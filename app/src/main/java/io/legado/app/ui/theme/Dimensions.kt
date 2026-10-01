package io.legado.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局 Compose 尺寸令牌（theme-styles.md §7.2）。
 *
 * 这里是 Compose 侧所有 dp 值的唯一定义处：Composable 体内禁止再裸写 `12.dp` / `16.dp`。
 * 原先分散在各 Feature 的 `PageDimens`（精准管理、下载管理等页面就近定义）已并入此处，
 * 新增尺寸一律追加到本对象，不要再另起一个平行对象。
 *
 * 字号不在此定义：文本一律走 `MaterialTheme.typography` 语义样式（theme-styles.md §7.4）。
 */
object AppDimens {

    // ── 通用整页列表 ──

    /** 整页列表（精准管理、下载管理等）的四周内边距 */
    val screenPadding: Dp = 16.dp

    /** 整页列表中卡片之间的纵向间距 */
    val cardSpacing: Dp = 12.dp

    // ── 内嵌 Tab 页（主界面书架 / 发现 / 我的）──

    /** Tab 页分组面板的左右外边距，比整页列表窄，让面板更贴近屏幕边缘 */
    val panelHorizontalPadding: Dp = 12.dp

    /** 相邻分组面板之间的纵向间距 */
    val panelSpacing: Dp = 10.dp

    /** 分组面板首个面板距列表顶部的间距 */
    val panelTopPadding: Dp = 8.dp

    /** 面板行的最小高度，保证命中区不小于无障碍要求的 48dp */
    val panelRowMinHeight: Dp = 60.dp

    /** 面板行内容的水平内边距 */
    val panelRowHorizontalPadding: Dp = 16.dp

    /** 面板行内容的垂直内边距 */
    val panelRowVerticalPadding: Dp = 10.dp

    /** 行内图标尺寸 */
    val panelRowIconSize: Dp = 22.dp

    /** 行内图标与文本之间的间距 */
    val panelRowIconSpacing: Dp = 14.dp

    /** 行内标题与副标题之间的间距 */
    val panelRowTitleSpacing: Dp = 8.dp

    /** 行内尾部控件与文本之间的间距（如开关） */
    val panelRowTrailingSpacing: Dp = 8.dp

    /** 分组标题的上内边距 */
    val panelTitleTopPadding: Dp = 12.dp

    /** 分组标题的下内边距 */
    val panelTitleBottomPadding: Dp = 6.dp

    /** 面板描边与行分隔线的粗细 */
    val dividerThickness: Dp = 1.dp

    // ── 缓存管理页 ──

    /** 缓存卡片的内边距 */
    val manageCardPadding: Dp = 12.dp

    /** 缓存卡片内各行的纵向间距 */
    val manageRowSpacing: Dp = 6.dp

    /** 卡片操作按钮的高度与最小宽度（48dp 同时满足无障碍最小命中区） */
    val manageActionHeight: Dp = 34.dp
    val manageActionMinWidth: Dp = 48.dp

    /** 卡片操作按钮之间的间距 */
    val manageActionSpacing: Dp = 8.dp

    /** 操作按钮的左右内边距 */
    val manageActionPaddingHorizontal: Dp = 10.dp

    /** 禁用状态文字的不透明度（卡片操作按钮与批量按钮共用） */
    const val DISABLED_CONTENT_ALPHA = 0.45f

    /** 缓存列表卡片封面的宽高（3:4） */
    val manageCoverWidth: Dp = 60.dp
    val manageCoverHeight: Dp = 80.dp

    /** 章节弹窗的章节行最小高度 */
    val manageChapterRowHeight: Dp = 52.dp

    /** 顶部 Tab 栏距列表的间距 */
    val manageTabTopPadding: Dp = 10.dp

    // ── 书籍封面（AppBookCover）──

    /** 封面圆角，对齐 View 版 CoverImageView 的 12px 描边圆角 */
    val bookCoverCornerRadius: Dp = 4.dp

    // ── 书架条目（列表 / 网格，尺寸对齐原 item_bookshelf_* 布局）──

    /** 标准列表条目：开启"显示外边框"时的内边距 */
    val shelfItemBorderPadding: Dp = 8.dp

    /** 条目默认内边距 */
    val shelfItemPadding: Dp = 4.dp

