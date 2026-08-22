package pl.siedlar.nexusprank;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

public class GuardActivity extends MainActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable rehideNow = this::hideBarsNow;
    private final Runnable rehideLate = this::hideBarsNow;
    private Object backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        installSystemUiGuard();
        hideBarsNow();

        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = BackApi33.register(this, () -> {
                hideBarsNow();
                scheduleRehide();
            });
        }
    }

    private void installSystemUiGuard() {
        View decor = getWindow().getDecorView();

        decor.setOnSystemUiVisibilityChangeListener(visibility -> scheduleRehide());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.setOnApplyWindowInsetsListener((view, insets) -> {
                if (insets.isVisible(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars())) {
                    scheduleRehide();
                }
                return insets;
            });
        }
    }

    private void scheduleRehide() {
        ui.removeCallbacks(rehideNow);
        ui.removeCallbacks(rehideLate);
        ui.postDelayed(rehideNow, 24L);
        ui.postDelayed(rehideLate, 220L);
    }

    private void hideBarsNow() {
        if (isFinishing() || isDestroyed()) return;

        View decor = getWindow().getDecorView();
        int flags =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decor.setSystemUiVisibility(flags);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    @Override
    public void onBackPressed() {
        hideBarsNow();
        scheduleRehide();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideBarsNow();
            scheduleRehide();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBarsNow();
        scheduleRehide();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            BackApi33.unregister(this, backCallback);
            backCallback = null;
        }
        super.onDestroy();
    }

    private static final class BackApi33 {
        static Object register(GuardActivity activity, Runnable action) {
            android.window.OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            );
            return callback;
        }

        static void unregister(GuardActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                (android.window.OnBackInvokedCallback) callback
            );
        }
    }
}
