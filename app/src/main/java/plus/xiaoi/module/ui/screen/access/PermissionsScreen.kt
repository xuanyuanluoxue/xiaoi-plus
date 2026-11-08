package plus.xiaoi.module.ui.screen.access

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import plus.xiaoi.module.core.PermissionProbe
import plus.xiaoi.module.core.PermissionSnapshot
import plus.xiaoi.module.ui.components.PermissionRow
import plus.xiaoi.module.ui.components.PermissionStatus
import plus.xiaoi.module.ui.components.XiaoiCard
import plus.xiaoi.module.ui.components.XiaoiCardGroup
import plus.xiaoi.module.ui.components.XiaoiGroupHeader
import plus.xiaoi.module.ui.components.XiaoiLoadingSkeleton
import plus.xiaoi.module.ui.components.XiaoiPreferenceCard
import plus.xiaoi.module.ui.components.XiaoiTextButton
import plus.xiaoi.module.ui.theme.XiaoiTheme
import top.yukonga.miuix.kmp.basic.Text

private enum class PermissionKind {
    NOTIFICATIONS,
    EXACT_ALARM,
    FULL_SCREEN,
    BATTERY,
    USAGE,
    LISTENER,
}

/**
 * 权限矩阵：展示 PermissionProbe.capture 的真实结论，UNKNOWN 只写“未确认”。
 * 每项提供“去设置”Intent；打不开时退回应用详情，仍失败才提示。
 */
@Composable
fun PermissionsScreen(
    onNavigate: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = XiaoiTheme.colors
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<PermissionSnapshot?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    fun capture() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            snapshot = withContext(Dispatchers.IO) { PermissionProbe.capture(context) }
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { capture() }

    fun open(kind: PermissionKind) {
        val pkg = context.packageName
        val primary = when (kind) {
            PermissionKind.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)

            PermissionKind.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
            } else {
                appDetails(pkg)
            }

            PermissionKind.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
            } else {
                appDetails(pkg)
            }

            PermissionKind.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            PermissionKind.USAGE -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            PermissionKind.LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        if (!start(context, primary) && !start(context, appDetails(pkg))) {
            onMessage("无法打开系统设置，请手动在设置中查找")
        }
    }

    val snap = snapshot
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = XiaoiTheme.dimens.pagePad)
            .padding(top = XiaoiTheme.dimens.pagePadTop, bottom = 24.dp),
    ) {
        if (snap == null) {
            XiaoiLoadingSkeleton(height = 96.dp)
            Spacer(Modifier.height(XiaoiTheme.dimens.cardGap))
            XiaoiLoadingSkeleton(height = 260.dp)
            return@Column
        }

        val missing = snap.missingCount()
        XiaoiCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("检测结果", color = colors.text, style = XiaoiTheme.typography.rowTitle)
                Spacer(Modifier.weight(1f))
                XiaoiTextButton(if (refreshing) "检测中…" else "重新检测") { capture() }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (missing == 0) {
                    "没有明确缺失的权限；未确认项不计入缺失。"
                } else {
                    "$missing 项明确未授权，可按行前往系统设置。"
                },
                color = colors.textSecondary,
                style = XiaoiTheme.typography.body,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "检测时间 ${formatClock(snap.capturedAt)}；未确认表示系统没有可用的标准 API，不当作拒绝。",
                color = colors.textHint,
                style = XiaoiTheme.typography.caption,
            )
        }

        XiaoiGroupHeader("权限")
        XiaoiCardGroup {
            XiaoiPreferenceCard {
                PermissionRow(
                    name = "通知",
                    status = snap.notifications.toUiStatus(),
                    detail = "普通通知与提醒降级需要",
                    onOpenSettings = { open(PermissionKind.NOTIFICATIONS) },
                )
            }
            XiaoiPreferenceCard {
                PermissionRow(
                    name = "精确闹钟",
                    status = snap.exactAlarm.toUiStatus(),
                    detail = if (Build.VERSION.SDK_INT >= 31) {
                        "Android 12+ 需要单独允许"
                    } else {
                        "当前系统版本默认可用"
                    },
                    onOpenSettings = { open(PermissionKind.EXACT_ALARM) },
                )
            }
            XiaoiPreferenceCard {
                PermissionRow(
                    name = "全屏通知",
                    status = snap.fullScreenIntent.toUiStatus(),
                    detail = if (Build.VERSION.SDK_INT >= 34) {
                        "Android 14+ 需单独授权"
                    } else {
                        "当前系统版本无法确认"
                    },
                    onOpenSettings = { open(PermissionKind.FULL_SCREEN) },
                )
            }
            XiaoiPreferenceCard {
                PermissionRow(
                    name = "电池优化",
                    status = snap.batteryOptimization.toUiStatus(),
                    detail = "忽略电池优化后后台更稳定",
                    onOpenSettings = { open(PermissionKind.BATTERY) },
                )
            }
            XiaoiPreferenceCard {
                PermissionRow(
                    name = "使用情况访问",
                    status = snap.usageAccess.toUiStatus(),
                    detail = "读取前台应用需要 usage access",
                    onOpenSettings = { open(PermissionKind.USAGE) },
                )
            }
            XiaoiPreferenceCard {
                PermissionRow(
                    name = "通知使用权",
                    status = snap.notificationListener.toUiStatus(),
                    detail = "读取/取消通知需要单独授权",
                    onOpenSettings = { open(PermissionKind.LISTENER) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "权限由系统 API 实时判定；部分 ROM 会一直返回“未确认”，这是降级而不是故障。",
            color = colors.textHint,
            style = XiaoiTheme.typography.caption,
        )
    }
}

private fun appDetails(pkg: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

private fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (t: Throwable) {
    false
}

private fun PermissionSnapshot.State.toUiStatus(): PermissionStatus = when (this) {
    PermissionSnapshot.State.GRANTED -> PermissionStatus.GRANTED
    PermissionSnapshot.State.DENIED -> PermissionStatus.DENIED
    PermissionSnapshot.State.UNKNOWN -> PermissionStatus.UNKNOWN
    PermissionSnapshot.State.NOT_APPLICABLE -> PermissionStatus.NOT_APPLICABLE
}
