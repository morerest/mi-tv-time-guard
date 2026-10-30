package com.wangqd.mitvguard;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;

/**
 * 电视时长守护配置与当日状态持久化管理（V2：支持周一至周五 17:00 后生效、非生效时段自由看、今晚大人接管次日自动恢复）
 */
public class GuardConfig {
    private static final String PREF_NAME = "mi_tv_guard_prefs";

    // 配置项 Keys
    private static final String KEY_ENABLED = "guard_enabled";
    private static final String KEY_DAILY_LIMIT_MIN = "daily_limit_min";
    private static final String KEY_ACTIVE_DAYS = "active_days_iso"; // "1,2,3,4,5" (1=周一..7=周日)
    private static final String KEY_ACTIVE_START = "active_start_time"; // 默认 "17:00"
    private static final String KEY_ACTIVE_END = "active_end_time";     // 默认 "22:30"
    private static final String KEY_OUTSIDE_MODE = "outside_window_mode"; // "FREE"(不影响大人) 或 "BLOCK"(严管锁屏)
    private static final String KEY_AUTO_OFF_SECONDS = "auto_off_seconds";
    private static final String KEY_LOCK_MESSAGE = "lock_message";
    private static final String KEY_PROTECT_SETTINGS = "protect_settings";

    // 当日动态状态 Keys
    private static final String KEY_CURRENT_DATE = "current_date";
    private static final String KEY_USED_SECONDS = "used_seconds_today";
    private static final String KEY_BONUS_MINUTES = "bonus_minutes_today";
    private static final String KEY_FORCE_LOCKED = "force_locked";
    private static final String KEY_ADULT_OVERRIDE_DATE = "adult_override_date"; // 今晚大人接管（仅当日有效，次日自动恢复）

    public static final int WEB_PORT = 8686;
    public static final String MODE_FREE = "FREE";
    public static final String MODE_BLOCK = "BLOCK";

    private final SharedPreferences prefs;
    private static volatile long tempAllowSettingsUntilMs = 0;

    public GuardConfig(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        checkAndRolloverDay();
    }

    /**
     * 检查日期是否跨天，若已进入新的一天，自动重置今日已看时长、临时加时与强制锁屏
     */
    public synchronized boolean checkAndRolloverDay() {
        String today = getTodayDateString();
        String savedDate = prefs.getString(KEY_CURRENT_DATE, "");
        if (!today.equals(savedDate)) {
            prefs.edit()
                    .putString(KEY_CURRENT_DATE, today)
                    .putInt(KEY_USED_SECONDS, 0)
                    .putInt(KEY_BONUS_MINUTES, 0)
                    .putBoolean(KEY_FORCE_LOCKED, false)
                    .apply();
            return true;
        }
        return false;
    }

