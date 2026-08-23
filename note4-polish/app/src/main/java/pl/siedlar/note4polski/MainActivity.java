package pl.siedlar.note4polski;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String CHANGE_CONFIGURATION = "android.permission.CHANGE_CONFIGURATION";
    private TextView statusView;
    private TextView deviceView;
    private TextView commandView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshDiagnostics();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 246, 248));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(26));
        scroll.addView(root);

        TextView title = text("Note 4 Polski", 28, Color.rgb(20, 24, 31));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title, fullWidth());

        TextView subtitle = text("Bezpieczne włączenie polskiego menu (pl-PL)", 15, Color.rgb(78, 86, 98));
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(6), 0, dp(22));
        root.addView(subtitle, fullWidth());

        deviceView = text("", 14, Color.rgb(45, 52, 63));
        deviceView.setPadding(dp(14), dp(14), dp(14), dp(14));
        deviceView.setBackgroundColor(Color.WHITE);
        root.addView(deviceView, fullWidth());

        statusView = text("", 16, Color.rgb(25, 78, 47));
        statusView.setPadding(dp(14), dp(18), dp(14), dp(18));
        root.addView(statusView, fullWidth());

        Button enableButton = button("WŁĄCZ POLSKI JĘZYK");
        enableButton.setOnClickListener(v -> enablePolish());
        root.addView(enableButton, spacedButton());

        TextView info = text(
                "Jeżeli telefon nie ma roota, Android wymaga jednorazowego nadania aplikacji uprawnienia przez ADB. " +
                "Po nadaniu uprawnienia wróć tutaj i naciśnij przycisk ponownie.",
                14, Color.rgb(65, 72, 82));
        info.setPadding(0, dp(20), 0, dp(10));
        root.addView(info, fullWidth());

        commandView = text(adbCommand(), 13, Color.rgb(20, 24, 31));
        commandView.setPadding(dp(14), dp(14), dp(14), dp(14));
        commandView.setTextIsSelectable(true);
        commandView.setBackgroundColor(Color.rgb(226, 229, 234));
        root.addView(commandView, fullWidth());

        Button copyButton = button("KOPIUJ KOMENDĘ ADB");
        copyButton.setOnClickListener(v -> copyAdbCommand());
        root.addView(copyButton, spacedButton());

        Button devButton = button("OTWÓRZ OPCJE PROGRAMISTY");
        devButton.setOnClickListener(v -> openDeveloperOptions());
        root.addView(devButton, spacedButton());

        TextView warning = text(
                "Ważne: ta aplikacja nie flashuje ROM-u. Jeśli firmware Samsunga nie zawiera polskich tłumaczeń, " +
                "może zostać spolszczona tylko część interfejsu. Pełne menu wymaga wtedy polskiego firmware dokładnie dla modelu urządzenia.",
                13, Color.rgb(128, 68, 20));
        warning.setPadding(0, dp(22), 0, 0);
        root.addView(warning, fullWidth());

        setContentView(scroll);
    }

    private void refreshDiagnostics() {
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        String android = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        String locale = currentLocaleTag();
        boolean note4 = Build.MODEL != null && Build.MODEL.toUpperCase(Locale.US).startsWith("SM-N910");

        deviceView.setText(
                "Urządzenie: " + model + "\n" +
                "Android: " + android + "\n" +
                "Język systemu: " + locale + "\n" +
                "Galaxy Note 4: " + (note4 ? "wykryty" : "model nierozpoznany jako SM-N910x"));

        if (isPolishActive()) {
            statusView.setText("STATUS: Polski język jest aktywny.");
        } else if (hasChangeConfigurationPermission()) {
            statusView.setText("STATUS: Uprawnienie jest gotowe. Można włączyć polski język.");
        } else {
            statusView.setText("STATUS: Brak uprawnienia systemowego. Aplikacja spróbuje roota, a jeśli go nie ma — potrzebna jest jednorazowa komenda ADB.");
        }
    }

    private void enablePolish() {
        try {
            if (!hasChangeConfigurationPermission()) {
                tryRootGrant();
            }

            if (!hasChangeConfigurationPermission()) {
                statusView.setText("NIE MOŻNA JESZCZE ZMIENIĆ JĘZYKA. Nadaj uprawnienie ADB komendą poniżej, potem wróć i naciśnij przycisk ponownie.");
                return;
            }

            boolean changed = applyPolishLocale();
            if (changed) {
                Toast.makeText(this, "Ustawiono język polski. Jeżeli część menu pozostanie obca, ROM nie zawiera kompletu polskich tłumaczeń.", Toast.LENGTH_LONG).show();
                statusView.setText("GOTOWE: wysłano do systemu ustawienie pl-PL.");
                refreshDiagnostics();
            } else {
                statusView.setText("Nie udało się potwierdzić zmiany języka. Sprawdź firmware urządzenia.");
            }
        } catch (Throwable error) {
            statusView.setText("Błąd: " + error.getClass().getSimpleName() + ": " + safeMessage(error));
        }
    }

    private boolean hasChangeConfigurationPermission() {
        return checkCallingOrSelfPermission(CHANGE_CONFIGURATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void tryRootGrant() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "pm grant " + getPackageName() + " " + CHANGE_CONFIGURATION
            });
            process.waitFor();
        } catch (Throwable ignored) {
            // Brak roota jest normalnym scenariuszem. Wtedy użytkownik używa ADB.
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private boolean applyPolishLocale() throws Exception {
        if (Build.VERSION.SDK_INT >= 28) {
            throw new UnsupportedOperationException("Ta metoda jest przeznaczona dla Androida 5/6 używanego w Galaxy Note 4.");
        }

        Locale polish = new Locale("pl", "PL");
        Class<?> activityManagerNative = Class.forName("android.app.ActivityManagerNative");
        Method getDefault = activityManagerNative.getDeclaredMethod("getDefault");
        getDefault.setAccessible(true);
        Object activityManager = getDefault.invoke(null);

        Method getConfiguration = activityManager.getClass().getMethod("getConfiguration");
        Configuration config = (Configuration) getConfiguration.invoke(activityManager);
        config.locale = polish;
        config.setLayoutDirection(polish);

        try {
            Field userSetLocale = Configuration.class.getDeclaredField("userSetLocale");
            userSetLocale.setAccessible(true);
            userSetLocale.setBoolean(config, true);
        } catch (Throwable ignored) {
            // Pole jest ukryte i może różnić się między buildami Samsunga.
        }

        Method updateConfiguration = activityManager.getClass().getMethod("updateConfiguration", Configuration.class);
        updateConfiguration.invoke(activityManager, config);
        Locale.setDefault(polish);
        return true;
    }

    private boolean isPolishActive() {
        return "pl".equalsIgnoreCase(currentLocale().getLanguage());
    }

    private Locale currentLocale() {
        Configuration c = getResources().getConfiguration();
        if (Build.VERSION.SDK_INT >= 24) {
            return c.getLocales().get(0);
        }
        return c.locale;
    }

    private String currentLocaleTag() {
        Locale l = currentLocale();
        String country = l.getCountry();
        return l.getLanguage() + (country == null || country.isEmpty() ? "" : "-" + country);
    }

    private String adbCommand() {
        return "adb shell pm grant " + getPackageName() + " " + CHANGE_CONFIGURATION;
    }

    private void copyAdbCommand() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB", adbCommand()));
        Toast.makeText(this, "Komenda ADB skopiowana", Toast.LENGTH_SHORT).show();
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null ? "brak szczegółów" : message;
    }

    private TextView text(String value, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(0f, 1.15f);
        return tv;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinHeight(dp(52));
        return b;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams spacedButton() {
        LinearLayout.LayoutParams p = fullWidth();
        p.topMargin = dp(12);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
