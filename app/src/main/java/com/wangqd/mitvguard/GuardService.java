package com.wangqd.mitvguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 核心常驻服务：
 * 1. 每秒检测电视亮屏状态，精准统计今日观看时长（息屏自动暂停计时）；
 * 2. 剩余 5 分钟与最后 60 秒右上角温和胶囊提醒；
 * 3. 到点自动暂停视频、静音、弹出全屏护眼锁屏，倒计时后自动息屏/全黑休眠；
 * 4. 支持遥控器隐藏按键序列（上上下下左右）唤出家长应急解锁菜单；
 * 5. 托管内置 8899 端口电脑端 Web 管理后台。
 */
public class GuardService extends Service {
    private static final String CHANNEL_ID = "mi_tv_guard_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PARENT_KEY_SEQUENCE = "UUDDLR"; // 上上下下左右

    private static volatile GuardService instance;

    private GuardConfig config;
    private Handler mainHandler;
    private WebServer webServer;

    // 锁屏全屏悬浮窗
    private WindowManager lockWindowManager;
    private FrameLayout lockOverlayView;
    private LinearLayout lockContentBox;
    private TextView lockTitleView;
    private TextView lockMsgView;
    private TextView lockStatsView;
    private TextView lockCountdownView;
    private TextView lockFooterView;
    private LinearLayout parentSecretPanel;
    private boolean isLockOverlayShowing = false;
    private boolean isBlackoutSleepMode = false;
    private int lockOverlayElapsedSeconds = 0;

    // 右上角温和提醒悬浮胶囊
    private WindowManager toastWindowManager;
    private TextView warningBannerView;
    private boolean isWarningBannerShowing = false;
    private int fiveMinBannerHoldSeconds = 0;

    // 媒体静音前保存的原始音量
    private int savedMusicVolume = -1;
    private final StringBuilder keySeqBuffer = new StringBuilder();

    public static GuardService getInstance() {
        return instance;
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                onSecondTick();
            } catch (Exception ignored) {
            }
            mainHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                lockOverlayElapsedSeconds = 0;
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                lockOverlayElapsedSeconds = 0;
                if (isBlackoutSleepMode) {
                    exitBlackoutSleepMode();
                }
                mainHandler.post(GuardService.this::onSecondTick);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        config = new GuardConfig(this);
        mainHandler = new Handler(Looper.getMainLooper());

