# Compose UI 规范 — 迁移、Review Checklist 与典型违规示例

> 原 `UI-ARCHITECTURE.md`（2026-08-19）拆分产物：§13、§14、§17，章节编号沿用原编号，跨文件引用按「文件名 §编号」格式书写。生效范围、执行方式、老代码策略等通用约定见 [README.md](./README.md)。
> **最后更新**：2026-09-03

---

## 13. 老代码迁移规范（分阶段）

### 阶段一：标记（当前 Sprint）

对违反规范的存量代码做标记，不打回，但必须挂 annotation + TODO：

```kotlin
@Suppress("LegadoUiViolation")
@Composable
private fun ThemeAddBottomBar(...) { ... }
// TODO(#issue号): 迁移到 ui/config/widget/ConfigAddBottomBar.kt，统一 Dim. 和 Color
```

**要求**：`LegadoUiViolation` Suppress 必须伴随具体 TODO，否则 PR 打回。（注：该注解没有对应的 lint 规则，现阶段仅作为人工 Review 的标记使用，见 §14.1 说明。）

### 阶段二：清理（次 Sprint）

- 每个 Feature 迭代时，顺手重构本 Feature 内标记过的老代码。
- 通用组件层（`ui/widget/components/`、各模块 `widget/`）优先清理。

### 阶段三：固化（第三 Sprint）

- 新增代码零容忍，禁止新增 `LegadoUiViolation` Suppress。
- 存量未清理的老代码持续挂 TODO 追踪。

---

## 14. Code Review Checklist

分两层设计：**机器项（CI 硬卡）、人工项（Review）**。注意：机器项的强制机制**尚未建立**（见 §14.1 目标态声明），现阶段表中所有条目一律按 §14.2 人工对照执行。

### 14.1 [机器] CI 硬卡（违规 = 构建红，不依赖 Reviewer 心情）

| 规则                                                                                                                | 实现方式                                                                                 |
| ------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| 裸 `colorResource(R.color.xxx)`、硬编码 `Color(0xFF...)`                                                            | 自定义 lint 规则（白名单：`Color.Transparent` / 纯黑 / 纯白，见 `theme-styles.md` §7.1） |
| Composable 体内裸魔法数字 `16.dp`、`12.dp`、`0.8f`                                                                  | 自定义 lint 规则（标准动画参数豁免，见 `theme-styles.md` §7.2）                          |
| Composable 的 `Modifier` 非第一参数                                                                                 | Compose 官方 Lint Checks（`androidx.compose:compose-lint-checks` 依赖）                  |
| `GlobalScope`、构造器/Factory 之外 `new Repository()`                                                               | Detekt `ForbiddenMethodCall` + 自定义规则（`new Repository` 需 AST 级检测）              |
| `collectAsState()` 替代 `collectAsStateWithLifecycle()`                                                             | 自定义 lint 规则                                                                         |
| `Channel<Event>` 用 RENDEZVOUS / BUFFERED                                                                           | 自定义 lint 规则（仅允许 UNLIMITED / CONFLATED，见 `state-events.md` §4.1）              |
| `Log.e` / `Log.w` 缺异常对象第三参数                                                                                | 自定义 lint 规则                                                                         |
| Composable 内硬编码中文字符串（含 `contentDescription` 参数，见 `theme-styles.md` §7.5 / `accessibility.md` §15.1） | lint 硬编码字符串检测（白名单：Log、TODO、Preview）                                      |
| `tween` / `delay` 魔法数字时长不在三档（150/300/450）且未引用 `AnimationSpecs` 常量（`theme-styles.md` §7.6.1）     | 自定义 lint 规则（常量引用豁免）                                                         |
| `rememberInfiniteTransition` 缺 `label` 参数（`theme-styles.md` §7.6.3）                                            | 自定义 lint 规则                                                                         |
| `spring(...)` 调用点散落自定义 stiffness / dampingRatio（`theme-styles.md` §7.6.1）                                 | 自定义 lint 规则（仅允许引用统一 SpringSpec 常量）                                       |
| Screen 函数参数超过 5 个                                                                                            | Detekt `LongParameterList`（按文件类型设阈值）                                           |
| 阶段三起新增 `@Suppress("LegadoUiViolation")`                                                                       | CI grep，直接挂                                                                          |
| 新增 `*ViewModel.kt` 但无对应 `*ViewModelTest.kt`（`testing.md` §16.4）                                             | CI 脚本对比 src/test 路径                                                                |

