package com.dsh.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Finds DeepSeek Harness instances on the local network and checks single
 * addresses before the WebView is asked to load them.
 *
 * <p>Deliberately plain Java: no Android classes, so the logic can be reasoned
 * about (and unit-tested) without an emulator. It only ever speaks HTTP to
 * hosts inside the phone's own subnet.</p>
 */
final class ServerDiscovery {

    /** Upper bound for a scan, so a /16 never turns into 65k probes. */
    private static final int MAX_HOSTS = 512;
    private static final int PROBE_THREADS = 32;
    /** TCP connect budget per host. LAN hosts answer in milliseconds. */
    private static final int CONNECT_TIMEOUT_MS = 400;
    private static final int HTTP_TIMEOUT_MS = 1500;
    private static final int MAX_BODY_BYTES = 8192;

    private static final Pattern TITLE =
            Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ServerDiscovery() {
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    static final class Server {
        final String scheme;
        final String host;
        final int port;
        /** Body or title mentioned DSH/DeepSeek, so it is very likely ours. */
        final boolean dshLike;
        final String title;

        Server(String scheme, String host, int port, boolean dshLike, String title) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.dshLike = dshLike;
            this.title = title;
        }

        String authority() {
            return host + ":" + port;
        }

        String url() {
            return scheme + "://" + authority() + "/";
        }

        String label() {
            if (title != null && !title.isEmpty()) {
                return authority() + "  ·  " + title;
            }
            return authority();
        }
    }

    interface Listener {
        /** Called on a worker thread whenever a candidate is discovered. */
        void onServerFound(Server server);

        /** Called on a worker thread with scan progress. */
        void onProgress(int scanned, int total);

        /**
         * Called once on a worker thread when the scan ends.
         *
         * @param reason null on success, otherwise a human readable failure
         *               such as "no Wi-Fi or Ethernet address".
         */
        void onFinished(String reason);
    }

    static final class Scan {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final ExecutorService pool;

        Scan(ExecutorService pool) {
            this.pool = pool;
        }

        void cancel() {
            cancelled.set(true);
            pool.shutdownNow();
        }

        boolean isCancelled() {
            return cancelled.get();
        }
    }

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    /**
     * Starts a background scan of this device's own subnet. Returns
     * immediately; results arrive through the listener. Call
     * {@link Scan#cancel()} when the screen goes away.
     */
    static Scan start(final List<Integer> ports, final Listener listener) {
        return start(ports, localHosts(), listener);
    }

