package pl.siedlar.nexusprank;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

public class NexusApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final long WATCHDOG_MS = 25L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Activity activeActivity;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            Activity activity = activeActivity;
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (systemBarsVisible(activity)) hideSystemBars(activity);
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    private boolean systemBarsVisible(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            return insets == null || insets.isVisible(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        }
        int ui = activity.getWindow().getDecorView().getSystemUiVisibility();
        int required = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        return (ui & required) != required;
    }

    private void hideSystemBars(Activity activity) {
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        }
    }

    private void hideBurst(Activity activity) {
        hideSystemBars(activity);
        main.postDelayed(() -> { if (activeActivity == activity) hideSystemBars(activity); }, 16L);
        main.postDelayed(() -> { if (activeActivity == activity) hideSystemBars(activity); }, 40L);
        main.postDelayed(() -> { if (activeActivity == activity) hideSystemBars(activity); }, 80L);
    }

    private void attachVisibilityGuard(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            if (activeActivity != activity) return;
            hideBurst(activity);
        });
    }

    @Override public void onActivityResumed(Activity activity) {
        activeActivity = activity;
        attachVisibilityGuard(activity);
        main.removeCallbacks(watchdog);
        hideBurst(activity);
        main.post(watchdog);
    }

    @Override public void onActivityPaused(Activity activity) {
        if (activeActivity == activity) {
            activeActivity = null;
            main.removeCallbacks(watchdog);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { hideBurst(activity); }
    @Override public void onActivityStarted(Activity activity) { hideBurst(activity); }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    @Override public void onActivityDestroyed(Activity activity) {
        if (activeActivity == activity) {
            activeActivity = null;
            main.removeCallbacks(watchdog);
        }
    }
}
