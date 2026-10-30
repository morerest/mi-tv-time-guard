package com.wangqd.mitvguard;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置轻量 HTTP 服务器（监听 8686 端口）
 * 提供电脑端浏览器打开的可视化控制面板与 JSON API（支持跨域 CORS）
 */
public class WebServer {
    private final GuardService service;
    private final GuardConfig config;
    private final int port;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private String cachedHtml;

    public WebServer(GuardService service, GuardConfig config, int port) {
        this.service = service;
        this.config = config;
        this.port = port;
    }

    public synchronized void startServer() {
        if (running) return;
        running = true;
        executor = Executors.newCachedThreadPool();
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.execute(() -> handleClient(client));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    public synchronized void stopServer() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 1. 按字节读取 HTTP Header（直到 \r\n\r\n）
            ByteArrayOutputStream headerBos = new ByteArrayOutputStream();
            int b;
            int matchState = 0;
            while ((b = in.read()) != -1) {
                headerBos.write(b);
                if ((matchState == 0 || matchState == 2) && b == '\r') {
                    matchState++;
                } else if ((matchState == 1 || matchState == 3) && b == '\n') {
                    matchState++;
                    if (matchState == 4) break;
                } else {
                    matchState = (b == '\r') ? 1 : 0;
                }
                if (headerBos.size() > 16384) break;
            }

            String rawHeaders = new String(headerBos.toByteArray(), StandardCharsets.UTF_8);
            String[] headerLines = rawHeaders.split("\r\n");
            if (headerLines.length == 0 || headerLines[0].isEmpty()) {
                client.close();
                return;
            }

            String requestLine = headerLines[0];
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0].toUpperCase() : "GET";
            String fullUri = parts.length > 1 ? parts[1] : "/";

            String path = fullUri;
            String queryString = "";
            int qIdx = fullUri.indexOf('?');
            if (qIdx >= 0) {
                path = fullUri.substring(0, qIdx);
                queryString = fullUri.substring(qIdx + 1);
            }