    /** 书架列表左右内边距（对齐原 RecyclerView 的 8dp padding） */
    val shelfContentHorizontalPadding: Dp = 8.dp

    /** 首个条目的额外上边距（对齐 View 侧 ItemDecoration 的首行补偿） */
    val shelfFirstItemExtraTop: Dp = 8.dp

    /** 条目外边框粗细 */
    val shelfItemBorderWidth: Dp = 0.8.dp

    /** 外边框填充透明度（对齐 View 侧 bookBorderBackground 的 0.41f） */
    const val SHELF_ITEM_BORDER_FILL_ALPHA = 0.41f

    /** 外边框描边与背景色的混合比例（对齐 View 侧 bookBorderBackground 的 0.22f） */
    const val SHELF_ITEM_BORDER_BLEND = 0.22f

    /** 标准列表封面宽度（高度按书本比例推导） */
    val shelfCoverWidth: Dp = 66.dp

    /** 紧凑列表封面宽度 */
    val shelfCoverWidthCompact: Dp = 48.dp

    /** 封面与文字列的间距 */
    val shelfCoverNameSpacing: Dp = 10.dp

    /** 书名相对文字列的起始内边距 */
    val shelfTitleStartPadding: Dp = 2.dp

    /** 书名与未读角标之间的间距 */
    val shelfBadgeRowSpacing: Dp = 8.dp

    /** 作者/阅读进度/最新章节行的图标尺寸（对齐 archive-main 的 14dp 小图标） */
    val shelfMetaIconSize: Dp = 14.dp

    /** meta 行图标与文字之间的间距 */
    val shelfMetaIconSpacing: Dp = 4.dp

    /** 书名行与首个 meta 行之间的间距（对齐 archive-main 的 6dp） */
    val shelfMetaFirstSpacing: Dp = 6.dp

    /**
     * meta 行之间的额外间距。
     *
     * 行高已显式压紧（见 BookshelfItemParts 的行高常量），行与行不再加 padding，
     * 观感对齐参考分支；紧凑列表的"作者 • 章节"分隔点仍复用它做左右留白。
     */
    val shelfMetaSpacing: Dp = 0.dp

    /** 阅读进度条与上一行、百分比的间距 */
    val shelfProgressSpacing: Dp = 2.dp

    /** 阅读进度条高度 */
    val shelfProgressThickness: Dp = 2.dp

    /** 阅读进度条的下内边距 */
    val shelfProgressVerticalPadding: Dp = 4.dp

    /** 阅读进度百分比与进度条的间距 */
    val shelfProgressPercentSpacing: Dp = 4.dp

    /**
     * 标签胶囊圆角。
     *
     * 对齐原 bg_tag 的 8dp 固定值，不走 composeActionShape：胶囊本体缩小后
     * 9dp 的主题圆角会让小胶囊接近半圆、显得更胖。
     */
    val shelfTagChipCornerRadius: Dp = 8.dp

    /**
     * 标签胶囊的水平内边距。
     *
     * 原 View 版 createTagView 写的是 setPadding(8,4,8,4)，单位是 px（约 2dp/1dp），
     * 这里取 4dp/2dp：比"px 当 dp 用"的原版稍宽松，但明显小于第一版误放的 8dp/4dp。
     */
    val shelfTagChipPaddingHorizontal: Dp = 4.dp

    /** 标签胶囊的垂直内边距 */
    val shelfTagChipPaddingVertical: Dp = 2.dp

    /** 标签胶囊描边粗细（对齐原 bg_tag 的 0.5dp） */
    val shelfTagChipBorderWidth: Dp = 0.5.dp

    /** 标签胶囊之间的水平间距（对齐原 Flexbox 子项 margin 4px×2 ≈ 2dp） */
    val shelfTagChipSpacing: Dp = 2.dp

    /** 标签胶囊换行时的垂直间距（对齐原 margin 2px×2 ≈ 1dp） */
    val shelfTagChipVerticalSpacing: Dp = 1.dp

    /** 标签区与上一行的间距 */
    val shelfTagRowsSpacing: Dp = 2.dp

    /** 简介与上一行的间距 */
    val shelfIntroSpacing: Dp = 4.dp

    /** 网格条目外内边距 */
    val shelfGridItemPadding: Dp = 4.dp

