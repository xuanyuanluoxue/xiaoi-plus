package plus.xiaoi.module.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import plus.xiaoi.module.ui.theme.XiaoiTheme

/**
 * 加载骨架：150ms 延迟出现，避免闪一下；不使用旋转 spinner。
 * 只占位，不负责无障碍朗读。
 */
@Composable
fun XiaoiLoadingSkeleton(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(150)
        visible = true
    }
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                XiaoiTheme.colors.surface2.copy(alpha = 0.7f),
                RoundedCornerShape(12.dp),
            ),
    )
}