            int contentLength = 0;
            for (int i = 1; i < headerLines.length; i++) {
                String line = headerLines[i];
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ignored) {
                    }
                }
            }

            // 2. 按精确字节数读取 UTF-8 Body（彻底解决中文多字节导致字符流卡死超时的问题）
            String body = "";
            if (contentLength > 0 && contentLength < 65536) {
                byte[] bodyBytes = new byte[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int r = in.read(bodyBytes, totalRead, contentLength - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }
                body = new String(bodyBytes, 0, totalRead, StandardCharsets.UTF_8);
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(out, 200, "text/plain; charset=utf-8", "OK");
                client.close();
                return;
            }

            if ("/api/status".equals(path)) {
                sendResponse(out, 200, "application/json; charset=utf-8", buildStatusJson());
            } else if ("/api/action".equals(path)) {
                Map<String, String> params = parseParams(queryString, body);
                String resp = handleAction(params);
                sendResponse(out, 200, "application/json; charset=utf-8", resp);
            } else if ("/api/config".equals(path)) {
                Map<String, String> params = parseParams(queryString, body);
                String resp = handleSaveConfig(params);
                sendResponse(out, 200, "application/json; charset=utf-8", resp);
            } else {
                sendResponse(out, 200, "text/html; charset=utf-8", getDashboardHtml());
            }
            out.flush();
            client.close();
        } catch (Exception ignored) {
            try {
                client.close();
            } catch (Exception e) {
            }
        }
    }

    private String handleAction(Map<String, String> params) {
        String action = params.get("action");
        String message = "操作已生效";
        if ("add_time".equals(action)) {
            int mins = parseIntSafe(params.get("minutes"), 15);
            config.addBonusMinutesToday(mins);
            message = (mins >= 0 ? "已临时增加 " + mins + " 分钟" : "已扣减 " + (-mins) + " 分钟");
        } else if ("reset_today".equals(action)) {
            config.resetTodayUsage();
            message = "今日儿童已看时长已重置为 0";
        } else if ("toggle_adult_tonight".equals(action)) {
            boolean next = !config.isAdultOverrideToday();
            config.setGuardEnabled(true);
            config.setAdultOverrideToday(next);
            message = next
                    ? "🛋️ 已开启今晚大人接管（今晚免限制，明天自动恢复儿童守护）"
                    : "🛡️ 已恢复儿童时长守护";
        } else if ("toggle_guard".equals(action)) {
            boolean next = !config.isGuardEnabled();
            config.setGuardEnabled(next);
            if (next) {
                config.setAdultOverrideToday(false);
            }
            message = next ? "已开启时长守护总开关" : "已彻底暂停总开关";
        } else if ("lock_now".equals(action)) {
            config.setGuardEnabled(true);
            config.setAdultOverrideToday(false);
            config.setForceLocked(true);
            message = "🛑 已立即停止电视并弹出护眼锁屏";
        } else if ("unlock_force".equals(action)) {
            config.setForceLocked(false);
            message = "▶️ 已解除手动强制锁屏";
        } else if ("allow_settings".equals(action)) {
            GuardConfig.grantTempSettingsAccess(5 * 60 * 1000L);
            message = "🔓 已临时放行电视【系统设置】5 分钟";
        } else if ("uninstall".equals(action)) {
            service.launchSystemUninstallOnTv();
            message = "已在电视上关闭锁屏并弹出系统卸载确认框";
        }
        service.triggerCheckFromWeb();
        return buildResultJson(true, message);
    }

    private String handleSaveConfig(Map<String, String> params) {
        if (params.containsKey("dailyLimitMinutes")) {
            config.setDailyLimitMinutes(parseIntSafe(params.get("dailyLimitMinutes"), 30));
        }
        if (params.containsKey("activeDays")) {
            config.setActiveDays(params.get("activeDays"));
        }
        if (params.containsKey("activeStartTime")) {
            config.setActiveStartTime(params.get("activeStartTime"));
        }
        if (params.containsKey("activeEndTime")) {
            config.setActiveEndTime(params.get("activeEndTime"));
        }
        if (params.containsKey("outsideWindowMode")) {
            config.setOutsideWindowMode(params.get("outsideWindowMode"));
        }
        if (params.containsKey("autoOffSeconds")) {
            config.setAutoOffSeconds(parseIntSafe(params.get("autoOffSeconds"), 15));
        }
        if (params.containsKey("lockMessage")) {
            config.setLockMessage(params.get("lockMessage"));
        }
        if (params.containsKey("protectSettings")) {
            boolean ps = "true".equalsIgnoreCase(params.get("protectSettings"))
                    || "1".equals(params.get("protectSettings"));
            config.setProtectSettings(ps);
        }
        service.triggerCheckFromWeb();
        return buildResultJson(true, "✅ 规则设置已保存并立即生效");
    }

    private String buildResultJson(boolean ok, String msg) {
        try {
            JSONObject obj = new JSONObject(buildStatusJson());
            obj.put("ok", ok);
            obj.put("message", msg);
            return obj.toString();
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }

    private String buildStatusJson() {
        try {
            config.checkAndRolloverDay();
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("guardEnabled", config.isGuardEnabled());
            json.put("adultOverrideToday", config.isAdultOverrideToday());
            json.put("childGuardActiveNow", config.isChildGuardActiveRightNow());
            json.put("todayActiveDay", config.isTodayActiveDay());
            json.put("inTimeWindow", config.isNowInActiveTimeWindow());
            json.put("screenInteractive", service.isTvScreenInteractive());
            json.put("currentlyLocked", service.isCurrentlyLocked());
            json.put("forceLocked", config.isForceLocked());
            json.put("usedSecondsToday", config.getUsedSecondsToday());
            json.put("remainingSecondsToday", config.getRemainingSecondsToday());
            json.put("totalLimitSecondsToday", config.getTodayTotalLimitSeconds());
            json.put("dailyLimitMinutes", config.getDailyLimitMinutes());
            json.put("bonusMinutesToday", config.getBonusMinutesToday());
            json.put("activeDays", config.getActiveDays());
            json.put("activeStartTime", config.getActiveStartTime());
            json.put("activeEndTime", config.getActiveEndTime());
            json.put("outsideWindowMode", config.getOutsideWindowMode());
            json.put("autoOffSeconds", config.getAutoOffSeconds());
            json.put("lockMessage", config.getLockMessage());
            json.put("protectSettings", config.isProtectSettings());
            json.put("accessibilityActive", GuardAccessibilityService.getInstance() != null);
            json.put("tvIp", GuardConfig.getLocalIpAddress());
            json.put("webPort", GuardConfig.WEB_PORT);
            json.put("currentDate", GuardConfig.getTodayDateString());
            json.put("currentTime", GuardConfig.getCurrentTimeString());
            json.put("dayOfWeekIso", GuardConfig.getTodayIsoDayOfWeek());
            json.put("dayOfWeekName", GuardConfig.getTodayDayOfWeekChinese());
            return json.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private Map<String, String> parseParams(String query, String body) {
        Map<String, String> map = new HashMap<>();
        parseFormEncoded(query, map);
        if (body != null && !body.trim().isEmpty()) {
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) {
                try {
                    JSONObject obj = new JSONObject(trimmed);
                    java.util.Iterator<String> keys = obj.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        map.put(k, String.valueOf(obj.opt(k)));
                    }
                } catch (Exception ignored) {
                }
            } else {
                parseFormEncoded(trimmed, map);
            }
        }
        return map;
    }

    private void parseFormEncoded(String str, Map<String, String> out) {
        if (str == null || str.isEmpty()) return;
        String[] pairs = str.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                try {
                    String k = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
                    out.put(k, v);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private int parseIntSafe(String val, int def) {
        if (val == null) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void sendResponse(OutputStream out, int code, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = code == 200 ? "OK" : "Error";
        String headers = "HTTP/1.1 " + code + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
    }

    private String getDashboardHtml() {
        if (cachedHtml != null) {
            return cachedHtml;
        }
        try (InputStream is = service.getAssets().open("index.html")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            cachedHtml = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return cachedHtml;
        } catch (Exception e) {
            return "<html><body><h1>Mi TV Time Guard Running</h1></body></html>";
        }
    }
}
