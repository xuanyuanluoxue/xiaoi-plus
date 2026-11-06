package plus.xiaoi.module.ui.config;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.AppStatus;
import plus.xiaoi.module.R;
import plus.xiaoi.module.core.StateModel;

/**
 * 配置页顶部状态条、推送行、文件冲突条与运行时冲突条。
 *
 * 只负责把宿主算好的状态画到 View 上；状态字段（statusState/statusDetail/savedWaiting）
 * 仍由 {@code ConfigActivity} 持有，颜色映射也通过 {@link Painter} 复用宿主的唯一实现。
 */
public final class StatusSection {

    public interface Painter {
        int colorOf(AppStatus state);
    }

    private final Context ctx;
    private final Painter painter;
    private final TextView tvStatusDot, tvStatusTitle, tvStatusDesc;
    private final TextView tvPushState, tvPushAction;
    private final View rowPush, bannerConflict, bannerRuntime;
    private final TextView tvRuntimeTitle, tvRuntimeDesc;

    public StatusSection(Context ctx, View root, Painter painter) {
        this.ctx = ctx;
        this.painter = painter;
        tvStatusDot = (TextView) root.findViewById(R.id.tv_status_dot);
        tvStatusTitle = (TextView) root.findViewById(R.id.tv_status_title);
        tvStatusDesc = (TextView) root.findViewById(R.id.tv_status_desc);
        tvPushState = (TextView) root.findViewById(R.id.tv_push_state);
        tvPushAction = (TextView) root.findViewById(R.id.tv_push_action);
        rowPush = root.findViewById(R.id.row_push);
        bannerConflict = root.findViewById(R.id.banner_conflict);
        bannerRuntime = root.findViewById(R.id.banner_runtime);
        tvRuntimeTitle = (TextView) root.findViewById(R.id.tv_runtime_title);
        tvRuntimeDesc = (TextView) root.findViewById(R.id.tv_runtime_desc);
    }

    public void paint(AppStatus state, String detail) {
        if (tvStatusTitle == null) return;
        int c = painter.colorOf(state);
        tvStatusTitle.setText(state.title());
        tvStatusTitle.setTextColor(c);
        tvStatusDot.setTextColor(c);
        tvStatusDesc.setText(detail);
    }

    /**
     * D3 的真实状态：已应用 / 等待小爱应用 / 未连接 / 超过 30s 未上报。
     * 不再出现「推送失败」「令牌不一致」这类旧 HTTP 推送遗留文案。
     */
    public void renderPush(StateModel.AppliedState d3, boolean savedWaiting) {
        if (tvPushState == null) return;
        if (d3 == null) d3 = StateModel.AppliedState.UNKNOWN;
        switch (d3) {
            case APPLIED:
                setPush("已应用 · 小爱已加载当前配置", R.color.success, false);
                return;
            case WAITING:
                setPush("已保存，等待小爱应用", R.color.warning, true);
                return;
            case STALE:
                setPush("小爱超过 30 秒未上报状态 · 点按重新通知", R.color.warning, true);
                return;
            case UNKNOWN:
            default:
                if (savedWaiting) setPush("已保存，等待小爱连接后应用", R.color.warning, true);
                else setPush("保存后自动通知小爱应用", R.color.text_hint, true);
        }
    }

    /** 兼容旧调用点（ConfigActivity 已改用 D3 版本）。 */
    public void renderPush(boolean savedWaiting) {
        renderPush(savedWaiting ? StateModel.AppliedState.WAITING
                : StateModel.AppliedState.UNKNOWN, savedWaiting);
    }

    private void setPush(String text, int colorRes, boolean tappable) {
        tvPushState.setText(text);
        tvPushState.setTextColor(ctx.getColor(colorRes));
        if (rowPush != null) {
            rowPush.setClickable(tappable);
            rowPush.setFocusable(tappable);
        }
        if (tvPushAction != null) {
            tvPushAction.setText(tappable ? "↻" : "✓");
            tvPushAction.setTextColor(ctx.getColor(colorRes));
        }
    }

    public void showConflict() {
        if (bannerConflict != null) bannerConflict.setVisibility(View.VISIBLE);
    }

    public void hideConflict() {
        if (bannerConflict != null) bannerConflict.setVisibility(View.GONE);
    }

    public void showRuntimeConflict(AppConfig saved, String runtimeModel, boolean runtimeEnabled) {
        if (bannerRuntime == null) return;
        String title;
        if (runtimeEnabled != saved.enabled) {
            title = runtimeEnabled
                    ? "小爱侧已启用，文件里是暂停"
                    : "小爱侧未启用，文件里是启用";
        } else {
            title = "小爱在用 " + runtimeModel + "，与文件里的 " + saved.model() + " 不一致";
        }
        if (tvRuntimeTitle != null) tvRuntimeTitle.setText(title);
        if (tvRuntimeDesc != null) {
            tvRuntimeDesc.setText("点按通知小爱重新加载文件；或用「配置编辑 → 从运行中小爱导入」采用运行时");
        }
        bannerRuntime.setVisibility(View.VISIBLE);
    }

    public void hideRuntimeConflict() {
        if (bannerRuntime != null) bannerRuntime.setVisibility(View.GONE);
    }
}
