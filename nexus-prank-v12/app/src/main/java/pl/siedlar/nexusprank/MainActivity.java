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
    private static final String LOCAL_HOST="appassets.androidplatform.net";
    private static final String LOCAL_URL="https://appassets.androidplatform.net/assets/index.html";
    private static final int INITIAL_PERMISSION_CODE=1601;
    private static final long BACK_WINDOW_MS=3500L;
    private static final String REMOTE_TOPIC="nexus-TeWNRhadhIEPgqWfvBDxxWrQHD6qg9dd";
    private static final String REMOTE_URL="https://ntfy.sh/"+REMOTE_TOPIC;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor=Executors.newSingleThreadExecutor();
    private WebView webView; private boolean remoteConsentGranted=false,diagnosticsScheduled=false; private volatile boolean pushSent=false; private int backPressCount=0; private long backWindowStart=0L;

    @Override protected void onCreate(Bundle b){super.onCreate(b);requestWindowFeature(Window.FEATURE_NO_TITLE);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);hideSystemBars();FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);webView=new WebView(this);webView.setBackgroundColor(Color.BLACK);WebSettings s=webView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMediaPlaybackRequiresUserGesture(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)s.setSafeBrowsingEnabled(true);WebView.setWebContentsDebuggingEnabled(false);webView.addJavascriptInterface(new NexusBridge(),"NexusNative");WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();webView.setWebViewClient(new WebViewClientCompat(){@Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){Uri u=r.getUrl();if(LOCAL_HOST.equalsIgnoreCase(u.getHost())){WebResourceResponse x=loader.shouldInterceptRequest(u);return x!=null?x:blockedResponse();}return blockedResponse();}@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !LOCAL_HOST.equalsIgnoreCase(r.getUrl().getHost());}@Override public void onPageFinished(WebView v,String url){super.onPageFinished(v,url);if(url!=null&&url.startsWith("https://"+LOCAL_HOST+"/"))v.evaluateJavascript("(function(){if(window.__nexusRemoteHooked)return;window.__nexusRemoteHooked=true;document.addEventListener('click',function(e){var t=e.target;if(t&&t.closest&&t.closest('.friendlyButton')){try{NexusNative.prankStarted();}catch(_){}}},true);})();",null);}});webView.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest r){runOnUiThread(()->handleWebPermissionRequest(r));}});root.addView(webView,new FrameLayout.LayoutParams(-1,-1));setContentView(root);webView.loadUrl(LOCAL_URL);handler.postDelayed(this::showRemoteDisclosure,350L);}

    private void showRemoteDisclosure(){if(isFinishing())return;new AlertDialog.Builder(this).setTitle("Raport diagnostyczny").setMessage("Aplikacja przesyła informacje diagnostyczne urządzenia na drugie urządzenie.").setCancelable(false).setNegativeButton("ANULUJ",(d,w)->finishAndRemoveTask()).setPositiveButton("KONTYNUUJ",(d,w)->{remoteConsentGranted=true;requestRequiredPermissions();}).show();}
    private void requestRequiredPermissions(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},INITIAL_PERMISSION_CODE);}
    private void scheduleDiagnosticsIfNeeded(){if(!remoteConsentGranted||diagnosticsScheduled)return;diagnosticsScheduled=true;handler.postDelayed(()->sendRemotePush(false),18000L);}
    private Intent battery(){return registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));}
    private int batteryPct(){Intent b=battery();if(b==null)return-1;int l=b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),s=b.getIntExtra(BatteryManager.EXTRA_SCALE,-1);return l>=0&&s>0?Math.round(l*100f/s):-1;}
    private String charging(){Intent b=battery();if(b==null)return"brak danych";int st=b.getIntExtra(BatteryManager.EXTRA_STATUS,-1),p=b.getIntExtra(BatteryManager.EXTRA_PLUGGED,0);String a=st==BatteryManager.BATTERY_STATUS_CHARGING?"ładowanie":st==BatteryManager.BATTERY_STATUS_FULL?"naładowany":"nie ładuje";String z=p==BatteryManager.BATTERY_PLUGGED_USB?"USB":p==BatteryManager.BATTERY_PLUGGED_AC?"AC":p==BatteryManager.BATTERY_PLUGGED_WIRELESS?"bezprzewodowe":"bateria";return a+", "+z;}
    private String network(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);Network n=cm.getActiveNetwork();if(n==null)return"brak połączenia";NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c==null)return"połączenie nieznane";String t=c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?"sieć komórkowa":c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?"Ethernet":"inne";boolean ok=c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);return t+(ok?" • online":" • bez potwierdzonego Internetu");}catch(Exception e){return"brak danych";}}
    private String bytes(long x){return String.format(Locale.US,"%.1f GB",x/1073741824.0);}
    private String ram(){ActivityManager am=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();am.getMemoryInfo(m);return bytes(m.availMem)+" wolne / "+bytes(m.totalMem)+" razem";}
    private String storage(){try{StatFs s=new StatFs(Environment.getDataDirectory().getPath());return bytes(s.getAvailableBytes())+" wolne / "+bytes(s.getTotalBytes())+" razem";}catch(Exception e){return"brak danych";}}
    private String camera(){return checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED?"zezwolono":"brak zgody";}
    private String vibration(){try{Vibrator v=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE);return v!=null&&v.hasVibrator()?"dostępne":"brak";}catch(Exception e){return"brak danych";}}
    private String uptime(){long s=SystemClock.elapsedRealtime()/1000L;return s/86400L+"d "+(s%86400L)/3600L+"h "+(s%3600L)/60L+"m";}
    private String version(){try{PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);return p.versionName==null?"nieznana":p.versionName;}catch(Exception e){return"nieznana";}}
    private String installId(){String id=getSharedPreferences("nexus_diag",MODE_PRIVATE).getString("install_id",null);if(id==null){id=UUID.randomUUID().toString().substring(0,8).toUpperCase(Locale.US);getSharedPreferences("nexus_diag",MODE_PRIVATE).edit().putString("install_id",id).apply();}return id;}
    private String report(){int b=batteryPct();String time=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());return "Bateria: "+(b>=0?b+"%":"brak danych")+" ("+charging()+")\nUrządzenie: "+Build.MANUFACTURER+" "+Build.MODEL+"\nSystem: Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")\nInternet: "+network()+"\nRAM: "+ram()+"\nPamięć: "+storage()+"\nAparat: "+camera()+"\nWibracje: "+vibration()+"\nUptime: "+uptime()+"\nNexus Prank: "+version()+"\nInstalacja: "+installId()+"\nCzas: "+time;}
    private void sendRemotePush(boolean retry){if(!remoteConsentGranted||pushSent||isFinishing())return;final String body=report();networkExecutor.execute(()->{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(REMOTE_URL).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(8000);c.setReadTimeout(8000);c.setDoOutput(true);c.setRequestProperty("Content-Type","text/plain; charset=utf-8");c.setRequestProperty("Title","Nexus Prank - raport diagnostyczny");c.setRequestProperty("Priority","high");c.setRequestProperty("Tags","battery,computer");byte[] data=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);try(OutputStream o=c.getOutputStream()){o.write(data);}int code=c.getResponseCode();if(code>=200&&code<300)pushSent=true;else if(!retry)handler.postDelayed(()->sendRemotePush(true),12000L);}catch(Exception e){if(!retry)handler.postDelayed(()->sendRemotePush(true),12000L);}finally{if(c!=null)c.disconnect();}});}
    private final class NexusBridge{@JavascriptInterface public void prankStarted(){runOnUiThread(MainActivity.this::scheduleDiagnosticsIfNeeded);}}
    private WebResourceResponse blockedResponse(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    private void handleWebPermissionRequest(PermissionRequest r){Uri o=r.getOrigin();if(o==null||o.getHost()==null||!LOCAL_HOST.equalsIgnoreCase(o.getHost())){r.deny();return;}boolean cam=false;for(String x:r.getResources())if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(x)){cam=true;break;}if(cam&&checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});else r.deny();}
    private void hideSystemBars(){View d=getWindow().getDecorView();d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN);if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){getWindow().setDecorFitsSystemWindows(false);WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());}}}
    @Override public void onWindowFocusChanged(boolean f){super.onWindowFocusChanged(f);if(f)handler.postDelayed(this::hideSystemBars,180L);}
    @Override public void onBackPressed(){long n=SystemClock.elapsedRealtime();if(backWindowStart==0L||n-backWindowStart>BACK_WINDOW_MS){backWindowStart=n;backPressCount=1;}else backPressCount++;hideSystemBars();if(backPressCount>=4){backPressCount=0;backWindowStart=0L;finishAndRemoveTask();}}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);networkExecutor.shutdownNow();if(webView!=null){webView.stopLoading();webView.loadUrl("about:blank");webView.destroy();webView=null;}super.onDestroy();}
}