    /** 网格书名与封面之间的间距 */
    val shelfGridNameSpacing: Dp = 6.dp

    /** 列表样式文件夹封面的起始外边距 */
    val shelfFolderCoverStartMargin: Dp = 8.dp

    /** 列表样式文件夹封面的上外边距 */
    val shelfFolderCoverTopMargin: Dp = 8.dp

    /** 列表样式文件夹封面的下外边距 */
    val shelfFolderCoverBottomMargin: Dp = 12.dp

    /** 网格封面上的书名叠层内边距 */
    val shelfGridOverlayNamePadding: Dp = 4.dp

    /** 未读角标：字号以外的内边距 */
    val shelfBadgePaddingHorizontal: Dp = 4.dp

    /** 未读角标垂直内边距 */
    val shelfBadgePaddingVertical: Dp = 1.dp

    /** 未读角标最小尺寸 */
    val shelfBadgeMinSize: Dp = 14.dp

    /** 状态下标（角标 / 加载指示器）距条目边缘的间距 */
    val shelfBadgeMargin: Dp = 5.dp

    /** 列表条目加载指示器尺寸 */
    val shelfListLoadingSize: Dp = 26.dp

    /** 网格条目加载指示器尺寸 */
    val shelfGridLoadingSize: Dp = 22.dp

    /** 加载指示器线宽 */
    val shelfLoadingStrokeWidth: Dp = 2.dp

    /** 读取进度轨道的透明度（对齐 View 侧 25% 强调色轨道） */
    const val SHELF_PROGRESS_TRACK_ALPHA = 0.25f

    /** 封面宽高比：书本封面高度 = 宽度 × 该系数（对齐 CoverImageView 的 4/3） */
    const val BOOK_COVER_ASPECT = 4f / 3f

    // ── 发现页（书源列表 + 展开后的书源分类区）──

    /** 书源条目左右内边距（对齐原 item_find_book 根布局的 16dp） */
    val exploreRowHorizontalPadding: Dp = 16.dp

    /** 书源条目上内边距（对齐原根布局的 12dp；底部为 0，相邻条目间距就是这 12dp） */
    val exploreRowTopPadding: Dp = 12.dp

    /** 书源条目下内边距（原实现只有末项保留 12dp，这里并入列表的底部 contentPadding） */
    val exploreRowBottomPadding: Dp = 12.dp

    /** 书源标题行的左右内边距（对齐原 ll_title 的 10dp） */
    val exploreTitleHorizontalPadding: Dp = 10.dp

    /** 书源标题行的上下内边距（对齐原 ll_title 的 6dp） */
    val exploreTitleVerticalPadding: Dp = 6.dp

    /** 标题行尾部控件（加载圈 / 展开箭头）尺寸 */
    val exploreTitleIconSize: Dp = 20.dp

    /** 标题行尾部控件之间的间距（对齐原 rotate_loading 的 4dp marginRight） */
    val exploreTitleIconSpacing: Dp = 4.dp

    /** 展开加载指示器的线宽（对齐原 RotateLoading 的 1dp 细线） */
    val exploreTitleProgressStroke: Dp = 1.dp

    /** 书源分类区左右内边距（对齐原 8dp 容器 padding + 3dp flexbox padding） */
    val exploreKindsHorizontalPadding: Dp = 11.dp

    /** 分类区与标题行之间的间距（对齐原 flexbox 的 8dp marginTop + 3dp padding） */
    val exploreKindsTopSpacing: Dp = 11.dp

    /**
     * 分类项之间的间距。
     *
     * 对齐原 FlexboxLayout 的实际间隙：子项 margin 3dp×2 + divider 占位 8dp = 14dp。
     * 这个值直接决定断行组成（同一行能放下几个胶囊），不能凭观感取小值。
     */
    val exploreKindSpacing: Dp = 14.dp

    /** 分类项左右内边距（对齐 item_fillet_* 的 12dp） */
    val exploreKindHorizontalPadding: Dp = 12.dp

    /** 分类项上下内边距（对齐 item_fillet_* 的 4dp） */
    val exploreKindVerticalPadding: Dp = 4.dp

    /** usehtml 文本内容的内边距（对齐原 ScrollTextView 的 8dp） */
    val exploreHtmlContentPadding: Dp = 8.dp

