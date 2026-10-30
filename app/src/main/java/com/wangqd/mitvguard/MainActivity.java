package com.wangqd.mitvguard;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 小米电视端大屏仪表盘界面（现代 Apple TV / 极简深色卡片风格）
 * 核心突出：今日剩余时长、已看时长、规则状态；底部角落弱化显示管理端小字
 */
public class MainActivity extends Activity {
    private GuardConfig config;
    private Handler handler;

    private TextView badgeModeView;
    private TextView badgeAutoStartView;
    private TextView remainValView;
    private TextView remainSubView;
    private TextView usedValView;
    private TextView usedSubView;
    private TextView limitValView;
    private TextView limitSubView;
    private ProgressBar progressBar;
    private TextView footerUrlView;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new GuardConfig(this);
        handler = new Handler(Looper.getMainLooper());

        BootReceiver.startGuardServiceSafely(this);

        FrameLayout screenRoot = new FrameLayout(this);
        GradientDrawable bgGrad = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#09131C"), Color.parseColor("#0F222D"), Color.parseColor("#081018")});
        screenRoot.setBackground(bgGrad);

        // 主内容区居中容器
        LinearLayout mainContainer = new LinearLayout(this);
        mainContainer.setOrientation(LinearLayout.VERTICAL);
        mainContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        int padH = dp(64);
        int padV = dp(42);
        mainContainer.setPadding(padH, padV, padH, padV);

        // ==================== 1. 顶部标题与状态胶囊栏 ====================
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams topBarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleColLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        TextView titleView = new TextView(this);
        titleView.setText("🌿 电视时长守护");
        titleView.setTextColor(Color.parseColor("#F8FAFC"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText("培养健康用眼习惯 · 亮屏自动精准计时");
        subtitleView.setTextColor(Color.parseColor("#64748B"));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        titleCol.addView(subtitleView, subLp);

        topBar.addView(titleCol, titleColLp);

        // 右上角状态胶囊组
        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);

        badgeModeView = createPillBadge("儿童守护开启中", "#064E3B", "#34D399");
        badgeAutoStartView = createPillBadge("开机自启已激活", "#1E293B", "#94A3B8");
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b2Lp.leftMargin = dp(10);

        badgeRow.addView(badgeModeView);
        badgeRow.addView(badgeAutoStartView, b2Lp);
        topBar.addView(badgeRow);

        mainContainer.addView(topBar, topBarLp);

        // ==================== 2. 中央三列核心数据卡片 ====================
        LinearLayout cardsRow = new LinearLayout(this);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setWeightSum(3f);
        LinearLayout.LayoutParams cardsRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardsRowLp.topMargin = dp(28);

        // 卡片 1：今日剩余可用时长（视觉主焦点）
        remainValView = new TextView(this);
        remainSubView = new TextView(this);
        LinearLayout cardRemain = createMetricCard(
                "今日剩余可用时长",
                remainValView,
                remainSubView,
                "#0D3327",
                "#10B981",
                "#34D399",
                true);
        LinearLayout.LayoutParams c1Lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f);
        c1Lp.rightMargin = dp(16);
        cardsRow.addView(cardRemain, c1Lp);