> **目标态声明**：上表「实现方式」列描述的是目标态——机器规则统一维护在 `tools/lint-rules/` 独立模块并纳入 CI。该模块**当前尚未建立**（Detekt、compose-lint-checks 也未接入），表中规则现阶段一律按人工 Review 对照执行，不构成 CI 硬卡。规则模块落地后本表才成为构建期强制依据，届时「修改本表的 PR 必须同步更新规则代码」。

### 14.2 [人工] Reviewer 对照（任一 ❌ 打回）

- [ ] 新 Screen 是否套了 `ConfigManageScaffold` / `AppScaffold` 等通用脚手架？还是裸 `Scaffold`？（页面级 / 内嵌片段的判定依据见 §14.3）
- [ ] 回调命名是否 `onXxx` 风格？
- [ ] 有无新增 `private fun` 形式的可复用组件？（Screen 内私有且仅在当前文件使用两次以上 = 违规）
- [ ] ViewModel 是否直接调用 `clipboardManager` / `startActivity` / `toast`？
- [ ] 事件是否走 `Channel<Event>` 而非直接平台调用？消费侧是否用 `repeatOnLifecycle(STARTED)` 绑定，而非裸 launch？（`state-events.md` §4.4）
- [ ] Dialog / BottomSheet 是否由 `UiState` 条件渲染？有无 Dialog 被注册成导航路由、显隐开关散落 `mutableStateOf`？返回键拦截是否覆盖？（`state-events.md` §4.5）
- [ ] 新文件是否按目录结构规范落到了正确的包？
- [ ] 注释是否只解释了 "Why" 没有解释 "What"？
- [ ] `ui/widget/components/` 下新增组件是否附带 `@Preview`？
- [ ] `LazyColumn` / `LazyRow` / `LazyVerticalGrid` 的 `items()` 是否传入了 `key`？
- [ ] item 模型类是否加了 `@Immutable`，且标注的类无 `MutableList` / `MutableMap` 等可变集合字段？
- [ ] 需要跨进程重建存活的用户输入状态是否用了 `rememberSaveable`？
- [ ] 图片加载是否走统一的 Glide 封装组件（`AppImage` 等）+ 显式 `override` 尺寸？有无手写 decode 或第二套图片框架混入？
- [ ] `LazyColumn` / `LazyRow` item 里有无 bitmap 像素级处理（缩放/圆角）？
- [ ] 路由字符串是否集中定义？调用点有无散落的路由字面量 / `savedStateHandle` 裸读？
- [ ] 动画属性是否走 `graphicsLayer` / `Modifier.alpha` / `Modifier.scale`？有无逐帧动 layout 属性（`animateDpAsState` 改 height 之类）？（`theme-styles.md` §7.6.2）
- [ ] 无限动画离开 composition 是否即停？有无 `LaunchedEffect` 里手写帧循环？（`theme-styles.md` §7.6.3）
- [ ] 可交互 `Icon` 的 `contentDescription` 是否非空且走 `stringResource`？装饰图标是否用 `Image` 而非 `Icon`？（`accessibility.md` §15.1）
- [ ] 卡片 / 列表项组件根容器是否 `semantics(mergeDescendants = true)`？（`accessibility.md` §15.2）
- [ ] 可点击区域是否 ≥ 48×48 dp（小图标是否用 padding / `minimumInteractiveComponentSize` 撑命中区）？（`accessibility.md` §15.3）
- [ ] 有无硬编码 `.sp`？有无固定高度容器硬裁文字？（`accessibility.md` §15.4）
- [ ] 新增 ViewModel / 修改状态机的 PR 是否带对应单测？ViewModel 测试是否 `runTest` + Turbine，有无 `runBlocking` / 手写 collect / `Thread.sleep` / `@Ignore`？（`testing.md` §16）

### 14.3 脚手架判定依据：`AppScaffold` 的适用范围

`AppScaffold`（`ui/widget/components/AppScaffold.kt`）把 `containerColor` 设为 `Transparent`、`contentWindowInsets` 设为 0，**依赖外层 `LegadoBackgroundBox` 兜底背景**。因此适用范围以宿主为准：

| 场景       | 宿主特征                                                                                                               | 结论                                                                |
| ---------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| 页面级界面 | `BaseComposeActivity` 子类，或手动调用 `setLegadoContent`（外层有背景兜底）                                            | ✅ 必须套 `AppScaffold` / `ConfigManageScaffold`，禁止裸 `Scaffold` |
| 内嵌片段   | 挂在 View 体系里的 `ComposeView`，外层无背景兜底（例：`HomepageScreen`、`BookshelfTagManageScreen`、`DebugLogScreen`） | ❌ 保持裸 `Scaffold` + 不透明容器；透明容器会露出窗口默认背景       |

