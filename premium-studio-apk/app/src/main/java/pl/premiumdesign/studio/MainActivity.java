package pl.premiumdesign.studio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
 private static final String HOME="https://premium-design-studio-snm7kd.v2.appdeploy.ai/";
 private static final String HOST="premium-design-studio-snm7kd.v2.appdeploy.ai";
 private static final int PICK=4102;
 private WebView web; private ProgressBar progress; private ValueCallback<Uri[]> fileCb; private Uri lastExport; private boolean immersive=false;
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(11,11,12));getWindow().setNavigationBarColor(Color.rgb(11,11,12));buildUi();configure();if(b==null)web.loadUrl(HOME);else web.restoreState(b);}
 private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
 private void buildUi(){FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(11,11,12));web=new WebView(this);web.setBackgroundColor(Color.rgb(11,11,12));root.addView(web,new FrameLayout.LayoutParams(-1,-1));progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,dp(2));pp.gravity=Gravity.TOP;root.addView(progress,pp);TextView menu=new TextView(this);menu.setText("⋮");menu.setTextSize(26);menu.setTextColor(Color.rgb(240,235,226));menu.setGravity(Gravity.CENTER);GradientDrawable g=new GradientDrawable();g.setColor(Color.argb(205,18,18,19));g.setStroke(dp(1),Color.argb(100,215,181,142));g.setCornerRadius(dp(22));menu.setBackground(g);menu.setOnClickListener(this::showMenu);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(44),dp(44));mp.gravity=Gravity.TOP|Gravity.END;mp.topMargin=dp(10);mp.rightMargin=dp(10);root.addView(menu,mp);setContentView(root);}
 @SuppressLint({"SetJavaScriptEnabled","JavascriptInterface"}) private void configure(){WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setCacheMode(online()?WebSettings.LOAD_DEFAULT:WebSettings.LOAD_CACHE_ELSE_NETWORK);s.setAllowFileAccess(false);s.setAllowContentAccess(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportZoom(false);s.setMediaPlaybackRequiresUserGesture(false);s.setSafeBrowsingEnabled(true);s.setUserAgentString(s.getUserAgentString()+" PremiumDesignStudioAndroid/1.0");CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);web.addJavascriptInterface(new Bridge(),"AndroidBridge");web.setWebChromeClient(new WebChromeClient(){@Override public void onProgressChanged(WebView v,int p){progress.setProgress(p);progress.setVisibility(p>=100?View.GONE:View.VISIBLE);}@Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> cb,FileChooserParams params){if(fileCb!=null)fileCb.onReceiveValue(null);fileCb=cb;try{Intent i=params.createIntent();i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,PICK);return true;}catch(Exception e){fileCb=null;return false;}}});web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return route(r.getUrl());}@Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())Toast.makeText(MainActivity.this,"Brak połączenia — używam cache, jeśli jest dostępny.",Toast.LENGTH_LONG).show();}});}
 private boolean route(Uri u){if(u==null)return false;String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase();if("https".equals(scheme)&&HOST.equalsIgnoreCase(u.getHost()))return false;if("blob".equals(scheme)||"data".equals(scheme))return false;try{startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception e){Toast.makeText(this,"Nie można otworzyć linku.",Toast.LENGTH_SHORT).show();}return true;}
 private void showMenu(View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add(0,1,0,"Odśwież");p.getMenu().add(0,2,1,"Wstecz");p.getMenu().add(0,3,2,"Dalej");p.getMenu().add(0,4,3,"Strona główna");p.getMenu().add(0,5,4,"Udostępnij projekt");p.getMenu().add(0,6,5,"Udostępnij ostatni eksport");p.getMenu().add(0,7,6,immersive?"Wyłącz pełny ekran":"Pełny ekran");p.getMenu().add(0,8,7,"Otwórz w przeglądarce");p.getMenu().add(0,9,8,"Wyczyść cache");p.getMenu().add(0,10,9,"Informacje");p.setOnMenuItemClickListener(x->{switch(x.getItemId()){case 1:web.reload();return true;case 2:if(web.canGoBack())web.goBack();return true;case 3:if(web.canGoForward())web.goForward();return true;case 4:web.loadUrl(HOME);return true;case 5:shareUrl();return true;case 6:shareExport();return true;case 7:toggleFull();return true;case 8:startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(web.getUrl()==null?HOME:web.getUrl())));return true;case 9:web.clearCache(true);Toast.makeText(this,"Cache wyczyszczony.",Toast.LENGTH_SHORT).show();return true;case 10:new AlertDialog.Builder(this).setTitle("Premium Design Studio").setMessage("Android 1.0\nAI Art Director · Color Studio · Premium Design DNA\nEksport HTML · upload · share · cache/offline · fullscreen").setPositiveButton("OK",null).show();return true;}return false;});p.show();}
 private void shareUrl(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_SUBJECT,"Premium Design Studio");i.putExtra(Intent.EXTRA_TEXT,web.getUrl()==null?HOME:web.getUrl());startActivity(Intent.createChooser(i,"Udostępnij"));}
 private void shareExport(){if(lastExport==null){Toast.makeText(this,"Najpierw wykonaj Eksport HTML.",Toast.LENGTH_SHORT).show();return;}Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/html");i.putExtra(Intent.EXTRA_STREAM,lastExport);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"Udostępnij eksport"));}
 private void toggleFull(){immersive=!immersive;WindowInsetsController c=getWindow().getInsetsController();if(c!=null){if(immersive){c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);}else c.show(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());}}
 private boolean online(){ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);Network n=cm==null?null:cm.getActiveNetwork();NetworkCapabilities c=n==null?null:cm.getNetworkCapabilities(n);return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}
 @Override protected void onResume(){super.onResume();if(web!=null)web.getSettings().setCacheMode(online()?WebSettings.LOAD_DEFAULT:WebSettings.LOAD_CACHE_ELSE_NETWORK);}
 @Override protected void onSaveInstanceState(Bundle b){web.saveState(b);super.onSaveInstanceState(b);}
 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==PICK&&fileCb!=null){Uri[] out=null;if(result==RESULT_OK&&data!=null&&data.getClipData()!=null){ClipData c=data.getClipData();out=new Uri[c.getItemCount()];for(int i=0;i<c.getItemCount();i++)out[i]=c.getItemAt(i).getUri();}else if(result==RESULT_OK)out=WebChromeClient.FileChooserParams.parseResult(result,data);fileCb.onReceiveValue(out);fileCb=null;}}
 @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("AndroidBridge");web.stopLoading();web.destroy();}super.onDestroy();}
 public final class Bridge{@JavascriptInterface public void saveTextFile(String filename,String text){String safe=(filename==null||filename.isBlank()?"premium-site.html":filename).replaceAll("[^a-zA-Z0-9._-]","-");try{ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,safe);v.put(MediaStore.Downloads.MIME_TYPE,"text/html");v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/PremiumDesignStudio");Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);if(uri==null)throw new IllegalStateException();try(OutputStream o=getContentResolver().openOutputStream(uri)){if(o==null)throw new IllegalStateException();o.write(text.getBytes(StandardCharsets.UTF_8));}lastExport=uri;runOnUiThread(()->Toast.makeText(MainActivity.this,"Eksport zapisany w Downloads/PremiumDesignStudio.",Toast.LENGTH_LONG).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(MainActivity.this,"Nie udało się zapisać eksportu.",Toast.LENGTH_LONG).show());}}@JavascriptInterface public boolean isNativeApp(){return true;}@JavascriptInterface public void shareCurrent(){runOnUiThread(MainActivity.this::shareUrl);}}
}
