package com.dsh.mobile;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Local harness for {@link ServerDiscovery}. Runs on a plain JDK: the
 * discovery class deliberately has no Android dependency, so its real behaviour
 * can be exercised without an emulator or the Android SDK.
 *
 * <pre>
 * javac -d out app/src/main/java/com/dsh/mobile/ServerDiscovery.java tools/DiscoveryCheck.java
 * java -cp out com.dsh.mobile.DiscoveryCheck
 * </pre>
 */
public final class DiscoveryCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = ("<!doctype html><html><head><title>DeepSeek Harness</title></head>"
                    + "<body>dashboard</body></html>").getBytes("UTF-8");
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        int port = server.getAddress().getPort();

        HttpsServer tlsServer = startTlsServer();
        int tlsPort = tlsServer.getAddress().getPort();

        try {
            checkProbeOk(port);
            checkProbeRefused();
            checkProbeUnknownHost();
            checkProbeTlsUntrusted(tlsPort);
            checkScanFindsServer(port);
            checkScanFindsHttpsServer(tlsPort);
            checkScanOnEmptyHosts();
            checkLocalHostsLookSane();
        } finally {
            server.stop(0);
            tlsServer.stop(0);
        }

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void checkProbeOk(int port) {
        ServerDiscovery.ProbeResult result =
                ServerDiscovery.probe("http://127.0.0.1:" + port + "/");
        report("probe reaches a live HTTP server",
                result.kind == ServerDiscovery.Kind.OK && result.isUsable(),
                result.kind + " " + result.detail);
    }

    private static void checkProbeRefused() {
        // Port 1 on loopback is closed on every sane machine.
        ServerDiscovery.ProbeResult result = ServerDiscovery.probe("http://127.0.0.1:1/");
        report("probe reports a refused port",
                result.kind == ServerDiscovery.Kind.REFUSED && !result.isUsable(),
                result.kind + " " + result.detail);
    }

    private static void checkProbeUnknownHost() {
        ServerDiscovery.ProbeResult result =
                ServerDiscovery.probe("http://dsh-mobile.invalid/");
        report("probe reports an unknown host",
                result.kind == ServerDiscovery.Kind.UNKNOWN_HOST && !result.isUsable(),
                result.kind + " " + result.detail);
    }

    /**
     * Serves HTTPS with the project's own self-signed debug certificate, which
     * is exactly what "DSH behind a self-signed certificate" looks like to the
     * app: the TCP connection works, the handshake is refused.
     */
    private static HttpsServer startTlsServer() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream("app/dsh-debug.keystore")) {
            keyStore.load(in, "android".toCharArray());
        }
        KeyManagerFactory factory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, "android".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/", exchange -> {
            byte[] body = "<html><title>DSH self-signed</title></html>".getBytes("UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        return server;
    }

    private static void checkProbeTlsUntrusted(int tlsPort) {
        ServerDiscovery.ProbeResult result =
                ServerDiscovery.probe("https://127.0.0.1:" + tlsPort + "/");
        report("probe treats a self-signed certificate as 'continue anyway'",
                result.kind == ServerDiscovery.Kind.TLS_UNTRUSTED && result.isUsable(),
                result.kind + " " + result.detail);
    }

    private static void checkScanFindsServer(int port) throws Exception {
        final List<ServerDiscovery.Server> found = new CopyOnWriteArrayList<>();
        final List<String> reasons = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);

        ServerDiscovery.start(Collections.singletonList(port),
                Arrays.asList("127.0.0.1", "127.0.0.2"),
                new ServerDiscovery.Listener() {
                    @Override
                    public void onServerFound(ServerDiscovery.Server server) {
                        found.add(server);
                    }

                    @Override
                    public void onProgress(int scanned, int total) {
                        // Progress is informational only.
                    }

                    @Override
                    public void onFinished(String reason) {
                        reasons.add(reason == null ? "null" : reason);
                        done.countDown();
                    }
                });

        boolean finished = done.await(30, TimeUnit.SECONDS);
        boolean ok = finished
                && found.size() == 1
                && found.get(0).port == port
                && found.get(0).dshLike
                && found.get(0).label().contains("DeepSeek Harness");
        report("scan finds the live server and reads its title", ok,
                "finished=" + finished + " found=" + found.size() + " reasons=" + reasons);
    }

    private static void checkScanFindsHttpsServer(int tlsPort) throws Exception {
        final List<ServerDiscovery.Server> found = new CopyOnWriteArrayList<>();
        final List<String> reasons = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);

        ServerDiscovery.start(Collections.singletonList(tlsPort),
                Collections.singletonList("127.0.0.1"),
                new ServerDiscovery.Listener() {
                    @Override
                    public void onServerFound(ServerDiscovery.Server server) {
                        found.add(server);
                    }

                    @Override
                    public void onProgress(int scanned, int total) {
                    }

                    @Override
                    public void onFinished(String reason) {
                        reasons.add(reason == null ? "null" : reason);
                        done.countDown();
                    }
                });

        boolean finished = done.await(30, TimeUnit.SECONDS);
        boolean ok = finished
                && found.size() == 1
                && found.get(0).url().startsWith("https://")
                && found.get(0).title != null
                && found.get(0).title.contains("self-signed");
        report("scan discovers an HTTPS service behind a self-signed cert", ok,
                "finished=" + finished + " found=" + found.size()
                        + " reasons=" + reasons
                        + (found.isEmpty() ? "" : " url=" + found.get(0).url()));
    }

    private static void checkScanOnEmptyHosts() throws Exception {        final List<String> reasons = new CopyOnWriteArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        ServerDiscovery.start(Collections.singletonList(3080), new ArrayList<String>(),
                new ServerDiscovery.Listener() {
                    @Override
                    public void onServerFound(ServerDiscovery.Server server) {
                    }

                    @Override
                    public void onProgress(int scanned, int total) {
                    }

                    @Override
                    public void onFinished(String reason) {
                        reasons.add(String.valueOf(reason));
                        done.countDown();
                    }
                });
        boolean finished = done.await(5, TimeUnit.SECONDS);
        report("scan reports 'no-local-address' instead of hanging",
                finished && reasons.size() == 1 && "no-local-address".equals(reasons.get(0)),
                "finished=" + finished + " reasons=" + reasons);
    }

    private static void checkLocalHostsLookSane() {
        List<String> hosts = ServerDiscovery.localHosts();
        boolean ok = !hosts.isEmpty();
        for (String host : hosts) {
            String[] parts = host.split("\\.");
            if (parts.length != 4) {
                ok = false;
                break;
            }
            for (String part : parts) {
                try {
                    int value = Integer.parseInt(part);
                    if (value < 0 || value > 255) {
                        ok = false;
                    }
                } catch (NumberFormatException e) {
                    ok = false;
                }
            }
        }
        if (hosts.size() > 512) {
            ok = false;
        }
        report("local subnet enumeration yields bounded IPv4 hosts", ok,
                hosts.size() + " hosts, first=" + (hosts.isEmpty() ? "-" : hosts.get(0)));
    }

    private static void report(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "   [" + detail + "]");
    }
}