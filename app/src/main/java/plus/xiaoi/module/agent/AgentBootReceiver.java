package plus.xiaoi.module.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import plus.xiaoi.module.Logs;

/**
 * 开机自启：只处理 BOOT_COMPLETED。
 *
 * <p>是否真正监听由 {@link AgentService#startIfEnabled(Context)} 按配置判断；
 * 用户关闭 MCP 后不会自动恢复。</p>
 */
public final class AgentBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            Logs.i("AgentBootReceiver: 收到 BOOT_COMPLETED");
            AgentService.startIfEnabled(context);
        } catch (Throwable t) {
            Logs.e("AgentBootReceiver 启动失败（已忽略）: " + t.getClass().getSimpleName());
        }
    }
}