    /**
     * Same as {@link #start(List, Listener)} but with an explicit host list.
     * Kept separate so the scan pipeline can be exercised without depending on
     * whatever network the machine running the test happens to have.
     */
    static Scan start(final List<Integer> ports, final List<String> hosts,
                      final Listener listener) {
        final ExecutorService pool = Executors.newFixedThreadPool(PROBE_THREADS);
        final Scan scan = new Scan(pool);
        if (hosts == null || hosts.isEmpty()) {
            pool.shutdown();
            listener.onFinished("no-local-address");
            return scan;
        }

        final List<Integer> safePorts = (ports == null || ports.isEmpty())
                ? Collections.singletonList(3080)
                : new ArrayList<>(new LinkedHashSet<>(ports));
        final int total = hosts.size();
        final AtomicInteger scanned = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(total);

        for (final String host : hosts) {
            pool.execute(() -> {
                try {
                    if (scan.isCancelled()) {
                        return;
                    }
                    for (Integer port : safePorts) {
                        if (port == null || port < 1 || port > 65535) {
                            continue;
                        }
                        Server found = probeHost(host, port);
                        if (found != null && !scan.isCancelled()) {
                            listener.onServerFound(found);
                        }
                    }
                } finally {
                    int done = scanned.incrementAndGet();
                    if (done % 8 == 0 || done == total) {
                        listener.onProgress(done, total);
                    }
                    latch.countDown();
                }
            });
        }

        // Separate monitor thread so the caller thread is never blocked.
        Thread monitor = new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pool.shutdown();
            listener.onFinished(scan.isCancelled() ? "cancelled" : null);
        }, "dsh-discovery-monitor");
        monitor.setDaemon(true);
        monitor.start();
        return scan;
    }

    /**
     * @return a Server when {@code host:port} answers, otherwise null.
     */
    private static Server probeHost(String host, int port) {
        // Cheap TCP gate first: most of the subnet is closed and must not pay
        // for a full HTTP exchange.
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        } catch (IOException | SecurityException e) {
            return null;
        }

        HttpResult http = fetch("http://" + host + ":" + port + "/", false);
        String scheme = "http";
        if (http == null) {
            // DSH can be started with --tls-cert. For discovery only, accept a
            // self-signed certificate: this request just identifies the
            // service, it does not carry user data. The WebView still asks the
            // user before trusting that certificate.
            http = fetch("https://" + host + ":" + port + "/", true);
            scheme = "https";
        }
        if (http == null) {
            // Port is open but it is not speaking HTTP(S); not useful to us.
            return null;
        }
        return new Server(scheme, host, port, http.dshLike, http.title);
    }

    // ------------------------------------------------------------------
    // Single address check, used before loading the WebView
    // ------------------------------------------------------------------

    enum Kind {
        OK,
        UNKNOWN_HOST,
        REFUSED,
        TIMEOUT,
        TLS_UNTRUSTED,
        HTTP_ERROR,
        UNKNOWN
    }

    static final class ProbeResult {
        final Kind kind;
        final int httpCode;
        final String detail;

        ProbeResult(Kind kind, int httpCode, String detail) {
            this.kind = kind;
            this.httpCode = httpCode;
            this.detail = detail;
        }

        boolean isUsable() {
            // A self-signed certificate is still worth continuing to: the app
            // asks the user about it inside the WebView.
            return kind == Kind.OK || kind == Kind.TLS_UNTRUSTED || kind == Kind.HTTP_ERROR;
        }
    }

    /** Blocking; call from a worker thread. */
    static ProbeResult probe(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode();
            if (code >= 500) {
                return new ProbeResult(Kind.HTTP_ERROR, code, "HTTP " + code);
            }
            return new ProbeResult(Kind.OK, code, "HTTP " + code);
        } catch (UnknownHostException e) {
            return new ProbeResult(Kind.UNKNOWN_HOST, -1, e.getClass().getSimpleName());
        } catch (ConnectException e) {
            return new ProbeResult(Kind.REFUSED, -1, e.getClass().getSimpleName());
        } catch (SocketTimeoutException e) {
            return new ProbeResult(Kind.TIMEOUT, -1, e.getClass().getSimpleName());
        } catch (SSLException e) {
            return new ProbeResult(Kind.TLS_UNTRUSTED, -1, e.getClass().getSimpleName());
        } catch (IOException | SecurityException e) {
            return new ProbeResult(Kind.UNKNOWN, -1,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP details
    // ------------------------------------------------------------------

    private static volatile SSLSocketFactory discoveryTlsFactory;

    /**
     * Trust-all factory used ONLY to identify a LAN service during discovery.
     * It is never used to load pages or transfer data.
     */
    private static SSLSocketFactory discoveryTlsFactory() {
        SSLSocketFactory local = discoveryTlsFactory;
        if (local != null) {
            return local;
        }
        synchronized (ServerDiscovery.class) {
            if (discoveryTlsFactory == null) {
                try {
                    SSLContext context = SSLContext.getInstance("TLS");
                    context.init(null, new TrustManager[]{new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }}, new SecureRandom());
                    discoveryTlsFactory = context.getSocketFactory();
                } catch (Exception e) {
                    return null;
                }
            }
            return discoveryTlsFactory;
        }
    }

    private static final class HttpResult {
        final boolean dshLike;
        final String title;

        HttpResult(boolean dshLike, String title) {
            this.dshLike = dshLike;
            this.title = title;
        }
    }

    private static HttpResult fetch(String url, boolean allowSelfSigned) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            if (allowSelfSigned && connection instanceof HttpsURLConnection) {
                SSLSocketFactory factory = discoveryTlsFactory();
                if (factory == null) {
                    return null;
                }
                HttpsURLConnection https = (HttpsURLConnection) connection;
                https.setSSLSocketFactory(factory);
                https.setHostnameVerifier((hostname, session) -> true);
            }
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Connection", "close");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) {
                // 401/403 still means something is there; keep it as a plain
                // candidate so the user can tap it.
                return new HttpResult(false, null);
            }
            String body = readBody(connection);
            String lower = body.toLowerCase(Locale.US);
            String title = extractTitle(body);
            boolean dshLike = lower.contains("deepseek harness")
                    || lower.contains("dsh")
                    || lower.contains("deepseek");
            return new HttpResult(dshLike, title);
        } catch (IOException | SecurityException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection connection) {
        InputStream stream = null;
        try {
            stream = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int read;
            while ((read = stream.read(chunk)) != -1 && buffer.size() < MAX_BODY_BYTES) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Nothing useful to do on a failed close.
                }
            }
        }
    }

    private static String extractTitle(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher matcher = TITLE.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        String title = matcher.group(1).replaceAll("\\s+", " ").trim();
        return title.length() > 60 ? title.substring(0, 60) : title;
    }

    // ------------------------------------------------------------------
    // Local subnet
    // ------------------------------------------------------------------

    /**
     * IPv4 hosts in this device's own subnet, never wider than a /24 and never
     * including the device itself.
     */
    static List<String> localHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : iface.getInterfaceAddresses()) {
                    InetAddress inet = address.getAddress();
                    if (!(inet instanceof Inet4Address)
                            || inet.isLoopbackAddress()
                            || inet.isLinkLocalAddress()) {
                        continue;
                    }
                    addSubnet(hosts, inet, address.getNetworkPrefixLength());
                }
            }
        } catch (Exception ignored) {
            // Fall through with whatever was collected.
        }
        if (hosts.size() > MAX_HOSTS) {
            return new ArrayList<>(hosts).subList(0, MAX_HOSTS);
        }
        return new ArrayList<>(hosts);
    }

    private static void addSubnet(Set<String> hosts, InetAddress inet, int prefixLength) {
        byte[] raw = inet.getAddress();
        int ip = toInt(raw);
        int prefix = prefixLength;
        if (prefix <= 0 || prefix > 30) {
            prefix = 24;
        }
        // A /16 or wider would mean thousands of probes; scan the /24 instead.
        int effective = Math.max(prefix, 24);
        int mask = -1 << (32 - effective);
        int network = ip & mask;
        int broadcast = network | ~mask;
        for (int candidate = network + 1; candidate < broadcast; candidate++) {
            if (candidate == ip) {
                continue;
            }
            hosts.add(intToIp(candidate));
            if (hosts.size() >= MAX_HOSTS) {
                return;
            }
        }
    }

    private static int toInt(byte[] raw) {
        return ((raw[0] & 0xFF) << 24)
                | ((raw[1] & 0xFF) << 16)
                | ((raw[2] & 0xFF) << 8)
                | (raw[3] & 0xFF);
    }

    private static String intToIp(int value) {
        return ((value >>> 24) & 0xFF) + "."
                + ((value >>> 16) & 0xFF) + "."
                + ((value >>> 8) & 0xFF) + "."
                + (value & 0xFF);
    }
}