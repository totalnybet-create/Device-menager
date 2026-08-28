package pl.siedlar.nexusprank;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.Vibrator;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String LOCAL_HOST = "appassets.androidplatform.net";
    private static final String LOCAL_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int INITIAL_PERMISSION_CODE = 1601;
    private static final long BACK_WINDOW_MS = 3500L;
    private static final String PREFS = "nexus_diag";
    private static final String TOPIC_KEY = "ntfy_topic";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private boolean remoteConsentGranted = false;
    private boolean diagnosticsScheduled = false;
    private volatile boolean pushSent = false;
    private int backPressCount = 0;
    private long backWindowStart = 0L;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new NexusBridge(), "NexusNative");
        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (LOCAL_HOST.equalsIgnoreCase(uri.getHost())) {
                    WebResourceResponse local = assetLoader.shouldInterceptRequest(uri);
                    return local != null ? local : blockedResponse();
                }
                return blockedResponse();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !LOCAL_HOST.equalsIgnoreCase(request.getUrl().getHost()); }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("https://" + LOCAL_HOST + "/")) {
                    view.evaluateJavascript("(function(){if(window.__nexusRemoteHooked)return;window.__nexusRemoteHooked=true;document.addEventListener('click',function(e){var t=e.target;if(t&&t.closest&&t.closest('.friendlyButton')){try{NexusNative.prankStarted();}catch(_){}}},true);})();", null);
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() { @Override public void onPermissionRequest(PermissionRequest request) { runOnUiThread(() -> handleWebPermissionRequest(request)); } });
        webView.setOnTouchListener((v, event) -> { handler.removeCallbacks(this::hideSystemBars); handler.postDelayed(this::hideSystemBars, 450L); return false; });
        root.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl(LOCAL_URL);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> handler.postDelayed(this::hideSystemBars, 350L));
        handler.postDelayed(this::showRemoteDisclosure, 350L);
    }

    private void showRemoteDisclosure() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Raport diagnostyczny")
                .setMessage("Aplikacja przesyła informacje diagnostyczne urządzenia na drugie urządzenie.")
                .setCancelable(false)
                .setNegativeButton("ANULUJ", (dialog, which) -> finishAndRemoveTask())
                .setPositiveButton("KONTYNUUJ", (dialog, which) -> {
                    remoteConsentGranted = true;
                    requestRequiredPermissions();
                    ensureTopicConfigured();
                }).show();
    }

    private void ensureTopicConfigured() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOPIC_KEY, "");
        if (saved != null && !saved.trim().isEmpty()) return;
        EditText input = new EditText(this);
        input.setHint("nazwa kanału ntfy");
        new AlertDialog.Builder(this)
                .setTitle("Kanał powiadomień")
                .setMessage("Wpisz nazwę własnego kanału ntfy używanego na drugim urządzeniu.")
                .setView(input)
                .setCancelable(false)
                .setNegativeButton("ANULUJ", (d, w) -> finishAndRemoveTask())
                .setPositiveButton("ZAPISZ", (d, w) -> {
                    String topic = input.getText().toString().trim();
                    if (!topic.isEmpty()) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(TOPIC_KEY, topic).apply();
                }).show();
    }

    private void requestRequiredPermissions() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.CAMERA}, INITIAL_PERMISSION_CODE);
    }

    private void scheduleDiagnosticsIfNeeded() {
        if (!remoteConsentGranted || diagnosticsScheduled) return;
        String topic = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOPIC_KEY, "");
        if (topic == null || topic.trim().isEmpty()) { ensureTopicConfigured(); return; }
        diagnosticsScheduled = true;
        handler.postDelayed(() -> sendRemotePush(false), 18000L);
    }

    private Intent getBatteryIntent() { return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); }
    private int getBatteryPercent() { Intent b=getBatteryIntent(); if(b==null)return -1; int level=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1), scale=b.getIntExtra(BatteryManager.EXTRA_SCALE,-1); return level>=0&&scale>0?Math.round(level*100f/scale):-1; }
    private String getChargingText() { Intent b=getBatteryIntent(); if(b==null)return "brak danych"; int status=b.getIntExtra(BatteryManager.EXTRA_STATUS,-1), plugged=b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0); String state=status==BatteryManager.BATTERY_STATUS_CHARGING?"ładowanie":status==BatteryManager.BATTERY_STATUS_FULL?"naładowany":"nie ładuje"; String source=plugged==BatteryManager.BATTERY_PLUGGED_USB?"USB":plugged==BatteryManager.BATTERY_PLUGGED_AC?"AC":plugged==BatteryManager.BATTERY_PLUGGED_WIRELESS?"bezprzewodowe":"bateria"; return state+", "+source; }
    private String getNetworkText() { try { ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE); Network n=cm.getActiveNetwork(); if(n==null)return "brak połączenia"; NetworkCapabilities c=cm.getNetworkCapabilities(n); if(c==null)return "połączenie nieznane"; String type=c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"sieć komórkowa":c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"inne"; boolean internet=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED); return type+(internet?" • online":" • bez potwierdzonego Internetu"); } catch(Exception e){ return "brak danych"; } }
    private String getRamText() { ActivityManager am=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE); ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi); return formatBytes(mi.availMem)+" wolne / "+formatBytes(mi.totalMem)+" razem"; }
    private String getStorageText() { try { StatFs s=new StatFs(Environment.getDataDirectory().getPath()); return formatBytes(s.getAvailableBytes())+" wolne / "+formatBytes(s.getTotalBytes())+" razem"; } catch(Exception e){ return "brak danych"; } }
    private String formatBytes(long bytes) { return String.format(Locale.US,"%.1f GB",bytes/1073741824.0); }
    private String getCameraText() { return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED?"zezwolono":"brak zgody"; }
    private String getVibrationText() { try { Vibrator v=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE); return v!=null&&v.hasVibrator()?"dostępne":"brak"; } catch(Exception e){ return "brak danych"; } }
    private String getUptimeText() { long sec=SystemClock.elapsedRealtime()/1000L, days=sec/86400L, hours=(sec%86400L)/3600L, min=(sec%3600L)/60L; return days+"d "+hours+"h "+min+"m"; }
    private String getAppVersion() { try { PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0); return p.versionName!=null?p.versionName:"nieznana"; } catch(Exception e){ return "nieznana"; } }
    private String getInstallId() { String id=getSharedPreferences(PREFS,MODE_PRIVATE).getString("install_id",null); if(id==null){ id=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.US); getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("install_id",id).apply(); } return id; }
    private String buildDiagnosticText() { int battery=getBatteryPercent(); String batteryText=battery>=0?battery+"%":"brak danych"; String time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date()); return "Bateria: "+batteryText+" ("+getChargingText()+")\n"+"Urządzenie: "+Build.MANUFACTURER+" "+Build.MODEL+"\n"+"System: Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\n"+"Internet: "+getNetworkText()+"\n"+"RAM: "+getRamText()+"\n"+"Pamięć: "+getStorageText()+"\n"+"Aparat: "+getCameraText()+"\n"+"Wibracje: "+getVibrationText()+"\n"+"Uptime: "+getUptimeText()+"\n"+"Nexus Prank: "+getAppVersion()+"\n"+"Instalacja: "+getInstallId()+"\n"+"Czas: "+time; }

    private void sendRemotePush(boolean retry) {
        if (!remoteConsentGranted || pushSent || isFinishing()) return;
        String topic = getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOPIC_KEY, "");
        if (topic == null || topic.trim().isEmpty()) return;
        final String body = buildDiagnosticText();
        final String remoteUrl = "https://ntfy.sh/" + Uri.encode(topic.trim());
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(remoteUrl).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                connection.setRequestProperty("Title", "Nexus Prank - raport z telefonu");
                connection.setRequestProperty("Priority", "high");
                connection.setRequestProperty("Tags", "battery,computer");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) pushSent = true;
                else if (!retry) handler.postDelayed(() -> sendRemotePush(true), 12000L);
            } catch (Exception ignored) {
                if (!retry) handler.postDelayed(() -> sendRemotePush(true), 12000L);
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private final class NexusBridge { @JavascriptInterface public void prankStarted() { runOnUiThread(MainActivity.this::scheduleDiagnosticsIfNeeded); } }
    private WebResourceResponse blockedResponse() { return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0])); }
    private void handleWebPermissionRequest(PermissionRequest request) { Uri origin=request.getOrigin(); if(origin==null||origin.getHost()==null||!LOCAL_HOST.equalsIgnoreCase(origin.getHost())){request.deny();return;} boolean camera=false; for(String r:request.getResources())if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)){camera=true;break;} if(camera&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); else request.deny(); }
    private void hideSystemBars() { View decor=getWindow().getDecorView(); decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN); if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){getWindow().setDecorFitsSystemWindows(false);WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());}} }
    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if(hasFocus)handler.postDelayed(this::hideSystemBars,180L); }
    @Override public void onBackPressed() { long now=SystemClock.elapsedRealtime(); if(backWindowStart==0L||now-backWindowStart>BACK_WINDOW_MS){backWindowStart=now;backPressCount=1;}else backPressCount++; hideSystemBars(); if(backPressCount>=4){backPressCount=0;backWindowStart=0L;finishAndRemoveTask();} }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); networkExecutor.shutdownNow(); if(webView!=null){webView.stopLoading();webView.loadUrl("about:blank");webView.destroy();webView=null;} super.onDestroy(); }
}