    public static String getTodayDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static String getCurrentTimeString() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    public static String getCurrentHourMinuteString() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    /**
     * 获取今天是星期几（ISO 标准：1=周一, 2=周二, ..., 5=周五, 6=周六, 7=周日）
     */
    public static int getTodayIsoDayOfWeek() {
        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK); // 周日=1, 周一=2, ..., 周六=7
        return dow == Calendar.SUNDAY ? 7 : (dow - 1);
    }

    public static String getTodayDayOfWeekChinese() {
        String[] names = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int iso = getTodayIsoDayOfWeek();
        return (iso >= 1 && iso <= 7) ? names[iso] : "";
    }

    // ==================== 核心规则 Getter / Setter ====================

    public boolean isGuardEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setGuardEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            setForceLocked(false);
        }
    }

    /**
     * 今晚大人接管模式：仅在当天有效，次日自动恢复儿童守护
     */
    public boolean isAdultOverrideToday() {
        checkAndRolloverDay();
        String overrideDate = prefs.getString(KEY_ADULT_OVERRIDE_DATE, "");
        return getTodayDateString().equals(overrideDate);
    }

    public void setAdultOverrideToday(boolean overrideToday) {
        checkAndRolloverDay();
        prefs.edit()
                .putString(KEY_ADULT_OVERRIDE_DATE, overrideToday ? getTodayDateString() : "")
                .putBoolean(KEY_FORCE_LOCKED, false)
                .apply();
    }

    public int getDailyLimitMinutes() {
        return prefs.getInt(KEY_DAILY_LIMIT_MIN, 30);
    }

    public void setDailyLimitMinutes(int minutes) {
        prefs.edit().putInt(KEY_DAILY_LIMIT_MIN, Math.max(1, Math.min(720, minutes))).apply();
    }

    /**
     * 生效星期列表，默认 "1,2,3,4,5"（周一至周五）
     */
    public String getActiveDays() {
        return prefs.getString(KEY_ACTIVE_DAYS, "1,2,3,4,5");
    }

    public void setActiveDays(String daysCsv) {
        if (daysCsv != null) {
            prefs.edit().putString(KEY_ACTIVE_DAYS, daysCsv.trim()).apply();
        }
    }

    public boolean isTodayActiveDay() {
        String csv = "," + getActiveDays().replaceAll("\\s+", "") + ",";
        int todayIso = getTodayIsoDayOfWeek();
        return csv.contains("," + todayIso + ",");
    }

    /**
     * 守护生效开始时间（默认 17:00 放学后生效）
     */
    public String getActiveStartTime() {
        return prefs.getString(KEY_ACTIVE_START, "17:00");
    }

    public void setActiveStartTime(String time) {
        if (isValidHourMinute(time)) {
            prefs.edit().putString(KEY_ACTIVE_START, time.trim()).apply();
        }
    }

    /**
     * 守护生效结束时间（默认 22:30）
     */
    public String getActiveEndTime() {
        return prefs.getString(KEY_ACTIVE_END, "22:30");
    }

    public void setActiveEndTime(String time) {
        if (isValidHourMinute(time)) {
            prefs.edit().putString(KEY_ACTIVE_END, time.trim()).apply();
        }
    }

    /**
     * 非生效时段模式：
     * - "FREE"（默认）：17:00 前或非生效日属于家庭自由观看时段，不计时、不锁屏，不影响大人看电视；
     * - "BLOCK"：仅允许在生效时段内观看，其余时间直接锁屏。
     */
    public String getOutsideWindowMode() {
        return prefs.getString(KEY_OUTSIDE_MODE, MODE_FREE);
    }

    public void setOutsideWindowMode(String mode) {
        if (MODE_BLOCK.equalsIgnoreCase(mode)) {
            prefs.edit().putString(KEY_OUTSIDE_MODE, MODE_BLOCK).apply();
        } else {
            prefs.edit().putString(KEY_OUTSIDE_MODE, MODE_FREE).apply();
        }
    }

    public boolean isNowInActiveTimeWindow() {
        int startMin = parseMinutesFromMidnight(getActiveStartTime(), 17 * 60);
        int endMin = parseMinutesFromMidnight(getActiveEndTime(), 22 * 60 + 30);
        Calendar cal = Calendar.getInstance();
        int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        if (startMin == endMin) {
            return true; // 起止相同视为全天
        } else if (startMin < endMin) {
            return nowMin >= startMin && nowMin < endMin;
        } else {
            return nowMin >= startMin || nowMin < endMin;
        }
    }

    /**
     * 判断当前这一秒是否处于“儿童时长管控激活期”
     */
    public boolean isChildGuardActiveRightNow() {
        if (!isGuardEnabled() || isAdultOverrideToday()) {
            return false;
        }
        return isTodayActiveDay() && isNowInActiveTimeWindow();
    }

    public int getAutoOffSeconds() {
        return prefs.getInt(KEY_AUTO_OFF_SECONDS, 15);
    }

    public void setAutoOffSeconds(int seconds) {
        prefs.edit().putInt(KEY_AUTO_OFF_SECONDS, Math.max(0, Math.min(300, seconds))).apply();
    }

    public String getLockMessage() {
        return prefs.getString(KEY_LOCK_MESSAGE, "今日看电视时间已用完啦，保护眼睛，去休息或看看书吧！");
    }

    public void setLockMessage(String msg) {
        if (msg != null && !msg.trim().isEmpty()) {
            prefs.edit().putString(KEY_LOCK_MESSAGE, msg.trim()).apply();
        }
    }

    public boolean isProtectSettings() {
        return prefs.getBoolean(KEY_PROTECT_SETTINGS, true);
    }

    public void setProtectSettings(boolean protect) {
        prefs.edit().putBoolean(KEY_PROTECT_SETTINGS, protect).apply();
    }

    // ==================== 当日计时与动态状态 ====================

    public int getBonusMinutesToday() {
        checkAndRolloverDay();
        return prefs.getInt(KEY_BONUS_MINUTES, 0);
    }

    public void addBonusMinutesToday(int deltaMinutes) {
        checkAndRolloverDay();
        int current = prefs.getInt(KEY_BONUS_MINUTES, 0);
        prefs.edit()
                .putInt(KEY_BONUS_MINUTES, current + deltaMinutes)
                .putBoolean(KEY_FORCE_LOCKED, false)
                .apply();
    }

    public int getTodayTotalLimitSeconds() {
        int totalMinutes = Math.max(0, getDailyLimitMinutes() + getBonusMinutesToday());
        return totalMinutes * 60;
    }

    public int getUsedSecondsToday() {
        checkAndRolloverDay();
        return prefs.getInt(KEY_USED_SECONDS, 0);
    }

    public void incrementUsedSecond() {
        checkAndRolloverDay();
        int used = prefs.getInt(KEY_USED_SECONDS, 0) + 1;
        prefs.edit().putInt(KEY_USED_SECONDS, used).apply();
    }

    public void resetTodayUsage() {
        checkAndRolloverDay();
        prefs.edit()
                .putInt(KEY_USED_SECONDS, 0)
                .putInt(KEY_BONUS_MINUTES, 0)
                .putBoolean(KEY_FORCE_LOCKED, false)
                .apply();
    }

    public int getRemainingSecondsToday() {
        if (isForceLocked()) {
            return 0;
        }
        return Math.max(0, getTodayTotalLimitSeconds() - getUsedSecondsToday());
    }

    public boolean isForceLocked() {
        checkAndRolloverDay();
        return prefs.getBoolean(KEY_FORCE_LOCKED, false);
    }

    public void setForceLocked(boolean forceLocked) {
        checkAndRolloverDay();
        prefs.edit().putBoolean(KEY_FORCE_LOCKED, forceLocked).apply();
    }

    public static void grantTempSettingsAccess(long durationMs) {
        tempAllowSettingsUntilMs = System.currentTimeMillis() + durationMs;
    }

    public static boolean isTempSettingsAccessAllowed() {
        return System.currentTimeMillis() < tempAllowSettingsUntilMs;
    }

    private static boolean isValidHourMinute(String s) {
        if (s == null) return false;
        return s.trim().matches("^([01]?\\d|2[0-3]):[0-5]\\d$");
    }

    public static int parseMinutesFromMidnight(String hhmm, int fallback) {
        try {
            String[] parts = hhmm.trim().split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            return h * 60 + m;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String candidate = null;
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName().toLowerCase(Locale.US);
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return ip;
                        }
                        if (candidate == null) {
                            candidate = ip;
                        }
                    }
                }
            }
            if (candidate != null) return candidate;
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public static String formatDurationChinese(int totalSeconds) {
        if (totalSeconds <= 0) return "0分00秒";
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.CHINA, "%d小时%02d分%02d秒", hours, minutes, seconds);
        }
        return String.format(Locale.CHINA, "%d分%02d秒", minutes, seconds);
    }
}