**系统栏适配责任在内容侧**：`contentWindowInsets = 0` 只影响 content，与顶栏是否延伸到状态栏无关（顶栏由 `TopAppBar` 自身的 `windowInsets` 决定）。改用 `AppScaffold` 后必须补底部内边距：

- 滚动列表（LazyColumn / LazyRow 等）：`contentPadding` 底部补导航条高度（用 `navigationBarBottomInset`，见 `AppScaffold.kt`），内容滚动时可从导航条下穿过（铺满），最后一项能滚上来完整可点
- 主界面 Tab 内容（挂在 `MainActivity` 悬浮底栏之上，如书架 / 我的 / 新版发现 / 新版订阅）：底部内边距必须并入宿主下发的 `bottomPaddingPx`（`MainActivity.mainContentBottomPadding()` = 底栏高度 + 底栏下边距），只补 `navigationBarBottomInset` 时列表末尾会被悬浮底栏压住；同一 Compose 内容既要用于独立页又要嵌入主界面时（如发现列表页的三/五种布局），取 `maxOf(navigationBarBottomInset, bottomPaddingPx)`
- 整页可滚动列（Column + verticalScroll）：容器保持铺满，在内容末尾加 `Spacer(Modifier.navigationBarsPadding())`
- 底部有固定控件（bottomBar / 固定按钮 / 输入框）：容器保持铺满，只给该控件加 `Modifier.navigationBarsPadding()`

> 依据：`api-compat-rules.md` §5 Edge-to-Edge 强制（targetSdk 35+）。

---

## 17. 附录：典型违规示例

### 违规 A：Screen 内私有组件直接写死 dimens 和 R.color

```kotlin
// ❌ 违规
@Composable
private fun ThemeAddBottomBar(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp), // 魔法数字
        color = colorResource(R.color.background_add_button), // 绕过主题
        border = BorderStroke(1.dp, colorResource(R.color.border_add_button))
    ) { ... }
}
```

**正确做法**：

```kotlin
// ✅ 放到 ui/config/widget/ConfigAddBottomBar.kt，或至少用 AppDimens/MaterialTheme
Surface(
    shape = AppShapes.small,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
)
```

### 违规 B：跨 Feature 引用私有组件

```
❌ ui/config/shelf/ShelfScreen.kt
   import io.legado.app.ui.config.theme.manage.components.ThemeCard

// ThemeCard 是 config/theme/manage 的私有组件，book 包不能引用它
// 如果确实需要跨 Feature 复用，必须提升到 ui/widget/components/ 或 ui/config/widget/

✅ ui/config/shelf/ShelfScreen.kt
   import io.legado.app.ui.widget.components.ThemeCard   // 已提升到全局
   // 或
   import io.legado.app.ui.config.widget.ThemeCard   // 已提升到模块 widget
```

### 违规 C：ViewModel 直接持有平台服务

```kotlin
// ❌ 违规：ViewModel 直接写剪贴板
fun copyItem(item: ThemeItem) {
    val json = GSON.toJson(item.config)
    clipboardManager.setPrimaryClip(ClipData.newPlainText(null, json))
}
```

**正确做法**：

```kotlin
// ✅ ViewModel 只发事件
fun copyItem(item: ThemeItem) {
    _events.trySend(ThemeEvent.CopyJson(GSON.toJson(item.config)))
}

// ✅ Activity 收集事件，执行平台操作
when (event) {
    is ThemeEvent.CopyJson -> {
        clipboardManager.setPrimaryClip(ClipData.newPlainText(null, event.json))
        toast("已复制")
    }
}
```

### 违规 D：Screen 函数参数超过 5 个

```kotlin
// ❌ ThemeManageScreen 参数 11 个，超过本文件 §14 Checklist 的 5 个上限
fun ThemeManageScreen(
    onBackClick: () -> Unit,
    onImportFromClipboard: () -> Unit,
    onImportEmpty: () -> Unit,
    onImportFailed: () -> Unit,
    onSelectImage: () -> Unit,
    onShareJson: (String) -> Unit,
    onDeleteConfirm: () -> Unit,
    onToast: (Int) -> Unit = {},
    onToastMsg: (String) -> Unit = {},
    onColorClick: (String, String) -> Unit = { _, _ -> },
    onBlurClick: (Int) -> Unit = {}
)
```

**正确做法**：抽 Navigation Contract 或 Args 数据类。

```kotlin
data class ThemeManageNavArgs(
    val onToast: (Int) -> Unit = {},
    val onToastMsg: (String) -> Unit = {},
    // ...
)

@Composable
fun ThemeManageScreen(
    viewModel: ThemeManageViewModel,
    onBackClick: () -> Unit,
    args: ThemeManageNavArgs
)
```

---
