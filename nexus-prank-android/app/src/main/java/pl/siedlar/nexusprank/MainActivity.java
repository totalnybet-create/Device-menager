package pl.siedlar.nexusprank;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.Gravity;
import android.view.View;
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
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int CAMERA_REQUEST = 1001;
    private static final String LOCAL_URL = "https://appassets.androidplatform.net/assets/index.html";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private PermissionRequest pendingPermissionRequest;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private float ax, ay, az, gx, gy, gz;
    private long lastSensorPush;
    private String torchCameraId;
    private long lastTorchPulse;
    private final Runnable rehideBars = this::hideSystemUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        root.addView(buildCloseButton());
        setContentView(root);

        configureWebView();
        configureSensors();
        resolveTorchCamera();
        configureSystemUiRehide();
        hideSystemUi();
        webView.loadUrl(LOCAL_URL);
    }

    private TextView buildCloseButton() {
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(Color.WHITE);
        close.setTextSize(30f);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Zamknij Nexus Prank");
        close.setElevation(dp(14));
        close.setOnClickListener(v -> closeApp());

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x8A000000);
        bg.setStroke(dp(1), 0x66FFFFFF);
        close.setBackground(bg);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        lp.topMargin = dp(10);
        lp.rightMargin = dp(10);
        close.setLayoutParams(lp);
        return close;
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.addJavascriptInterface(new NativeBridge(), "NativeBridge");

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectNativeEnhancements();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermissionRequest(request));
            }
        });
    }

    private void injectNativeEnhancements() {
        String js = "(function(){" +
            "if(window.__nexusNativeInstalled)return;window.__nexusNativeInstalled=true;" +
            "try{window.NEXUS_DEVICE=JSON.parse(NativeBridge.getDeviceInfo())}catch(e){}" +
            "window.__nexusSensor=function(ax,ay,az,gx,gy,gz){var r=document.getElementById('root');if(!r)return;if(document.querySelector('.chaosStage')){var dx=Math.max(-9,Math.min(9,-ax*0.9+gy*2.2));var dy=Math.max(-9,Math.min(9,ay*0.9+gx*2.2));r.style.transform='translate3d('+dx+'px,'+dy+'px,0)';}else{r.style.transform='';}};" +
            "try{Object.defineProperty(navigator,'vibrate',{configurable:true,value:function(p){try{NativeBridge.vibrate(JSON.stringify(p));return true}catch(e){return false}}})}catch(e){}" +
            "var last='';var dimmed=false;var chaosDone=false;" +
            "function sync(){var s='idle';if(document.querySelector('.chaosStage'))s='chaos';else if(document.querySelector('.cameraScreen'))s='camera';else if(document.querySelector('.hardResetScreen'))s='hardreset';else if(document.querySelector('.recoveryScreen'))s='recovery';else if(document.querySelector('.androidBoot'))s='boot';else if(document.querySelector('.blackout'))s='blackout';else if(document.querySelector('.calibration'))s='calibrate';else if(document.querySelector('.cyber'))s='cyber';if(s!==last){last=s;try{NativeBridge.stage(s)}catch(e){}if(s==='chaos'&&!chaosDone){chaosDone=true;try{NativeBridge.pulseTorch(120);NativeBridge.tone(880,140)}catch(e){}}}if(document.querySelector('.finalDim')&&!dimmed){dimmed=true;try{NativeBridge.setBrightness(0.01)}catch(e){}}}" +
            "new MutationObserver(sync).observe(document.documentElement,{childList:true,subtree:true,attributes:true,classFilter:['class']});sync();" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void handleWebPermissionRequest(PermissionRequest request) {
        boolean wantsVideo = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                wantsVideo = true;
                break;
            }
        }
        if (!wantsVideo) {
            request.deny();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            return;
        }
        pendingPermissionRequest = request;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST && pendingPermissionRequest != null) {
            PermissionRequest request = pendingPermissionRequest;
            pendingPermissionRequest = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                request.deny();
            }
        }
    }

    private void configureSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            ax = event.values[0]; ay = event.values[1]; az = event.values[2];
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gx = event.values[0]; gy = event.values[1]; gz = event.values[2];
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSensorPush < 55 || webView == null) return;
        lastSensorPush = now;
        String js = "window.__nexusSensor&&window.__nexusSensor(" + ax + "," + ay + "," + az + "," + gx + "," + gy + "," + gz + ")";
        webView.evaluateJavascript(js, null);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void resolveTorchCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    torchCameraId = id;
                    break;
                }
            }
        } catch (Exception ignored) { }
    }

    private void hideSystemUi() {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void configureSystemUiRehide() {
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(visibility -> scheduleRehide());
    }

    private void scheduleRehide() {
        mainHandler.removeCallbacks(rehideBars);
        mainHandler.postDelayed(rehideBars, 90);
    }

    private void closeApp() {
        setWindowBrightness(-1f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
        else finish();
    }

    private void setWindowBrightness(float value) {
        runOnUiThread(() -> {
            WindowManager.LayoutParams p = getWindow().getAttributes();
            p.screenBrightness = value < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.01f, Math.min(1f, value));
            getWindow().setAttributes(p);
        });
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm == null ? null : vm.getDefaultVibrator();
        }
        return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    }

    private void performVibration(String raw) {
        try {
            Vibrator vibrator = getVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (raw == null || raw.equals("null")) return;
            if (raw.startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                int n = Math.min(arr.length(), 24);
                long[] timings = new long[n];
                int[] amplitudes = new int[n];
                for (int i = 0; i < n; i++) {
                    timings[i] = Math.max(0, Math.min(1200, arr.optLong(i, 0)));
                    amplitudes[i] = (i % 2 == 0) ? 235 : 0;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1));
                else vibrator.vibrate(timings, -1);
            } else {
                long ms = Math.max(0, Math.min(1200, Long.parseLong(raw)));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(ms, 235));
                else vibrator.vibrate(ms);
            }
        } catch (Exception ignored) { }
    }

    private void pulseTorchNative(int durationMs) {
        if (torchCameraId == null || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTorchPulse < 2000) return;
        lastTorchPulse = now;
        int duration = Math.max(60, Math.min(250, durationMs));
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            manager.setTorchMode(torchCameraId, true);
            mainHandler.postDelayed(() -> {
                try { manager.setTorchMode(torchCameraId, false); } catch (Exception ignored) { }
            }, duration);
        } catch (Exception ignored) { }
    }

    private void toneNative(int durationMs) {
        int duration = Math.max(50, Math.min(400, durationMs));
        ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 65);
        tg.startTone(ToneGenerator.TONE_PROP_BEEP, duration);
        mainHandler.postDelayed(tg::release, duration + 80L);
    }

    private String buildDeviceInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("android", Build.VERSION.RELEASE);
            o.put("sdk", Build.VERSION.SDK_INT);
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            o.put("battery", bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
            o.put("network", currentNetworkType());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private String currentNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "offline";
            Network network = cm.getActiveNetwork();
            if (network == null) return "offline";
            NetworkCapabilities c = cm.getNetworkCapabilities(network);
            if (c == null) return "offline";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            return "other";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class NativeBridge {
        @JavascriptInterface public void closeApp() { runOnUiThread(MainActivity.this::closeApp); }
        @JavascriptInterface public void setBrightness(double value) { setWindowBrightness((float) value); }
        @JavascriptInterface public void vibrate(String pattern) { performVibration(pattern); }
        @JavascriptInterface public void pulseTorch(int durationMs) { runOnUiThread(() -> pulseTorchNative(durationMs)); }
        @JavascriptInterface public void tone(int frequencyIgnored, int durationMs) { runOnUiThread(() -> toneNative(durationMs)); }
        @JavascriptInterface public String getDeviceInfo() { return buildDeviceInfo(); }
        @JavascriptInterface public void stage(String name) { if (!"hardreset".equals(name)) setWindowBrightness(-1f); }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) scheduleRehide();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleRehide();
        if (webView != null) webView.onResume();
        if (sensorManager != null) {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        setWindowBrightness(-1f);
        if (pendingPermissionRequest != null) {
            pendingPermissionRequest.deny();
            pendingPermissionRequest = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("NativeBridge");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else closeApp();
    }
}
