package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.theme.AppDimens

/**
 * 屏蔽规则进度悬浮芯片（发现列表页与新版发现/订阅共用）：
 * 显示当前被屏蔽的数量，点击打开屏蔽规则配置。
 *
 * 由调用方决定摆放位置（通常对齐内容区 TopEnd）。
 */
@Composable
fun BlockProgressChip(
    blockedCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AppDimens.exploreShowBlockChipCornerRadius),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = AppDimens.exploreShowBlockChipShadowElevation,
        onClick = onClick
    ) {
        Text(
            text = stringResource(R.string.explore_block_rule_progress_text, blockedCount),
            modifier = Modifier.padding(
                horizontal = AppDimens.exploreShowBlockChipPaddingHorizontal,
                vertical = AppDimens.exploreShowBlockChipPaddingVertical
            ),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontSize = 13.sp
        )
    }
}
