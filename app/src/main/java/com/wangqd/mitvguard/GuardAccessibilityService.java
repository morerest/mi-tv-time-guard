package com.wangqd.mitvguard;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * 系统级无障碍守护服务：
 * 1. 实现小米电视 100% 断电开机自启与后台防杀保活；
 * 2. 提供最高优先级 TYPE_ACCESSIBILITY_OVERLAY 悬浮窗上下文；
 * 3. 提供原生息屏指令 (GLOBAL_ACTION_LOCK_SCREEN)；
 * 4. 防止小朋友进入电视【系统设置】卸载或强行停止守护程序。
 */
public class GuardAccessibilityService extends AccessibilityService {
    private static volatile GuardAccessibilityService instance;
    private GuardConfig config;
    private long lastToastTime = 0;

    public static GuardAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        config = new GuardConfig(this);
        BootReceiver.startGuardServiceSafely(this);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        BootReceiver.startGuardServiceSafely(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        // 确保主计时服务始终存活
        if (GuardService.getInstance() == null) {
            BootReceiver.startGuardServiceSafely(this);
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString().toLowerCase();

        // 防熊孩子进入系统设置或卸载界面（仅在儿童守护激活期间拦截，不影响大人平时使用）
        if (config != null
                && config.isChildGuardActiveRightNow()
                && config.isProtectSettings()
                && !GuardConfig.isTempSettingsAccessAllowed()) {
            boolean isSettingsOrInstaller = pkg.contains("tv.settings")
                    || pkg.contains("mitv.settings")
                    || pkg.equals("com.android.settings")
                    || pkg.contains("packageinstaller");
            if (isSettingsOrInstaller) {
                performGlobalAction(GLOBAL_ACTION_HOME);
                long now = System.currentTimeMillis();
                if (now - lastToastTime > 3500) {
                    lastToastTime = now;
                    Toast.makeText(this,
                            "🔒 电视设置受家长保护中\n请在电脑管理页关闭守护后再进入设置",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * 触发电视原生息屏（Android 9.0+ 支持直接通过无障碍服务锁屏息屏）
     */
    public boolean tryLockScreenOff() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
