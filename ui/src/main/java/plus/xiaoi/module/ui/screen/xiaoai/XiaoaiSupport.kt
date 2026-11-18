package plus.xiaoi.module.ui.screen.xiaoai

import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import plus.xiaoi.module.application.runtime.AppRuntime

/**
 * 小爱/模型页共用的 IO 与格式化。
 *
 * 后台任务统一走 [AppRuntime.tasks]（AppExecutors 的端口，WP11）：表现层不再
 * 直接引用 `AppExecutors`，`:ui` 也就不依赖 `:platform`。
 * 结果回到调用方协程（主线程）后再写 Compose 状态。
 */
internal suspend fun <T> AppRuntime.xiaoaiIo(block: () -> T): T = suspendCancellableCoroutine { cont ->
    tasks.io {
        try {
            cont.resume(block())
        } catch (t: Throwable) {
            cont.resumeWith(Result.failure(t))
        }
    }
}

/** 端点 host（带端口）；解析失败时退回原串，不抛异常。 */
internal fun hostOf(url: String): String {
    val text = url.trim()
    if (text.isEmpty()) return ""
    val afterScheme = if (text.contains("://")) text.substringAfter("://") else text
    return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
}

/** 时间戳 → HH:mm:ss；0/负数显示“—”，不伪造时间。 */
internal fun formatClock(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return runCatching {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }.getOrDefault("—")
}

/** 页面调用壳的返回：不新增签名，只借用 Activity 的返回分发器。 */
internal fun goBack(activity: ComponentActivity?) {
    runCatching { activity?.onBackPressedDispatcher?.onBackPressed() }
}
