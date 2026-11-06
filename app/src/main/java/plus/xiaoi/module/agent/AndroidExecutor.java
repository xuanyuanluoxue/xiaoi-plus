package plus.xiaoi.module.agent;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.Json;

/**
 * WP1.2a 的 Android 执行器：只实现 {@code device.info}。
 *
 * <p>其余 4 个动作（app.open / alarm.set / notification.send / media.control）留给
 * WP1.2b；本类对它们返回 {@code NOT_AVAILABLE}，能力表因此显示为不可用。
 * 所有系统读取都做异常隔离：单个字段拿不到就省略，不把整个动作拖崩。</p>
 */
public final class AndroidExecutor implements ActionExecutor {

    private static final String NAME = "android";

    private static final String[] DEFAULT_FIELDS = {
            "model", "manufacturer", "android", "sdk",
            "battery", "charging", "network", "uptime"
    };

    private final Context ctx;

    public AndroidExecutor(Context context) {
        this.ctx = context == null ? null : context.getApplicationContext();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return ctx != null;
    }

    @Override
    public ActionResult execute(ActionRequest request) throws Exception {
        if (request == null) {
            return ActionResult.failure("", NAME,
                    AgentError.of(AgentError.Code.INVALID_ARGUMENT, "request 不能为空"));
        }
        if ("device.info".equals(request.action)) {
            return deviceInfo(request);
        }
        return ActionResult.failure(request.action, NAME,
                AgentError.of(AgentError.Code.NOT_AVAILABLE,
                        "WP1.2a 尚未实现动作：" + request.action));
    }

    // ------------------------------------------------------------- device.info

    private ActionResult deviceInfo(ActionRequest request) {
        List<String> requested = request.stringListArg("fields");
        if (requested == null || requested.isEmpty()) {
            requested = new ArrayList<String>();
            for (String f : DEFAULT_FIELDS) requested.add(f);
        }

        Map<String, Object> fields = Json.obj();
        for (String field : requested) {
            putField(fields, field);
        }

        Map<String, Object> result = Json.obj();
        result.put("fields", fields);
        return ActionResult.ok("device.info", NAME, result);
    }

    /** 按请求名写字段；未知/读不到的字段保持省略。 */
    private void putField(Map<String, Object> out, String field) {
        if (field == null || field.length() == 0) return;
        String f = field.trim();
        try {
            if ("model".equals(f)) {
                out.put(f, safe(Build.MODEL));
            } else if ("manufacturer".equals(f)) {
                out.put(f, safe(Build.MANUFACTURER));
            } else if ("brand".equals(f)) {
                out.put(f, safe(Build.BRAND));
            } else if ("device".equals(f)) {
                out.put(f, safe(Build.DEVICE));
            } else if ("android".equals(f)) {
                out.put(f, safe(Build.VERSION.RELEASE));
            } else if ("sdk".equals(f) || "sdkInt".equals(f)) {
                out.put(f, (long) Build.VERSION.SDK_INT);
            } else if ("battery".equals(f) || "batteryLevel".equals(f)) {
                int level = batteryLevel();
                if (level >= 0) out.put(f, (long) level);
            } else if ("charging".equals(f)) {
                Boolean charging = charging();
                if (charging != null) out.put(f, charging);
            } else if ("network".equals(f) || "networkType".equals(f)) {
                String type = networkType();
                if (type != null) out.put(f, type);
            } else if ("uptime".equals(f)) {
                out.put(f, SystemClock.elapsedRealtime() / 1000L);
            } else if ("uptimeMs".equals(f)) {
                out.put(f, SystemClock.elapsedRealtime());
            } else if ("memory".equals(f) || "foregroundApp".equals(f)) {
                // 需要 root / usage stats，WP1.2a 明确不提供；按 schema 省略。
            }
        } catch (Throwable ignored) {
            // 单个字段失败不阻塞其他字段。
        }
    }

    private int batteryLevel() {
        if (ctx == null) return -1;
        Intent battery = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) return -1;
        return Math.max(0, Math.min(100, level * 100 / scale));
    }

    private Boolean charging() {
        if (ctx == null) return null;
        Intent battery = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return null;
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        if (status < 0) return null;
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private String networkType() {
        if (ctx == null) return null;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        if (Build.VERSION.SDK_INT >= 23) {
            Network network = cm.getActiveNetwork();
            if (network == null) return "none";
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return "none";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return "bluetooth";
            return "other";
        }
        return "unknown";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