        // 卡片 2：今日已观看时长
        usedValView = new TextView(this);
        usedSubView = new TextView(this);
        LinearLayout cardUsed = createMetricCard(
                "今日已观看时长",
                usedValView,
                usedSubView,
                "#132030",
                "#263B54",
                "#F1F5F9",
                false);
        LinearLayout.LayoutParams c2Lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.925f);
        c2Lp.rightMargin = dp(16);
        cardsRow.addView(cardUsed, c2Lp);

        // 卡片 3：今日总额度与规则
        limitValView = new TextView(this);
        limitSubView = new TextView(this);
        LinearLayout cardLimit = createMetricCard(
                "今日允许总额度",
                limitValView,
                limitSubView,
                "#132030",
                "#263B54",
                "#60A5FA",
                false);
        LinearLayout.LayoutParams c3Lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.925f);
        cardsRow.addView(cardLimit, c3Lp);

        mainContainer.addView(cardsRow, cardsRowLp);

        // ==================== 3. 观看进度条 ====================
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        GradientDrawable progBg = new GradientDrawable();
        progBg.setColor(Color.parseColor("#1E293B"));
        progBg.setCornerRadius(dp(6));
        progressBar.setBackground(progBg);
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        progLp.topMargin = dp(22);
        mainContainer.addView(progressBar, progLp);

        // ==================== 4. 电视遥控器操作按钮行 ====================
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.topMargin = dp(28);

        TextView btnBackHome = createModernTvButton(
                "🏠  返回电视桌面（后台静默守护）",
                "#10B981",
                "#059669",
                v -> finish());

        TextView btnTestLock = createModernTvButton(
                "🔒  预览护眼锁屏画面",
                "#334155",
                "#1E293B",
                v -> {
                    config.setGuardEnabled(true);
                    config.setForceLocked(true);
                    if (GuardService.getInstance() != null) {
                        GuardService.getInstance().triggerCheckFromWeb();
                    }
                });
        LinearLayout.LayoutParams testBtnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        testBtnLp.leftMargin = dp(18);

        btnRow.addView(btnBackHome);
        btnRow.addView(btnTestLock, testBtnLp);
        mainContainer.addView(btnRow, btnRowLp);

        FrameLayout.LayoutParams mainLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        mainLp.gravity = Gravity.CENTER;
        screenRoot.addView(mainContainer, mainLp);

        // ==================== 5. 底部角落低调辅助信息栏 ====================
        footerUrlView = new TextView(this);
        footerUrlView.setTextColor(Color.parseColor("#475569"));
        footerUrlView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footerUrlView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        footerLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        footerLp.bottomMargin = dp(18);
        screenRoot.addView(footerUrlView, footerLp);

        setContentView(screenRoot);
        btnBackHome.requestFocus();
    }

    private LinearLayout createMetricCard(
            String label,
            TextView valueView,
            TextView subView,
            String bgHex,
            String borderHex,
            String valueColorHex,
            boolean isPrimary) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(24);
        card.setPadding(pad, dp(22), pad, dp(22));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(isPrimary ? 2 : 1), Color.parseColor(borderHex));
        card.setBackground(bg);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(Color.parseColor(isPrimary ? "#A7F3D0" : "#94A3B8"));
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.addView(lbl);

        valueView.setTextColor(Color.parseColor(valueColorHex));
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, isPrimary ? 36 : 30);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        valLp.topMargin = dp(10);
        card.addView(valueView, valLp);

        subView.setTextColor(Color.parseColor(isPrimary ? "#6EE7B7" : "#64748B"));
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        card.addView(subView, subLp);

        return card;
    }

    private TextView createPillBadge(String text, String bgHex, String textHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(textHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(1), Color.parseColor(textHex));
        tv.setBackground(bg);
        return tv;
    }

    private TextView createModernTvButton(
            String text,
            String focusedBgHex,
            String normalBgHex,
            View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(26), dp(13), dp(26), dp(13));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setClickable(true);

        GradientDrawable focusedDrawable = new GradientDrawable();
        focusedDrawable.setColor(Color.parseColor(focusedBgHex));
        focusedDrawable.setCornerRadius(dp(12));
        focusedDrawable.setStroke(dp(2), Color.WHITE);

        GradientDrawable normalDrawable = new GradientDrawable();
        normalDrawable.setColor(Color.parseColor(normalBgHex));
        normalDrawable.setCornerRadius(dp(12));
        normalDrawable.setStroke(dp(1), Color.parseColor("#475569"));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        states.addState(new int[]{android.R.attr.state_pressed}, focusedDrawable);
        states.addState(new int[]{}, normalDrawable);
        btn.setBackground(states);

        btn.setOnClickListener(listener);
        return btn;
    }

    private void updateUi() {
        boolean guardOn = config.isGuardEnabled();
        boolean adultTonight = config.isAdultOverrideToday();
        boolean childActiveNow = config.isChildGuardActiveRightNow();
        boolean accActive = GuardAccessibilityService.getInstance() != null;

        if (!guardOn) {
            updatePillBadge(badgeModeView, "⏸️ 总守护已暂停", "#451A03", "#FBBF24");
        } else if (adultTonight) {
            updatePillBadge(badgeModeView, "🛋️ 今晚大人接管 · 明日自动恢复", "#1E3A8A", "#93C5FD");
        } else if (childActiveNow) {
            updatePillBadge(badgeModeView, "🛡️ 儿童守护生效中", "#064E3B", "#34D399");
        } else {
            updatePillBadge(badgeModeView, "☕ 自由时段 · 不影响大人", "#0F2942", "#38BDF8");
        }

        if (accActive) {
            updatePillBadge(badgeAutoStartView, "⚡ 开机自启已激活", "#1E293B", "#94A3B8");
        } else {
            updatePillBadge(badgeAutoStartView, "⚠️ 待激活无障碍保活", "#3B1818", "#F87171");
        }

        int usedSec = config.getUsedSecondsToday();
        int remainSec = config.getRemainingSecondsToday();
        int totalSec = config.getTodayTotalLimitSeconds();
        int totalMin = totalSec / 60;
        int bonusMin = config.getBonusMinutesToday();

        if (!guardOn || adultTonight || !config.isTodayActiveDay()) {
            remainValView.setText("不限时");
            remainValView.setTextColor(Color.parseColor("#60A5FA"));
            if (!config.isTodayActiveDay()) {
                remainSubView.setText("今天是" + GuardConfig.getTodayDayOfWeekChinese() + "，不在管控周期内");
            } else {
                remainSubView.setText(adultTonight ? "今晚大人接管免限，次日自动恢复儿童守护" : "总开关已暂停，不限制观看时长");
            }
        } else if (!childActiveNow && GuardConfig.MODE_FREE.equals(config.getOutsideWindowMode())) {
            remainValView.setText(GuardConfig.formatDurationChinese(remainSec));
            remainValView.setTextColor(Color.parseColor("#38BDF8"));
            remainSubView.setText(String.format(Locale.CHINA,
                    "当前为自由时段（不扣额度），%s 起进入儿童守护", config.getActiveStartTime()));
        } else {
            remainValView.setText(GuardConfig.formatDurationChinese(remainSec));
            if (remainSec <= 300) {
                remainValView.setTextColor(Color.parseColor("#F87171"));
                remainSubView.setText("即将到达今日休息时间");
            } else {
                remainValView.setTextColor(Color.parseColor("#34D399"));
                remainSubView.setText("儿童守护时段内亮屏自动倒计时");
            }
        }

        usedValView.setText(GuardConfig.formatDurationChinese(usedSec));
        usedSubView.setText("仅在儿童守护生效期内累计");

        limitValView.setText(String.format(Locale.CHINA, "%d 分钟", totalMin));
        String daysDesc = "1,2,3,4,5".equals(config.getActiveDays()) ? "周一至周五" : "自定义周期";
        String windowDesc = String.format(Locale.CHINA, "%s %s~%s 生效",
                daysDesc, config.getActiveStartTime(), config.getActiveEndTime());
        if (bonusMin != 0) {
            windowDesc += String.format(Locale.CHINA, " (%+d分)", bonusMin);
        }
        limitSubView.setText(windowDesc);

        int permille = totalSec > 0 ? Math.min(1000, (int) (usedSec * 1000L / totalSec)) : 1000;
        progressBar.setProgress(permille);

        String ip = GuardConfig.getLocalIpAddress();
        footerUrlView.setText(String.format(Locale.CHINA,
                "局域网设置端  http://%s:%d   ·   锁屏页遥控器快捷键：上上下下左右",
                ip, GuardConfig.WEB_PORT));
    }

    private void updatePillBadge(TextView tv, String text, String bgHex, String textHex) {
        tv.setText(text);
        tv.setTextColor(Color.parseColor(textHex));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgHex));
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(1), Color.parseColor(textHex));
        tv.setBackground(bg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