    /** usehtml 文本内容的最小高度（对齐原 ScrollTextView 的 minHeight 48dp） */
    val exploreHtmlMinHeight: Dp = 48.dp

    /** usehtml 图片可用宽度在屏幕宽度上的扣减（对齐原实现减去的 48dp 左右留白） */
    val exploreHtmlImageMargin: Dp = 48.dp

    /** useweb 内容加载中的占位高度（对齐原 120dp 的 loading 高度） */
    val exploreWebLoadingHeight: Dp = 120.dp

    /** 标题行背景填充透明度（对齐 bg_find_book_group 的 transparent10 = 6%） */
    const val EXPLORE_TITLE_BG_ALPHA = 0.063f

    // ── 订阅页（RSS 源网格）──

    /** 网格单元的内边距（对齐原 item_rss 根布局的 16dp） */
    val rssGridItemPadding: Dp = 16.dp

    /** 订阅源图标尺寸（对齐原 iv_icon 的 50dp） */
    val rssGridIconSize: Dp = 50.dp

    /** 订阅源图标圆角（对齐原 FilletImageView 的 radius 12dp） */
    val rssGridIconCornerRadius: Dp = 12.dp

    /** 图标与名称之间的间距（对齐原 tv_name 的 marginTop 16dp） */
    val rssGridNameSpacing: Dp = 16.dp

    // ── 弹窗 ──

    /** 单选列表弹窗的选项区最大高度，超出后滚动，避免弹窗顶穿屏幕 */
    val dialogOptionsMaxHeight: Dp = 320.dp

    // ── 通用快速滚动条（VerticalScrollbar）──
    // 对齐参考分支 NG_main 阅读页目录抽屉的快速滚动块（NgLazyListFastScroller 的 FLOATING_HANDLE 变体）：
    // 无常驻轨道，只有一块带上下箭头的浮动拖柄

    /** 拖拽感应区宽度（比拖柄宽，拖柄贴右缘） */
    val scrollbarRailWidth: Dp = 28.dp

    /** 浮动拖柄宽度 */
    val scrollbarHandleWidth: Dp = 24.dp

    /** 浮动拖柄高度：能放下上下两个箭头 */
    val scrollbarHandleHeight: Dp = 56.dp

    /** 浮动拖柄圆角 */
    val scrollbarHandleCornerRadius: Dp = 12.dp

    /** 浮动拖柄描边粗细 */
    val scrollbarHandleBorderWidth: Dp = 0.5.dp

    /** 拖柄内箭头尺寸 */
    val scrollbarChevronSize: Dp = 16.dp

    /** 两个箭头之间的间距 */
    val scrollbarChevronSpacing: Dp = 2.dp

    /** 拖柄上下内缩：拖柄不会贴到列表首尾 */
    val scrollbarVerticalPadding: Dp = 8.dp

    /** 拖柄投阴影高度（墨水屏用 0） */
    val scrollbarHandleShadowElevation: Dp = 2.dp
    val scrollbarHandleShadowElevationEInk: Dp = 0.dp

    /** 拖柄底色掺入主题主色的比例（日间 / 夜间），让拖柄比纯容器色更"活"一点 */
    const val SCROLLBAR_HANDLE_BLEND_LIGHT = 0.06f
    const val SCROLLBAR_HANDLE_BLEND_DARK = 0.12f

    /** 拖柄整体透明度（日间 / 夜间；墨水屏不透明） */
    const val SCROLLBAR_HANDLE_ALPHA_LIGHT = 0.90f
    const val SCROLLBAR_HANDLE_ALPHA_DARK = 0.94f

    /** 拖柄描边色透明度（常规 / 墨水屏） */
    const val SCROLLBAR_HANDLE_BORDER_ALPHA = 0.14f
    const val SCROLLBAR_HANDLE_BORDER_ALPHA_EINK = 0.42f

    /** 拖柄阴影色透明度（日间 / 夜间） */
    const val SCROLLBAR_HANDLE_SHADOW_ALPHA_LIGHT = 0.12f
    const val SCROLLBAR_HANDLE_SHADOW_ALPHA_DARK = 0.28f

    /** 拖柄内箭头颜色相对主色的透明度 */
    const val SCROLLBAR_HANDLE_ICON_ALPHA = 0.86f
}
