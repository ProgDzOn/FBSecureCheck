package com.securebook.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import android.content.ContentValues;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TARGET_URL = "https://progdzon.github.io/FBSecureCheck/";
    private static final String HOST = "progdzon.github.io";
    // أقل إصدار WebView نعتبره مناسباً لمواقع اليوم (الأقدم قد تتعطل فيه أزرار القوائم)
    private static final int MIN_WEBVIEW_MAJOR = 90;

    private WebView webView;
    private SwipeRefreshLayout swipe;
    private ProgressBar progress;
    private LinearLayout offlineView;
    private boolean hadError = false;

    // رفع الملفات والصور (مثل صور المنتجات) من داخل صفحات الموقع
    private ValueCallback<Uri[]> filePathCallback;
    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (filePathCallback == null) return;
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(
                        result.getResultCode(), result.getData());
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1530);

        webView = new WebView(this);
        swipe = new SwipeRefreshLayout(this);
        swipe.addView(webView);
        swipe.setColorSchemeColors(0xFF2F6BFF, 0xFF19C3D3);
        swipe.setOnRefreshListener(() -> webView.reload());
        // لا يبدأ السحب للتحديث إلا إذا كانت الصفحة في أعلاها
        swipe.setOnChildScrollUpCallback((parent, child) -> webView.getScrollY() > 0);
        root.addView(swipe, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(0xFF19C3D3));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, 8);
        plp.gravity = Gravity.TOP;
        root.addView(progress, plp);

        offlineView = buildOfflineView();
        root.addView(offlineView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // حفظ جلسة تسجيل الدخول و"تذكرني"
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        // الصفحة تحفظ ملفاتها (Excel/JSON) عبر الجسر SecureBookApp لأن روابط blob: لا يدعمها DownloadManager
        webView.addJavascriptInterface(new Bridge(), "SecureBookApp");
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url != null && url.startsWith("blob:")) return;
            startDownload(url, userAgent, contentDisposition, mimeType);
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            // بدون هذين الدالتين تفشل confirm() و alert() صامتة داخل WebView (مثل تأكيد الحذف والاستيراد)
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    fileChooserLauncher.launch(params.createIntent());
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null) return false;
                scheme = scheme.toLowerCase();
                // روابط داخلية تخص الصفحة نفسها (مثل أزرار القوائم javascript:) نتركها للمتصفح
                if (scheme.equals("javascript") || scheme.equals("about")
                        || scheme.equals("data") || scheme.equals("blob")) {
                    return false;
                }
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = uri.getHost();
                    if (host != null && (host.equals(HOST) || host.endsWith("." + HOST))) {
                        return false;
                    }
                }
                // روابط خارجية، اتصال هاتفي، بريد، واتساب...
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                hadError = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                swipe.setRefreshing(false);
                if (!hadError) offlineView.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    hadError = true;
                    swipe.setRefreshing(false);
                    offlineView.setVisibility(View.VISIBLE);
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // فحص الصفحة من الكمبيوتر عبر chrome://inspect (نسخة debug فقط)
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            load();
            checkWebViewVersion();
        }
    }

    // ------------------------------------------------ تنبيه WebView القديم
    private void checkWebViewVersion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            final PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi == null || pi.versionName == null) return;
            final int major = Integer.parseInt(pi.versionName.split("\\.")[0]);
            if (major >= MIN_WEBVIEW_MAJOR) return;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.webview_old_title)
                    .setMessage(getString(R.string.webview_old_msg, major))
                    .setPositiveButton(R.string.update_now, (d, w) -> openPlayStore(pi.packageName))
                    .setNegativeButton(R.string.later, null)
                    .show();
        } catch (Exception ignored) {
            // لا نزعج المستخدم إذا تعذر الفحص
        }
    }

    private void openPlayStore(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (Exception e) {
            openExternally(Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
        }
    }

    private void load() {
        if (isOnline()) {
            offlineView.setVisibility(View.GONE);
            webView.loadUrl(TARGET_URL);
        } else {
            offlineView.setVisibility(View.VISIBLE);
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // لا يوجد تطبيق يتعامل مع هذا الرابط
        }
    }

    // ------------------------------------------------------------- التحميلات
    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            dm.enqueue(buildDownload(url, userAgent, mimeType, name, true));
        } catch (Exception first) {
            try {
                // أجهزة قديمة بدون إذن الكتابة: نحفظ في مجلد التطبيق
                dm.enqueue(buildDownload(url, userAgent, mimeType, name, false));
            } catch (Exception second) {
                openExternally(Uri.parse(url));
                return;
            }
        }
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
    }

    private DownloadManager.Request buildDownload(String url, String userAgent, String mimeType,
                                                  String name, boolean publicDir) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) req.addRequestHeader("Cookie", cookie);
        if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
        if (mimeType != null) req.setMimeType(mimeType);
        req.setTitle(name);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        if (publicDir) {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        } else {
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
        }
        return req;
    }

    // ------------------------------------------------------ شاشة عدم الاتصال
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout buildOfflineView() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(0xFF0B1530);
        l.setPadding(dp(32), dp(32), dp(32), dp(32));
        l.setVisibility(View.GONE);
        l.setClickable(true);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        l.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView t = new TextView(this);
        t.setText(R.string.offline_title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(16), 0, 0);
        l.addView(t);

        TextView m = new TextView(this);
        m.setText(R.string.offline_msg);
        m.setTextColor(0xFFB9C7E2);
        m.setTextSize(14);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, dp(12), 0, dp(24));
        l.addView(m);

        Button b = new Button(this);
        b.setText(R.string.retry);
        b.setTextColor(0xFF0B1530);
        b.setBackgroundTintList(ColorStateList.valueOf(0xFF19C3D3));
        b.setOnClickListener(v -> load());
        l.addView(b);
        return l;
    }

    // ------------------------------------------- جسر الصفحة ↔ التطبيق (حفظ وطباعة)
    private class Bridge {
        @JavascriptInterface
        public void saveFile(String base64, String mime, String name) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String safe = (name == null || name.trim().isEmpty() ? "SecureBook.bin" : name)
                        .replaceAll("[\\\\/:*?\"<>|]", "_");
                boolean publicDir = writeFile(data, mime == null ? "application/octet-stream" : mime, safe);
                toastLater(publicDir ? R.string.file_saved : R.string.file_saved_app);
            } catch (Exception e) {
                toastLater(R.string.file_failed);
            }
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm == null) return;
                pm.print("SecureBook", webView.createPrintDocumentAdapter("SecureBook"),
                        new PrintAttributes.Builder().build());
            });
        }
    }

    private void toastLater(int res) {
        runOnUiThread(() -> Toast.makeText(this, res, Toast.LENGTH_LONG).show());
    }

    // يعيد true إذا حُفظ في مجلد التنزيلات العام، و false إذا حُفظ في مجلد التطبيق (أندرويد 9 وأقدم)
    private boolean writeFile(byte[] data, String mime, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, mime);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("insert failed");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("stream failed");
                out.write(data);
            }
            return true;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new Exception("no dir");
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(data);
        }
        return false;
    }

    // ------------------------------------------------------ دورة حياة النشاط
    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        webView.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
