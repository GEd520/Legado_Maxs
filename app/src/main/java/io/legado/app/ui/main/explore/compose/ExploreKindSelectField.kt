package io.legado.app.ui.main.explore.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.legado.app.R
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.ui.theme.AppDimens
import io.legado.app.ui.theme.composeActionShape

/**
 * 下拉选择类分类项（`type: select`）。
 *
 * 外观对齐原 `item_fillet_selector_single.xml`：强调色的名称 + 当前选中项（原实现是 Spinner）。
 * 选中值写在 infoMap 的 `title` 键上，切换后执行 `action` 脚本。
 */
@Composable
internal fun ExploreKindSelectField(
    modifier: Modifier = Modifier,
    kind: ExploreKind,
    sourceUrl: String,
    controller: ExploreKindsController,
    onSelected: ((String) -> Unit)? = null,
) {
    val name by rememberKindName(sourceUrl, kind, controller)
    // 候选项按 chars 记忆：书源切换"模式"后"平台"的候选列表会整体换掉（其余字段可能一样），
    // 以候选自身为 key 才能保证下拉里拿到的是最新一版；同时省掉每次重组重新过滤
    val chars = remember(kind.chars) { kind.charsOrDefault() }
    val infoMap = remember(sourceUrl, controller) { controller.infoMap(sourceUrl) }
    var selected by remember(infoMap, kind.title, chars) {
        // 对齐原实现：已存值优先；不在当前候选里时收口到首项（原 setSelectionSafely 的 coerce 行为）
        val saved = infoMap[kind.title].takeUnless { it.isNullOrEmpty() }
        mutableStateOf((saved ?: (kind.default ?: chars[0])).takeIf { it in chars } ?: chars[0])
    }
    var expanded by remember(kind) { mutableStateOf(false) }
    // 只在 infoMap 还没有值时才落默认值：原实现同样不改写已有值，
    // 这样切到别的模式再切回来，用户上次选的项还在（越界值只影响显示，不污染规则读取）
    LaunchedEffect(kind.title, chars) {
        if (infoMap[kind.title].isNullOrEmpty()) {
            infoMap[kind.title] = kind.default ?: chars[0]
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(composeActionShape())
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { expanded = true }
                .padding(
                    horizontal = AppDimens.exploreKindHorizontalPadding,
                    vertical = AppDimens.exploreKindVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 值区域复刻 View 版 Spinner：文本居中 + 下方下划线，三角在行尾
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppDimens.exploreSelectValueSpacing)
            ) {
                Column {
                    Text(
                        text = selected,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(AppDimens.dividerThickness)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    )
                }
            }
            // 装饰图标：点击目标是有 semantics 的整行，图标本身不需要单独描述
            Image(
                painter = painterResource(R.drawable.ic_arrow_drop_down),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.width(AppDimens.exploreTitleIconSize),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            chars.forEach { char ->
                DropdownMenuItem(
                    text = { Text(text = char, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        expanded = false
                        if (char == selected) return@DropdownMenuItem
                        selected = char
                        infoMap[kind.title] = char
                        kind.action?.takeIf { it.isNotBlank() }?.let {
                            controller.evalAction(sourceUrl, it, kind.title)
                        }
                        onSelected?.invoke(char)
                    },
                )
            }
        }
    }
}
