package pl.siedlar.nexusprank;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String LOCAL_HOST = "appassets.androidplatform.net";
    private static final String LOCAL_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int CAMERA_PERMISSION_CODE = 1201;
    private static final int INITIAL_PERMISSION_CODE = 1501;
    private static final long BACK_WINDOW_MS = 3500L;
    private static final String CHANNEL_ID = "nexus_diagnostics";
    private static final int DIAGNOSTIC_NOTIFICATION_ID = 15001;
    private static final String REMOTE_TOPIC = "nexus-TeWNRhadhIEPgqWfvBDxxWrQHD6qg9dd";
    private static final String REMOTE_URL = "https://ntfy.sh/" + REMOTE_TOPIC;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private PermissionRequest pendingCameraRequest;
    private int backPressCount = 0;
    private long backWindowStart = 0L;
    private Location latestLocation;
    private LocationManager locationManager;
    private boolean remoteConsentGranted = false;
    private boolean diagnosticsScheduled = false;
    private boolean pushSent = false;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            latestLocation = location;
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();
        createNotificationChannel();

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new NexusBridge(), "NexusNative");

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (LOCAL_HOST.equalsIgnoreCase(uri.getHost())) {
                    WebResourceResponse local = assetLoader.shouldInterceptRequest(uri);
                    return local != null ? local : blockedResponse();
                }
                return blockedResponse();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !LOCAL_HOST.equalsIgnoreCase(request.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("https://" + LOCAL_HOST + "/")) {
                    view.evaluateJavascript(
                            "(function(){if(window.__nexusRemoteHooked)return;window.__nexusRemoteHooked=true;" +
                            "document.addEventListener('click',function(e){var t=e.target;" +
                            "if(t&&t.closest&&t.closest('.friendlyButton')){try{NexusNative.prankStarted();}catch(_){}}},true);})();",
                            null
                    );
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingCameraRequest == request) {
                    pendingCameraRequest = null;
                }
            }
        });

        webView.setOnTouchListener((v, event) -> {
            handler.removeCallbacks(this::hideSystemBars);
            handler.postDelayed(this::hideSystemBars, 450L);
            return false;
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);
        webView.loadUrl(LOCAL_URL);

        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility ->
                handler.postDelayed(this::hideSystemBars, 350L)
        );

        handler.postDelayed(this::showInitialConsent, 350L);
    }

    private void showInitialConsent() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Zgody i zdalny raport")
                .setMessage("Po akceptacji Nexus Prank może użyć aparatu i lokalizacji. W trakcie testu raport diagnostyczny zostanie wysłany przez internet na prywatny kanał powiadomień na Twoim drugim telefonie. Raport zawiera: poziom baterii i ładowanie, GPS i dokładność, model telefonu, wersję Androida oraz czas. Android pokaże osobne systemowe okna zgody dla aparatu, lokalizacji i powiadomień.")
                .setCancelable(false)
                .setNegativeButton("ANULUJ", (dialog, which) -> finishAndRemoveTask())
                .setPositiveButton("AKCEPTUJ I KONTYNUUJ", (dialog, which) -> {
                    remoteConsentGranted = true;
                    requestInitialPermissions();
                })
                .show();
    }

    private void requestInitialPermissions() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), INITIAL_PERMISSION_CODE);
        }
    }

    private void scheduleDiagnosticsIfNeeded() {
        if (!remoteConsentGranted || diagnosticsScheduled) return;
        diagnosticsScheduled = true;
        startLocationCapture();
        handler.postDelayed(() -> {
            showDiagnosticNotification();
            sendRemotePush(false);
        }, 18000L);
    }

    private void createNotificationChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Nexus diagnostyka",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Lokalne informacje diagnostyczne Nexus Prank");
        manager.createNotificationChannel(channel);
    }

    private void startLocationCapture() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            latestLocation = newer(gps, network);
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper());
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper());
            }
            handler.postDelayed(this::stopLocationCapture, 30000L);
        } catch (SecurityException ignored) {
        }
    }

    private Location newer(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getTime() >= b.getTime() ? a : b;
    }

    private void stopLocationCapture() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
    }

    private Intent getBatteryIntent() {
        return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private int getBatteryPercent() {
        Intent battery = getBatteryIntent();
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return Math.round(level * 100f / scale);
    }

    private String getChargingText() {
        Intent battery = getBatteryIntent();
        if (battery == null) return "brak danych";
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) return "ładowanie";
        if (status == BatteryManager.BATTERY_STATUS_FULL) return "naładowany";
        if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) return "rozładowywanie";
        if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) return "nie ładuje";
        return "stan nieznany";
    }

    private String getLocationText() {
        Location location = latestLocation;
        if (location == null) return "brak aktualnego fixu";
        return String.format(Locale.US, "%.5f, %.5f", location.getLatitude(), location.getLongitude());
    }

    private String getAccuracyText() {
        Location location = latestLocation;
        if (location == null || !location.hasAccuracy()) return "brak danych";
        return String.format(Locale.US, "±%.0f m", location.getAccuracy());
    }

    private String buildDiagnosticText() {
        int battery = getBatteryPercent();
        String batteryText = battery >= 0 ? battery + "%" : "brak danych";
        String device = Build.MANUFACTURER + " " + Build.MODEL;
        String androidVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        return "Bateria: " + batteryText + " (" + getChargingText() + ")\n" +
                "GPS: " + getLocationText() + "\n" +
                "Dokładność: " + getAccuracyText() + "\n" +
                "Urządzenie: " + device + "\n" +
                "System: " + androidVersion + "\n" +
                "Czas: " + time;
    }

    private void showDiagnosticNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String details = buildDiagnosticText();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Nexus Prank — raport diagnostyczny")
                .setContentText("Raport gotowy")
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setAutoCancel(true)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(DIAGNOSTIC_NOTIFICATION_ID, notification);
    }

    private void sendRemotePush(boolean retry) {
        if (!remoteConsentGranted || pushSent || isFinishing()) return;
        final String body = buildDiagnosticText();
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(REMOTE_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                connection.setRequestProperty("Title", "Nexus Prank - raport z telefonu");
                connection.setRequestProperty("Priority", "high");
                connection.setRequestProperty("Tags", "battery,round_pushpin");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    pushSent = true;
                } else if (!retry) {
                    handler.postDelayed(() -> sendRemotePush(true), 12000L);
                }
            } catch (Exception ignored) {
                if (!retry) {
                    handler.postDelayed(() -> sendRemotePush(true), 12000L);
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private final class NexusBridge {
        @JavascriptInterface
        public void prankStarted() {
            runOnUiThread(MainActivity.this::scheduleDiagnosticsIfNeeded);
        }
    }

    private WebResourceResponse blockedResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                java.util.Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0])
        );
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        Uri origin = request.getOrigin();
        if (origin == null || origin.getHost() == null || !LOCAL_HOST.equalsIgnoreCase(origin.getHost())) {
            request.deny();
            return;
        }

        boolean asksForCamera = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                asksForCamera = true;
                break;
            }
        }
        if (!asksForCamera) {
            request.deny();
            return;
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            return;
        }

        pendingCameraRequest = request;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == INITIAL_PERMISSION_CODE) {
            return;
        }
        if (requestCode == CAMERA_PERMISSION_CODE && pendingCameraRequest != null) {
            PermissionRequest request = pendingCameraRequest;
            pendingCameraRequest = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                request.deny();
            }
        }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            handler.postDelayed(this::hideSystemBars, 180L);
        }
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.elapsedRealtime();
        if (backWindowStart == 0L || now - backWindowStart > BACK_WINDOW_MS) {
            backWindowStart = now;
            backPressCount = 1;
        } else {
            backPressCount += 1;
        }
        hideSystemBars();
        if (backPressCount >= 4) {
            backPressCount = 0;
            backWindowStart = 0L;
            finishAndRemoveTask();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopLocationCapture();
        networkExecutor.shutdownNow();
        if (pendingCameraRequest != null) {
            pendingCameraRequest.deny();
            pendingCameraRequest = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
