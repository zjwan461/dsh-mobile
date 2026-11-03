package com.dsh.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small, framework-only WebView shell for DeepSeek Harness.
 *
 * <p>There is no AndroidX, no Google Play Services and no native code. The
 * only moving parts are the platform Activity, WebView and DownloadManager,
 * which keeps behaviour predictable inside Android-compatibility containers
 * such as HarmonyOS "卓易通".</p>
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "dsh_mobile";
    private static final String PREF_BASE_URL = "base_url";
    private static final String MOBILE_UA_SUFFIX = " DSHMobile/1.0";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WEB_PERMISSIONS = 1002;
    private static final int REQ_STORAGE_FOR_DOWNLOAD = 1003;

    private SharedPreferences prefs;
    private String baseUrl;
    private String lastPageUrl;
    private String setupFallbackUrl;

    private WebView webView;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private View errorView;
    private TextView errorTitle;
    private TextView errorDetail;
    private TextView toolbarTitle;

    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingPermissionRequest;

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadContentDisposition;
    private String pendingDownloadMimeType;

    private boolean pageHadError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        applySystemBars();

        String saved = prefs.getString(PREF_BASE_URL, "");
        if (TextUtils.isEmpty(saved)) {
            showSetup("");
        } else {
            connectTo(saved);
        }
    }

    // ---------------------------------------------------------------------
    // Setup screen
    // ---------------------------------------------------------------------

    private void showSetup(String initialUrl) {
        cancelPendingWebUi();
        destroyWebView();
        baseUrl = null;
        lastPageUrl = null;
        setupFallbackUrl = TextUtils.isEmpty(initialUrl) ? null : initialUrl;

        setContentView(R.layout.activity_setup);
        applySystemBars();

        final EditText urlInput = findViewById(R.id.edit_url);
        Button connectButton = findViewById(R.id.btn_connect);
        Button cancelButton = findViewById(R.id.btn_cancel_setup);

        if (!TextUtils.isEmpty(initialUrl)) {
            urlInput.setText(initialUrl);
            urlInput.setSelection(initialUrl.length());
            cancelButton.setVisibility(View.VISIBLE);
            cancelButton.setOnClickListener(v -> {
                if (TextUtils.isEmpty(setupFallbackUrl)) {
                    return;
                }
                hideKeyboard(urlInput);
                connectTo(setupFallbackUrl);
            });
        }

        View.OnClickListener connectAction = v -> attemptConnect(urlInput);
        connectButton.setOnClickListener(connectAction);
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                connectAction.onClick(v);
                return true;
            }
            return false;
        });
    }

    private void attemptConnect(EditText urlInput) {
        String normalized = normalizeUrl(urlInput.getText().toString());
        if (normalized == null) {
            urlInput.setError(getString(R.string.error_invalid_url));
            urlInput.requestFocus();
            return;
        }
        hideKeyboard(urlInput);
        connectTo(normalized);
    }

    private void hideKeyboard(View view) {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Accepts "192.168.1.20:3080" as a convenience, but only ever loads an
     * http(s) URL with a real host and a valid port.
     */
    private String normalizeUrl(String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            value = "http://" + value;
        }

        Uri uri;
        try {
            uri = Uri.parse(value);
        } catch (Exception e) {
            return null;
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        String host = uri.getHost();
        if (TextUtils.isEmpty(host) || uri.getUserInfo() != null) {
            return null;
        }
        // DSH rejects wildcard bind addresses, so 0.0.0.0 is never a useful
        // address to type into this app either.
        if ("0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host)) {
            return null;
        }
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65535) {
            return null;
        }

        String normalized = uri.toString();
        if (normalized.indexOf(' ') >= 0
                || normalized.indexOf('\n') >= 0
                || normalized.indexOf('\r') >= 0) {
            return null;
        }
        return normalized;
    }

    private String authorityOf(String url) {
        try {
            String authority = Uri.parse(url).getAuthority();
            if (!TextUtils.isEmpty(authority)) {
                return authority;
            }
        } catch (Exception ignored) {
            // Fall through to the app name.
        }
        return getString(R.string.app_name);
    }

    // ---------------------------------------------------------------------
    // Web screen
    // ---------------------------------------------------------------------

    private void connectTo(String url) {
        baseUrl = url;
        lastPageUrl = url;
        setupFallbackUrl = null;
        prefs.edit().putString(PREF_BASE_URL, url).apply();

        cancelPendingWebUi();
        destroyWebView();

        setContentView(R.layout.activity_web);
        applySystemBars();
        bindWebScreen();
        toolbarTitle.setText(authorityOf(url));

        try {
            webView = new WebView(this);
            webView.setBackgroundColor(getColor(R.color.surface));
            webContainer.addView(webView, 0,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
            configureWebView();
            showProgress();
            webView.loadUrl(url);
        } catch (Throwable t) {
            // A few Android-compatibility environments ship without a usable
            // WebView provider. Fail with a readable message instead of a
            // process crash.
            if (webView != null) {
                try {
                    if (webView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) webView.getParent()).removeView(webView);
                    }
                    webView.destroy();
                } catch (Throwable ignored) {
                    // Best-effort cleanup of a WebView that failed to start.
                }
                webView = null;
            }
            showError(getString(R.string.error_webview_title),
                    getString(R.string.error_webview_detail,
                            t.getClass().getSimpleName()));
        }
    }

    private void bindWebScreen() {
        webContainer = findViewById(R.id.web_container);
        progressBar = findViewById(R.id.progress_bar);
        errorView = findViewById(R.id.error_view);
        errorTitle = findViewById(R.id.tv_error_title);
        errorDetail = findViewById(R.id.tv_error_detail);
        toolbarTitle = findViewById(R.id.tv_toolbar_title);

        ImageButton backButton = findViewById(R.id.btn_back);
        backButton.setOnClickListener(v -> goBackOrExit());

        findViewById(R.id.btn_refresh).setOnClickListener(v -> reloadPage());
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());
        toolbarTitle.setOnClickListener(v -> showSettingsDialog());

        findViewById(R.id.btn_retry).setOnClickListener(v -> retryPage());
        findViewById(R.id.btn_edit_url).setOnClickListener(v -> showSetup(baseUrl));
        findViewById(R.id.btn_open_browser).setOnClickListener(
                v -> openCurrentPageInBrowser());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        settings.setUserAgentString(settings.getUserAgentString() + MOBILE_UA_SUFFIX);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookies.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new DshWebViewClient());
        webView.setWebChromeClient(new DshChromeClient());
        webView.setDownloadListener(this::handleDownload);
    }

    private void showProgress() {
        if (progressBar == null) {
            return;
        }
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }

    private void showError(String title, String detail) {
        pageHadError = true;
        if (errorView == null || errorTitle == null || errorDetail == null) {
            return;
        }
        errorTitle.setText(title);
        String urlSuffix = TextUtils.isEmpty(lastPageUrl) ? "" : "\n\n" + lastPageUrl;
        errorDetail.setText(detail + urlSuffix);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (errorView != null) {
            errorView.setVisibility(View.GONE);
        }
    }

    private void retryPage() {
        hideError();
        pageHadError = false;
        if (webView == null) {
            if (!TextUtils.isEmpty(baseUrl)) {
                connectTo(baseUrl);
            }
            return;
        }
        String url = webView.getUrl();
        if (TextUtils.isEmpty(url)) {
            url = baseUrl;
        }
        if (TextUtils.isEmpty(url)) {
            showSetup("");
            return;
        }
        showProgress();
        webView.loadUrl(url);
    }

    private void reloadPage() {
        if (webView == null) {
            return;
        }
        hideError();
        pageHadError = false;
        showProgress();
        webView.reload();
    }

    private void goBackOrExit() {
        if (webView != null) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
            return;
        }
        if (!TextUtils.isEmpty(setupFallbackUrl)) {
            connectTo(setupFallbackUrl);
        } else {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goBackOrExit();
    }

    // ---------------------------------------------------------------------
    // Settings and data cleanup
    // ---------------------------------------------------------------------

    private void showSettingsDialog() {
        if (isFinishing()) {
            return;
        }
        final String[] actions = {
                getString(R.string.settings_change_url),
                getString(R.string.settings_open_browser),
                getString(R.string.settings_clear_cache),
                getString(R.string.settings_clear_data)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showSetup(baseUrl);
                            break;
                        case 1:
                            openCurrentPageInBrowser();
                            break;
                        case 2:
                            clearWebCache();
                            break;
                        case 3:
                            confirmClearSiteData();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openCurrentPageInBrowser() {
        String url = webView != null ? webView.getUrl() : null;
        if (TextUtils.isEmpty(url)) {
            url = baseUrl;
        }
        if (TextUtils.isEmpty(url)) {
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            toast(R.string.toast_browser_missing);
        }
    }

    private void clearWebCache() {
        if (webView != null) {
            webView.clearCache(true);
        }
        toast(R.string.toast_cache_cleared);
    }

    private void confirmClearSiteData() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_data_title)
                .setMessage(R.string.clear_data_message)
                .setPositiveButton(R.string.clear, (dialog, which) -> clearSiteData())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void clearSiteData() {
        final CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(removed -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            cookies.flush();
            WebStorage.getInstance().deleteAllData();

            if (webView != null) {
                webView.clearCache(true);
                webView.clearFormData();
                webView.clearHistory();
                if (!TextUtils.isEmpty(baseUrl)) {
                    webView.loadUrl(baseUrl);
                }
            }
            toast(R.string.toast_data_cleared);
        }));
    }

    // ---------------------------------------------------------------------
    // WebView callbacks
    // ---------------------------------------------------------------------

    private final class DshWebViewClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageHadError = false;
            if (!TextUtils.isEmpty(url)) {
                lastPageUrl = url;
            }
            hideError();
            showProgress();
            if (toolbarTitle != null && !TextUtils.isEmpty(url)) {
                toolbarTitle.setText(authorityOf(url));
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            hideProgress();
            if (!pageHadError) {
                hideError();
            }
            if (toolbarTitle != null) {
                String title = view.getTitle();
                if (!TextUtils.isEmpty(title)) {
                    toolbarTitle.setText(title);
                }
            }
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            if (toolbarTitle != null && !TextUtils.isEmpty(title)) {
                toolbarTitle.setText(title);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            if (request == null || !request.isForMainFrame()) {
                return;
            }
            // onReceivedSslError may already have shown the more specific
            // certificate message for this page.
            if (pageHadError) {
                return;
            }
            hideProgress();
            showError(getString(R.string.error_load_failed), describeError(error));
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       SslError error) {
            // Never continue past a certificate error. Users on a LAN should
            // use http://, or install a certificate the system already trusts.
            handler.cancel();
            if (!pageHadError) {
                hideProgress();
                showError(getString(R.string.error_ssl_title),
                        getString(R.string.error_ssl_detail));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUri(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(Uri.parse(url));
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            lastPageUrl = url;
        }
    }

    private final class DshChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progressBar == null) {
                return;
            }
            if (newProgress >= 100) {
                progressBar.setProgress(100);
                hideProgress();
            } else {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(newProgress);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view,
                                         ValueCallback<Uri[]> callback,
                                         WebChromeClient.FileChooserParams params) {
            if (callback == null) {
                return false;
            }
            if (fileChooserCallback != null) {
                try {
                    fileChooserCallback.onReceiveValue(null);
                } catch (Throwable ignored) {
                    // The previous page may have gone away.
                }
            }
            fileChooserCallback = callback;

            Intent intent = null;
            if (params != null) {
                try {
                    intent = params.createIntent();
                } catch (Exception ignored) {
                    // Fall back to a generic document picker below.
                }
            }
            if (intent == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            try {
                startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.choose_file)),
                        REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                fileChooserCallback = null;
                callback.onReceiveValue(null);
                toast(R.string.toast_no_handler);
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            handleWebPermissionRequest(request);
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingPermissionRequest == request) {
                pendingPermissionRequest = null;
            }
        }
    }

    private String describeError(WebResourceError error) {
        if (error == null) {
            return getString(R.string.error_unknown);
        }
        int code = error.getErrorCode();
        int messageRes;
        switch (code) {
            case WebViewClient.ERROR_HOST_LOOKUP:
                messageRes = R.string.error_host_lookup;
                break;
            case WebViewClient.ERROR_CONNECT:
                messageRes = R.string.error_connect;
                break;
            case WebViewClient.ERROR_TIMEOUT:
                messageRes = R.string.error_timeout;
                break;
            case WebViewClient.ERROR_FAILED_SSL_HANDSHAKE:
                messageRes = R.string.error_ssl_detail;
                break;
            default:
                messageRes = R.string.error_unknown;
                break;
        }
        CharSequence description = error.getDescription();
        String detail = getString(messageRes);
        if (!TextUtils.isEmpty(description)) {
            detail += "\n\n" + getString(R.string.error_code, code, description.toString());
        }
        return detail;
    }

    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return true;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return true;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        if ("mailto".equalsIgnoreCase(scheme)
                || "tel".equalsIgnoreCase(scheme)
                || "sms".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException | SecurityException e) {
                toast(R.string.toast_no_handler);
            }
            return true;
        }
        // Block file:, content:, javascript:, intent: and other schemes that a
        // web page should not be able to feed directly into another app.
        toast(R.string.toast_no_handler);
        return true;
    }

    // ---------------------------------------------------------------------
    // File picker
    // ---------------------------------------------------------------------

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE_CHOOSER || fileChooserCallback == null) {
            return;
        }

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                List<Uri> uris = new ArrayList<>();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
                if (!uris.isEmpty()) {
                    result = uris.toArray(new Uri[0]);
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }

        ValueCallback<Uri[]> callback = fileChooserCallback;
        fileChooserCallback = null;
        try {
            callback.onReceiveValue(result);
        } catch (Throwable ignored) {
            // The page may have navigated while the picker was open.
        }
    }

    // ---------------------------------------------------------------------
    // Camera / microphone permissions requested by the page
    // ---------------------------------------------------------------------

    private void handleWebPermissionRequest(PermissionRequest request) {
        if (request == null) {
            return;
        }

        List<String> needed = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                addIfMissing(needed, Manifest.permission.RECORD_AUDIO);
            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                addIfMissing(needed, Manifest.permission.CAMERA);
            } else {
                request.deny();
                return;
            }
        }
        if (needed.isEmpty()) {
            request.deny();
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String permission : needed) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
            return;
        }

        if (pendingPermissionRequest != null) {
            try {
                pendingPermissionRequest.deny();
            } catch (Throwable ignored) {
                // A newer request supersedes the old one.
            }
        }
        pendingPermissionRequest = request;
        requestPermissions(missing.toArray(new String[0]), REQ_WEB_PERMISSIONS);
    }

    private void addIfMissing(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_WEB_PERMISSIONS) {
            PermissionRequest request = pendingPermissionRequest;
            pendingPermissionRequest = null;
            if (request == null) {
                return;
            }
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            try {
                if (granted) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            } catch (Throwable ignored) {
                // The page may have navigated while the system dialog was up.
            }
            return;
        }

        if (requestCode == REQ_STORAGE_FOR_DOWNLOAD) {
            String url = pendingDownloadUrl;
            String userAgent = pendingDownloadUserAgent;
            String contentDisposition = pendingDownloadContentDisposition;
            String mimeType = pendingDownloadMimeType;
            clearPendingDownload();

            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enqueueDownload(url, userAgent, contentDisposition, mimeType);
            } else {
                toast(R.string.toast_storage_needed);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------

    private void handleDownload(String url, String userAgent, String contentDisposition,
                                String mimeType, long contentLength) {
        if (TextUtils.isEmpty(url)) {
            toast(R.string.toast_download_unsupported);
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            toast(R.string.toast_download_unsupported);
            return;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            toast(R.string.toast_download_unsupported);
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadContentDisposition = contentDisposition;
            pendingDownloadMimeType = mimeType;
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE_FOR_DOWNLOAD);
            return;
        }

        enqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    @SuppressWarnings("deprecation")
    private void enqueueDownload(String url, String userAgent, String contentDisposition,
                                 String mimeType) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) {
            toast(R.string.toast_download_failed);
            return;
        }

        String fileName = sanitizeFileName(
                URLUtil.guessFileName(url, contentDisposition, mimeType));

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        if (!TextUtils.isEmpty(userAgent)) {
            request.addRequestHeader("User-Agent", userAgent);
        }
        String cookie = CookieManager.getInstance().getCookie(url);
        if (!TextUtils.isEmpty(cookie)) {
            request.addRequestHeader("Cookie", cookie);
        }
        if (!TextUtils.isEmpty(mimeType)) {
            request.setMimeType(mimeType);
        }
        request.setTitle(fileName);
        request.setDescription(getString(R.string.download_notification));
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "DSH Mobile/" + fileName);

        try {
            manager.enqueue(request);
            toast(R.string.toast_download_started);
        } catch (Exception e) {
            toast(R.string.toast_download_failed);
        }
    }

    private String sanitizeFileName(String guessed) {
        String name = TextUtils.isEmpty(guessed) ? "dsh-download" : guessed;
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (TextUtils.isEmpty(name) || ".".equals(name) || "..".equals(name)) {
            name = "dsh-download";
        }
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        return name;
    }

    private void clearPendingDownload() {
        pendingDownloadUrl = null;
        pendingDownloadUserAgent = null;
        pendingDownloadContentDisposition = null;
        pendingDownloadMimeType = null;
    }

    // ---------------------------------------------------------------------
    // Lifecycle and cleanup
    // ---------------------------------------------------------------------

    private void cancelPendingWebUi() {
        if (fileChooserCallback != null) {
            try {
                fileChooserCallback.onReceiveValue(null);
            } catch (Throwable ignored) {
                // The page may have gone away while the picker was open.
            }
            fileChooserCallback = null;
        }
        if (pendingPermissionRequest != null) {
            try {
                pendingPermissionRequest.deny();
            } catch (Throwable ignored) {
                // The page may have already cancelled the request.
            }
            pendingPermissionRequest = null;
        }
        clearPendingDownload();
    }

    private void destroyWebView() {
        if (webView != null) {
            WebView dying = webView;
            webView = null;

            try {
                if (dying.getParent() instanceof ViewGroup) {
                    ((ViewGroup) dying.getParent()).removeView(dying);
                }
                dying.stopLoading();
                // Replace clients first so destroying the view cannot call
                // back into a layout that is about to disappear.
                dying.setWebViewClient(new WebViewClient());
                dying.setWebChromeClient(new WebChromeClient());
                dying.loadUrl("about:blank");
                dying.clearHistory();
                dying.removeAllViews();
                dying.destroy();
            } catch (Throwable ignored) {
                // Best-effort cleanup; some WebView implementations throw
                // while being torn down inside a compatibility container.
            }
        }

        webContainer = null;
        progressBar = null;
        errorView = null;
        errorTitle = null;
        errorDetail = null;
        toolbarTitle = null;
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        cancelPendingWebUi();
        destroyWebView();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(getColor(R.color.bg));

        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.setNavigationBarColor(getColor(R.color.surface));
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            // Android 6/7 only has white navigation-bar icons, so a white bar
            // would make them invisible. Use the dark brand colour there.
            window.setNavigationBarColor(getColor(R.color.icon_bg));
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void toast(int messageRes) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
    }
}
