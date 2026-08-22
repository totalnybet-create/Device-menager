package pl.devicemanager.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "device_manager_android";
    private static final String PREF_SERVER = "server_base_url";

    private SharedPreferences preferences;
    private WebView webView;
    private TextView serverLabel;
    private String baseUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        configureWebView();

        baseUrl = preferences.getString(PREF_SERVER, null);
        if (baseUrl == null || baseUrl.isBlank()) {
            showServerDialog(true);
        } else {
            loadPanel();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(8), dp(8));
        toolbar.setBackgroundColor(Color.rgb(245, 245, 245));

        serverLabel = new TextView(this);
        serverLabel.setText("Device Manager");
        serverLabel.setSingleLine(true);
        serverLabel.setTextSize(14f);
        serverLabel.setTextColor(Color.rgb(32, 32, 32));
        toolbar.addView(serverLabel, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button reload = new Button(this);
        reload.setText("↻");
        reload.setContentDescription("Odśwież panel");
        reload.setOnClickListener(v -> {
            if (baseUrl != null) {
                webView.reload();
            }
        });
        toolbar.addView(reload, new LinearLayout.LayoutParams(dp(56), dp(48)));

        Button settings = new Button(this);
        settings.setText("⚙");
        settings.setContentDescription("Ustaw adres serwera");
        settings.setOnClickListener(v -> showServerDialog(false));
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(56), dp(48)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);

        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        setContentView(root);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        WebView.setWebContentsDebuggingEnabled(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (isAllowedOrigin(target)) {
                    return false;
                }
                Toast.makeText(
                        MainActivity.this,
                        "Zablokowano przejście poza skonfigurowany serwer.",
                        Toast.LENGTH_SHORT
                ).show();
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(
                        MainActivity.this,
                        "Błąd certyfikatu TLS. Połączenie zostało zablokowane.",
                        Toast.LENGTH_LONG
                ).show();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request.isForMainFrame()) {
                    Toast.makeText(
                            MainActivity.this,
                            "Nie można połączyć się z serwerem Device Manager.",
                            Toast.LENGTH_LONG
                    ).show();
                }
            }
        });
    }

    private void showServerDialog(boolean required) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), 0);

        TextView hint = new TextView(this);
        hint.setText("Podaj adres serwera Device Manager. Używaj HTTPS. HTTP jest dozwolone tylko dla localhost i prywatnej sieci LAN.");
        hint.setTextSize(14f);
        content.addView(hint);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint("https://device-manager.example.pl");
        if (baseUrl != null) {
            input.setText(baseUrl);
            input.setSelection(input.length());
        }
        content.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Adres serwera")
                .setView(content)
                .setPositiveButton("Zapisz", null)
                .setNegativeButton(required ? null : "Anuluj", null)
                .create();
        dialog.setCancelable(!required);
        dialog.setCanceledOnTouchOutside(!required);
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String normalized = normalizeServerUrl(input.getText().toString());
                    if (normalized == null) {
                        input.setError("Podaj poprawny adres HTTPS albo lokalny adres HTTP.");
                        return;
                    }
                    baseUrl = normalized;
                    preferences.edit().putString(PREF_SERVER, normalized).apply();
                    dialog.dismiss();
                    loadPanel();
                }));
        dialog.show();
    }

    private void loadPanel() {
        if (baseUrl == null) {
            return;
        }
        Uri uri = Uri.parse(baseUrl);
        String host = uri.getHost() == null ? baseUrl : uri.getHost();
        serverLabel.setText("Device Manager · " + host);
        webView.loadUrl(baseUrl + "/panel");
    }

    private String normalizeServerUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }

        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            return null;
        }

        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return null;
        }
        if (scheme.equals("http") && !isPrivateOrLocalHost(host)) {
            return null;
        }

        String authority = uri.getEncodedAuthority();
        if (authority == null || authority.isBlank()) {
            return null;
        }
        return scheme + "://" + authority;
    }

    private boolean isAllowedOrigin(Uri target) {
        if (baseUrl == null || target == null) {
            return false;
        }
        Uri configured = Uri.parse(baseUrl);
        return equalsIgnoreCase(configured.getScheme(), target.getScheme())
                && equalsIgnoreCase(configured.getHost(), target.getHost())
                && effectivePort(configured) == effectivePort(target);
    }

    private int effectivePort(Uri uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private boolean isPrivateOrLocalHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".local") || lower.equals("::1")) {
            return true;
        }

        String[] parts = lower.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException ignored) {
            return false;
        }

        return octets[0] == 10
                || octets[0] == 127
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 169 && octets[1] == 254);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