        startForegroundWithNotification();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);

        startWebServer();
        mainHandler.post(tickRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithNotification();
        startWebServer();
        return START_STICKY;
    }

    private void startWebServer() {
        if (webServer == null) {
            webServer = new WebServer(this, config, GuardConfig.WEB_PORT);
            webServer.startServer();
        }
    }

    /**
     * 每秒核心调度逻辑
     */
    private void onSecondTick() {
        config.checkAndRolloverDay();

        boolean screenInteractive = isTvScreenInteractive();
        if (!screenInteractive) {
            // 电视处于息屏待机状态，不扣减观看时长
            hideWarningBanner();
            return;
        }

        // 若守护总开关已关闭，或处于「今晚大人接管（次日自动恢复）」状态，完全放行且不计入儿童时长
        if (!config.isGuardEnabled() || config.isAdultOverrideToday()) {
            hideWarningBanner();
            if (isLockOverlayShowing) {
                hideLockOverlayAndRestoreAudio();
            }
            return;
        }

        // 若家长在电脑端点击了「立即锁屏停止电视」，无论当前几点均优先执行锁屏
        if (config.isForceLocked()) {
            hideWarningBanner();
            showOrUpdateLockOverlay("🛑 家长已暂停电视播放", config.getLockMessage());
            return;
        }

        boolean todayActive = config.isTodayActiveDay();
        boolean inWindow = config.isNowInActiveTimeWindow();

        // 若当前不在儿童管控时段（例如周一至周五 17:00 前，或非管控的周末）
        if (!todayActive || !inWindow) {
            hideWarningBanner();
            if (todayActive && GuardConfig.MODE_BLOCK.equals(config.getOutsideWindowMode())) {
                // 严管模式：在生效日但未到 17:00（或已过结束时间），直接锁屏防偷看
                String reasonMsg = String.format(Locale.CHINA,
                        "儿童允许观看时段为 %s ~ %s，其余时间请休息眼睛哦！",
                        config.getActiveStartTime(), config.getActiveEndTime());
                showOrUpdateLockOverlay("🌙 当前不在儿童看电视时段", reasonMsg);
            } else {
                // 默认自由模式 (MODE_FREE)：17:00 前或周末完全不影响大人使用电视，不计时、不锁屏
                if (isLockOverlayShowing) {
                    hideLockOverlayAndRestoreAudio();
                }
            }
            return;
        }

        // 当前处于儿童管控时段（如周一至周五 17:00 后）：检查今日剩余额度
        int remainingSec = config.getRemainingSecondsToday();
        if (remainingSec <= 0) {
            hideWarningBanner();
            showOrUpdateLockOverlay("🌿 今日看电视时间已用完", config.getLockMessage());
            return;
        }

        // 允许正常观看：若之前处于锁屏状态，立即解除锁屏并恢复声音
        if (isLockOverlayShowing) {
            hideLockOverlayAndRestoreAudio();
        }

        // 屏幕点亮且处于正常观看状态：累加 1 秒今日已看时长
        config.incrementUsedSecond();
        remainingSec = config.getRemainingSecondsToday();

        // 温和提醒逻辑：1) 剩余整 5 分钟时弹出 12 秒胶囊提示；2) 最后 60 秒常驻倒计时
        if (remainingSec == 300) {
            fiveMinBannerHoldSeconds = 12;
            playGentleBeep();
        }

        if (fiveMinBannerHoldSeconds > 0) {
            fiveMinBannerHoldSeconds--;
            showOrUpdateWarningBanner("⏰ 温馨提醒：今天还能看 5 分钟电视哦，准备休息啦~", "#1E4620");
        } else if (remainingSec <= 60 && remainingSec > 0) {
            showOrUpdateWarningBanner(
                    String.format(Locale.CHINA, "⏳ 即将结束：今日剩余时间还有 %d 秒", remainingSec),
                    "#7C2D12");
        } else {
            hideWarningBanner();
        }
    }

    /**
     * 判断小米电视屏幕是否处于点亮状态
     */
    public boolean isTvScreenInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    return pm.isInteractive();
                } else {
                    //noinspection deprecation
                    return pm.isScreenOn();
                }
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    public boolean isCurrentlyLocked() {
        return isLockOverlayShowing;
    }

    // ==================== 全屏护眼锁屏与自动息屏控制 ====================

    private void showOrUpdateLockOverlay(String title, String message) {
        pauseAndMuteMedia();

        if (!isLockOverlayShowing) {
            createAndAddLockOverlay();
            lockOverlayElapsedSeconds = 0;
            isBlackoutSleepMode = false;
        } else {
            lockOverlayElapsedSeconds++;
        }

        if (lockOverlayView == null) return;

        int usedSec = config.getUsedSecondsToday();
        int totalLimitMin = config.getTodayTotalLimitSeconds() / 60;
        String ip = GuardConfig.getLocalIpAddress();

        lockTitleView.setText(title);
        lockMsgView.setText(message);
        lockStatsView.setText(String.format(Locale.CHINA,
                "今日已观看  %s     ·     今日总额度  %d 分钟",
                GuardConfig.formatDurationChinese(usedSec), totalLimitMin));
        lockFooterView.setText(String.format(Locale.CHINA,
                "管理端 %s:%d", ip, GuardConfig.WEB_PORT));

        int autoOffSec = config.getAutoOffSeconds();
        if (autoOffSec > 0) {
            int remainToSleep = autoOffSec - lockOverlayElapsedSeconds;
            if (remainToSleep > 0) {
                if (isBlackoutSleepMode) {
                    exitBlackoutSleepMode();
                }
                lockCountdownView.setText(String.format(Locale.CHINA,
                        "💤 电视将在 %d 秒后自动黑屏休眠...", remainToSleep));
            } else {
                lockCountdownView.setText("💤 已进入休眠保护状态");
                if (remainToSleep == 0 || remainToSleep % 10 == 0) {
                    triggerTvScreenOffOrBlackout();
                }
            }
        } else {
            if (isBlackoutSleepMode) {
                exitBlackoutSleepMode();
            }
            lockCountdownView.setText("🔒 屏幕已锁定，请休息眼睛");
        }
    }

    private void createAndAddLockOverlay() {
        try {
            Context overlayCtx = getBestOverlayContext();
            lockWindowManager = (WindowManager) overlayCtx.getSystemService(Context.WINDOW_SERVICE);
            if (lockWindowManager == null) return;

            lockOverlayView = new FrameLayout(overlayCtx) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        onOverlayKeyDown(event.getKeyCode());
                    }
                    // 当家长隐藏菜单开启时，允许方向键和确认键在按钮间移动点击
                    if (parentSecretPanel != null && parentSecretPanel.getVisibility() == View.VISIBLE) {
                        int code = event.getKeyCode();
                        if (code == KeyEvent.KEYCODE_DPAD_LEFT
                                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                                || code == KeyEvent.KEYCODE_DPAD_UP
                                || code == KeyEvent.KEYCODE_DPAD_DOWN
                                || code == KeyEvent.KEYCODE_DPAD_CENTER
                                || code == KeyEvent.KEYCODE_ENTER) {
                            return super.dispatchKeyEvent(event);
                        }
                    }
                    // 拦截其余所有遥控器按键（防返回键退出）
                    return true;
                }
            };
            lockOverlayView.setBackgroundColor(Color.parseColor("#0B241A"));
            lockOverlayView.setFocusable(true);
            lockOverlayView.setFocusableInTouchMode(true);

            lockContentBox = new LinearLayout(overlayCtx);
            lockContentBox.setOrientation(LinearLayout.VERTICAL);
            lockContentBox.setGravity(Gravity.CENTER);
            int padH = dp(overlayCtx, 56);
            int padV = dp(overlayCtx, 44);
            lockContentBox.setPadding(padH, padV, padH, padV);
            GradientDrawable centerCardBg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.parseColor("#113325"), Color.parseColor("#0C261B")});
            centerCardBg.setCornerRadius(dp(overlayCtx, 28));
            centerCardBg.setStroke(dp(overlayCtx, 1), Color.parseColor("#1F5C43"));
            lockContentBox.setBackground(centerCardBg);

            // 顶部护眼胶囊小标签
            TextView eyeTag = new TextView(overlayCtx);
            eyeTag.setText("🌿  EYE CARE · 护眼休息时间");
            eyeTag.setTextColor(Color.parseColor("#34D399"));
            eyeTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            eyeTag.setTypeface(Typeface.DEFAULT_BOLD);
            eyeTag.setPadding(dp(overlayCtx, 16), dp(overlayCtx, 6), dp(overlayCtx, 16), dp(overlayCtx, 6));
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setColor(Color.parseColor("#064E3B"));
            tagBg.setCornerRadius(dp(overlayCtx, 999));
            tagBg.setStroke(dp(overlayCtx, 1), Color.parseColor("#10B981"));
            eyeTag.setBackground(tagBg);
            LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tagLp.bottomMargin = dp(overlayCtx, 18);
            lockContentBox.addView(eyeTag, tagLp);

            // 主标题
            lockTitleView = new TextView(overlayCtx);
            lockTitleView.setTextColor(Color.parseColor("#ECFDF5"));
            lockTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
            lockTitleView.setTypeface(Typeface.DEFAULT_BOLD);
            lockTitleView.setGravity(Gravity.CENTER);
            lockContentBox.addView(lockTitleView);

            // 提示语
            lockMsgView = new TextView(overlayCtx);
            lockMsgView.setTextColor(Color.parseColor("#A7F3D0"));
            lockMsgView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            lockMsgView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            msgLp.topMargin = dp(overlayCtx, 16);
            lockContentBox.addView(lockMsgView, msgLp);

            // 统计卡片
            lockStatsView = new TextView(overlayCtx);
            lockStatsView.setTextColor(Color.parseColor("#D1FAE5"));
            lockStatsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            lockStatsView.setGravity(Gravity.CENTER);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.parseColor("#091E16"));
            cardBg.setCornerRadius(dp(overlayCtx, 14));
            cardBg.setStroke(dp(overlayCtx, 1), Color.parseColor("#1E4D3B"));
            lockStatsView.setBackground(cardBg);
            lockStatsView.setPadding(dp(overlayCtx, 28), dp(overlayCtx, 12), dp(overlayCtx, 28), dp(overlayCtx, 12));
            LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            statsLp.topMargin = dp(overlayCtx, 26);
            lockContentBox.addView(lockStatsView, statsLp);

            // 自动休眠倒计时
            lockCountdownView = new TextView(overlayCtx);
            lockCountdownView.setTextColor(Color.parseColor("#6EE7B7"));
            lockCountdownView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            lockCountdownView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cdLp.topMargin = dp(overlayCtx, 20);
            lockContentBox.addView(lockCountdownView, cdLp);

            // 家长遥控器隐藏按键解锁面板（默认隐藏，按 上上下下左右 唤出）
            parentSecretPanel = buildParentSecretPanel(overlayCtx);
            LinearLayout.LayoutParams secretLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            secretLp.topMargin = dp(overlayCtx, 22);
            lockContentBox.addView(parentSecretPanel, secretLp);

            FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            centerLp.gravity = Gravity.CENTER;
            centerLp.leftMargin = dp(overlayCtx, 64);
            centerLp.rightMargin = dp(overlayCtx, 64);
            lockOverlayView.addView(lockContentBox, centerLp);

            // 底部角落极低调辅助文字
            lockFooterView = new TextView(overlayCtx);
            lockFooterView.setTextColor(Color.parseColor("#3B594C"));
            lockFooterView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            lockFooterView.setGravity(Gravity.END);
            FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            footerLp.gravity = Gravity.BOTTOM | Gravity.END;
            footerLp.bottomMargin = dp(overlayCtx, 18);
            footerLp.rightMargin = dp(overlayCtx, 28);
            lockOverlayView.addView(lockFooterView, footerLp);

            addViewWithFallbackWindowType(lockWindowManager, lockOverlayView, true);
            lockOverlayView.requestFocus();
            isLockOverlayShowing = true;
            keySeqBuffer.setLength(0);
        } catch (Exception e) {
            isLockOverlayShowing = false;
        }
    }

    private LinearLayout buildParentSecretPanel(Context ctx) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER);
        panel.setVisibility(View.GONE);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F2937"));
        bg.setCornerRadius(dp(ctx, 12));
        bg.setStroke(dp(ctx, 2), Color.parseColor("#F59E0B"));
        panel.setBackground(bg);
        int p = dp(ctx, 14);
        panel.setPadding(p, p, p, p);

        Button btnAdd15 = createTvActionButton(ctx, "+15分钟", v -> {
            config.addBonusMinutesToday(15);
            hideLockOverlayAndRestoreAudio();
            onSecondTick();
        });
        Button btnAdd30 = createTvActionButton(ctx, "+30分钟", v -> {
            config.addBonusMinutesToday(30);
            hideLockOverlayAndRestoreAudio();
            onSecondTick();
        });
        Button btnAdult = createTvActionButton(ctx, "🛋️ 今晚大人接管(明日自动恢复)", v -> {
            config.setAdultOverrideToday(true);
            hideLockOverlayAndRestoreAudio();
            onSecondTick();
        });
        Button btnHide = createTvActionButton(ctx, "收起菜单", v -> {
            panel.setVisibility(View.GONE);
            keySeqBuffer.setLength(0);
        });

        panel.addView(btnAdd15);
        panel.addView(btnAdd30);
        panel.addView(btnAdult);
        panel.addView(btnHide);
        return panel;
    }

    private Button createTvActionButton(Context ctx, String text, View.OnClickListener listener) {
        Button btn = new Button(ctx);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 8);
        lp.rightMargin = dp(ctx, 8);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void onOverlayKeyDown(int keyCode) {
        // 如果处于全黑省电休眠模式，按任意键短暂唤醒护眼绿锁屏提示 8 秒，随后再次全黑
        if (isBlackoutSleepMode) {
            exitBlackoutSleepMode();
            int autoOff = config.getAutoOffSeconds();
            lockOverlayElapsedSeconds = Math.max(0, autoOff - 8);
        }

        if (parentSecretPanel != null && parentSecretPanel.getVisibility() == View.VISIBLE) {
            return;
        }

        char c = 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) c = 'U';
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) c = 'D';
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) c = 'L';
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) c = 'R';
        else {
            keySeqBuffer.setLength(0);
            return;
        }

        keySeqBuffer.append(c);
        if (keySeqBuffer.length() > PARENT_KEY_SEQUENCE.length()) {
            keySeqBuffer.delete(0, keySeqBuffer.length() - PARENT_KEY_SEQUENCE.length());
        }
        if (PARENT_KEY_SEQUENCE.equals(keySeqBuffer.toString())) {
            keySeqBuffer.setLength(0);
            GuardConfig.grantTempSettingsAccess(3 * 60 * 1000L);
            if (parentSecretPanel != null) {
                parentSecretPanel.setVisibility(View.VISIBLE);
                if (parentSecretPanel.getChildCount() > 0) {
                    parentSecretPanel.getChildAt(0).requestFocus();
                }
            }
        }
    }

    /**
     * 触发小米电视自动息屏休眠（优先调用系统原生锁屏息屏，同时开启全黑 0 亮度休眠层兜底）
     */
    public void triggerTvScreenOffOrBlackout() {
        GuardAccessibilityService acc = GuardAccessibilityService.getInstance();
        if (acc != null) {
            acc.tryLockScreenOff();
        }
        enterBlackoutSleepMode();
    }

    private void enterBlackoutSleepMode() {
        if (lockOverlayView == null) return;
        isBlackoutSleepMode = true;
        lockOverlayView.setBackgroundColor(Color.BLACK);
        if (lockContentBox != null) lockContentBox.setVisibility(View.INVISIBLE);
        if (lockFooterView != null) lockFooterView.setVisibility(View.INVISIBLE);
    }

    private void exitBlackoutSleepMode() {
        if (lockOverlayView == null) return;
        isBlackoutSleepMode = false;
        lockOverlayView.setBackgroundColor(Color.parseColor("#0B241A"));
        if (lockContentBox != null) lockContentBox.setVisibility(View.VISIBLE);
        if (lockFooterView != null) lockFooterView.setVisibility(View.VISIBLE);
    }

    public void hideLockOverlayAndRestoreAudio() {
        mainHandler.post(() -> {
            if (isLockOverlayShowing && lockWindowManager != null && lockOverlayView != null) {
                try {
                    lockWindowManager.removeViewImmediate(lockOverlayView);
                } catch (Exception ignored) {
                }
            }
            lockOverlayView = null;
            isLockOverlayShowing = false;
            isBlackoutSleepMode = false;
            lockOverlayElapsedSeconds = 0;
            restoreMediaAudio();
        });
    }

    // ==================== 右上角温和倒计时胶囊提示 ====================

    private void showOrUpdateWarningBanner(String text, String bgColorHex) {
        try {
            if (!isWarningBannerShowing) {
                Context ctx = getBestOverlayContext();
                toastWindowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (toastWindowManager == null) return;

                warningBannerView = new TextView(ctx);
                warningBannerView.setTextColor(Color.WHITE);
                warningBannerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                warningBannerView.setTypeface(Typeface.DEFAULT_BOLD);
                int padH = dp(ctx, 22);
                int padV = dp(ctx, 12);
                warningBannerView.setPadding(padH, padV, padH, padV);

                addViewWithFallbackWindowType(toastWindowManager, warningBannerView, false);
                isWarningBannerShowing = true;
            }
            if (warningBannerView != null) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor(bgColorHex));
                bg.setCornerRadius(dp(this, 24));
                bg.setStroke(dp(this, 2), Color.parseColor("#FBBF24"));
                warningBannerView.setBackground(bg);
                warningBannerView.setText(text);
            }
        } catch (Exception ignored) {
        }
    }

    private void hideWarningBanner() {
        if (isWarningBannerShowing && toastWindowManager != null && warningBannerView != null) {
            try {
                toastWindowManager.removeViewImmediate(warningBannerView);
            } catch (Exception ignored) {
            }
        }
        warningBannerView = null;
        isWarningBannerShowing = false;
    }

    // ==================== 暂停视频与静音控制 ====================

    private void pauseAndMuteMedia() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;

            // 发送系统级媒体暂停与停止按键，让后台视频 App 暂停播放
            long now = SystemClock.uptimeMillis();
            KeyEvent pauseDown = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
            KeyEvent pauseUp = new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
            am.dispatchMediaKeyEvent(pauseDown);
            am.dispatchMediaKeyEvent(pauseUp);

            // 将媒体音量设为 0（记录原始音量以便解锁后恢复）
            int currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (currentVol > 0) {
                if (savedMusicVolume < 0) {
                    savedMusicVolume = currentVol;
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreMediaAudio() {
        try {
            if (savedMusicVolume > 0) {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0);
                }
                savedMusicVolume = -1;
            }
        } catch (Exception ignored) {
        }
    }

    private void playGentleBeep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 250);
            mainHandler.postDelayed(tg::release, 400);
        } catch (Exception ignored) {
        }
    }

    /**
     * 在电视上直接调起系统卸载界面（供电脑管理端一键触发卸载）
     */
    public void launchSystemUninstallOnTv() {
        mainHandler.post(() -> {
            config.setGuardEnabled(false);
            GuardConfig.grantTempSettingsAccess(10 * 60 * 1000L);
            hideWarningBanner();
            hideLockOverlayAndRestoreAudio();
            try {
                Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
                uninstallIntent.setData(Uri.parse("package:" + getPackageName()));
                uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(uninstallIntent);
            } catch (Exception ignored) {
            }
        });
    }

    public void triggerCheckFromWeb() {
        mainHandler.post(this::onSecondTick);
    }

    // ==================== 悬浮窗多版本兼容工具 ====================

    private Context getBestOverlayContext() {
        GuardAccessibilityService acc = GuardAccessibilityService.getInstance();
        return acc != null ? acc : this;
    }

    @SuppressWarnings("deprecation")
    private void addViewWithFallbackWindowType(WindowManager wm, View view, boolean fullScreenIntercept) {
        int[] candidateTypes;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            candidateTypes = new int[]{
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.TYPE_PHONE
            };
        } else {
            candidateTypes = new int[]{
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.TYPE_TOAST
            };
        }

        for (int type : candidateTypes) {
            try {
                WindowManager.LayoutParams lp;
                if (fullScreenIntercept) {
                    int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                    lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            type,
                            flags,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.CENTER;
                } else {
                    int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
                    lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            type,
                            flags,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.END;
                    lp.x = dp(this, 32);
                    lp.y = dp(this, 32);
                }
                wm.addView(view, lp);
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private static int dp(Context context, int dpValue) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dpValue, context.getResources().getDisplayMetrics());
    }

    private void startForegroundWithNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "电视时长守护", NotificationManager.IMPORTANCE_LOW);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Intent openIntent = new Intent(this, MainActivity.class);
            int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

            Notification notification = builder
                    .setContentTitle("电视时长守护运行中")
                    .setContentText("管理端 http://" + GuardConfig.getLocalIpAddress() + ":" + GuardConfig.WEB_PORT)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        hideWarningBanner();
        hideLockOverlayAndRestoreAudio();
        if (webServer != null) {
            webServer.stopServer();
            webServer = null;
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
