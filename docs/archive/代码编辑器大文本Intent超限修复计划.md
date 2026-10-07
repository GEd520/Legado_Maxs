# 代码编辑器大文本 Intent 超限修复计划

> **状态：待实施**。根因已于 2026-10-07 在模拟器（emulator-5554，API 35）实测定位并 100% 复现，本文档可直接作为实施依据。实施完成后把状态行改为「已实施（日期 + 验证结论一句话）」。
> 现象描述：打开大书源（搬山人）的 jsLib 全屏编辑时，应用白屏/黑屏后整体重启，看起来像"内存占用过高被杀"，实际与内存无关。

## 一、根因结论（已实测，勿再从内存方向排查）

### 1.1 机制

`CodeEditActivity` 的全部 12 个调用方都把**待编辑文本全文**放进 Intent extra（`putExtra("text", …)`）启动。文本超过约 25 万字符后，Intent 以 UTF-16 打包出的 Binder 事务超过约 500KB 的实际悬崖，系统抛 `TransactionTooLargeException`，随后**直接 SIGKILL 前台应用进程并重启**。用户看到的白屏/黑屏就是进程被杀到应用重启之间的空档（实测系统重启后还会再试拉起编辑器、再被杀一次，黑屏时间因此更长）。

### 1.2 实测证据（模拟器 100% 复现）

jsLib 为 259,609 字符时（搬山人大源，`https://www.banshanren.com`）：

```
START u0 {cmp=...ui.code.CodeEditActivity (has extras)} with LAUNCH_SINGLE_TASK
ActivityTaskManager: android.os.TransactionTooLargeException: data parcel size 523180 bytes
ActivityManager: Process io.legado.app.yuedu.debug (pid 32533) has died: fg TOP
Zygote: Process 32533 exited due to signal 9 (Killed)
```

- 旧版 jsLib 238,209 字符（≈480KB parcel）→ 正常打开，首帧 `Displayed +208ms`；
- 新版 259,609 字符（523,180 bytes parcel）→ 进程必死；
- 差分对照：同一页面、同一条启动路径，小字段（并发率，6 字符）→ 正常打开、进程存活；jsLib 预览 → 进程必死。**载荷大小是唯一变量**；
- 内存排清：编辑器打开成功时 PSS 仅 +26MB，无 OOM、无 GC 风暴、无掉帧告警；死亡发生在打开后 1 秒内且当时系统空闲内存 3.6GB。sora editor 渲染本身健康（13,603 行 Markdown，最长行 190 字符），**不要动编辑器**。

### 1.3 波及范围

| 方向 | 位置 | 说明 |
| --- | --- | --- |
| 去程（传全文进编辑器） | 12 个文件、15 处，见第五节清单 | 523KB 事务的直接来源 |
| 回程（编辑器保存回传） | `CodeEditActivity.save()`（`CodeEditActivity.kt:167`）+ 9 处接收点 | 回程走同一 Binder 通道，受同一悬崖约束（同一机制，未单独实测，本次一并修，**只修去程是半成品**） |

最易超限的调用点：整源 JSON 编辑（`BaseContentEditDialog.openCodeEditor`，整源 400KB+）、HTTP 响应查看（`HttpDebugScreen` / `CurlTestScreen`）、气泡 SVG（`BubbleManageActivity`）。

## 二、行为定义（验收标准，改完后逐条过）

1. 打开搬山人大源 → 基本页点 jsLib 截断预览 → 进入全屏编辑器，内容完整显示；`pidof io.legado.app.yuedu.debug` 前后不变；logcat 无 `TransactionTooLargeException`；
2. 编辑器内加一行后点保存 → 回到编辑页，jsLib 字段内容包含该行；再次进入编辑器内容保持；
3. 小字段（并发率、请求头等）全屏编辑、保存往返行为与现状完全一致（走原直传路径）；
4. 打开「编辑内容」（整源 JSON，400KB+）→ 正常进入编辑器并可保存返回；
5. 只读打开场景（调试响应查看等）打开正常；
6. 不改内容直接退出 → 行为与现状一致（不产生回传）。

## 三、方案设计

核心思路：**大文本改走进程内 `CacheManager` 内存缓存中转，Intent 只传 key；小文本维持现状直传**。`CodeEditViewModel` 已有 `cacheKey` 读端（`CodeEditViewModel.kt:82-89`），本方案补齐发送端与回程。

### 3.1 新增 `ui/code/CodeEditLauncher.kt`

```kotlin
object CodeEditLauncher {
    // 超过该字符数改走 CacheManager 内存中转。
    // 32k 字符 ≈ 64KB parcel，远离 Binder 事务悬崖（实测 480KB 过、523KB 死）
    private const val VIA_INTENT_MAX_CHARS = 32_000
    private const val CACHE_PREFIX = "codeEditText_"

    /** 启动侧：把待编辑文本放进 Intent；大文本写内存缓存、只传 cacheKey */
    fun putText(intent: Intent, text: String) { /* 见下 */ }

    /** 接收侧：从结果 Intent 取回编辑文本；兼容旧 "text" 直传，大文本读后即删缓存 */
    fun readResultText(result: Intent): String? { /* 见下 */ }
}
```

- `putText`：`text.length > VIA_INTENT_MAX_CHARS` 时 `CacheManager.putMemory(key, text)`（key = `CACHE_PREFIX + UUID.randomUUID()`），intent 放 `putExtra("cacheKey", key)`；否则维持 `putExtra("text", text)`；
- `readResultText`：有 `"text"` 直接返回（小文本旧路径）；有 `"cacheKey"` 则 `CacheManager.getFromMemory(key)` 取出后 `CacheManager.deleteMemory(key)`。

### 3.2 回程：`CodeEditActivity.save()`

`CodeEditActivity.kt:167` 的 `putExtra("text", text)` 替换为同阈值判断：超阈值时 `putMemory` + 只传 `cacheKey`（key 由编辑器侧生成，规则同上）。`cursorPosition` / `fieldKey` / `tabKey` 等小 extra 不动。

### 3.3 读端清理：`CodeEditViewModel.initData`

`cacheKey` 分支（`CodeEditViewModel.kt:82-89`）读取成功后 `CacheManager.deleteMemory(cacheKey)`，防缓存滞留。读取失败的现有错误分支（toast「未获取到查看文本」）保持——它是进程在 put 与 read 之间死亡时的降级表现，优于现状。

### 3.4 已核实的支撑事实（实施时不必再查）

- `CacheManager`（`help/CacheManager.kt`）：内存层是 50MB 上限的 `LruCache`（`memoryLruCache`），`putMemory` / `getFromMemory` / `deleteMemory` 齐全；260KB 字符串占比极小，put→read 窗口毫秒级，无需加防驱逐机制；
- `CodeEditActivity` 在 manifest 中为 `launchMode="singleTask"`（`AndroidManifest.xml:243`）。

### 3.5 明确不做（范围外）

- 不动 sora editor / `CodeEditActivity` 的加载与渲染逻辑（实测健康）；
- 不动 `singleTask` + `onNewIntent` 的存量语义（编辑器已在栈顶时再次拉起不重读新参属存量行为，如发现一并记 follow-up，不在本次修）；
- 不为其他页面（非 CodeEditActivity）的大对象 Intent 传递做顺手重构，发现问题走 follow-up 报告。

## 四、实施步骤（checklist）

1. 通读本计划与 `CodeEditActivity` / `CodeEditViewModel` / `CacheManager` 现状；
2. 新增 `ui/code/CodeEditLauncher.kt`；
3. 改 `CodeEditActivity.save()` 回程（3.2）；
4. 改 `CodeEditViewModel.initData` 读后删（3.3）；
5. 按第五节清单替换 15 处去程 + 9 处回程接收；
6. 编译验证：`gradlew :app:compileAppMaxDebugKotlin`（**多变体下不要用 `compileDebugKotlin`**，会因 flavor 歧义失败）；
7. 模拟器过第二节全部条目（见第六节操作要点）；
8. `gradlew lint`（CI 同款门禁）；
9. 按 `docs/project-rules/update-log-rules.md` 更新 `app/src/main/assets/web/help/md/updateLog.md`（用户可见 bug 修复）；
10. 走 code-review（Standards + Spec 两轴）；
11. 提交：中文 Conventional Commits（建议 `fix(编辑器): 大文本改走内存中转修复启动被杀`，可按实际拆分）；本文件状态行更新为已实施。

## 五、调用点清单

### 5.1 去程（15 处，`putExtra("text", …)` → `CodeEditLauncher.putText(intent, …)`）

| 文件 | 行 | 场景 |
| --- | --- | --- |
| `ui/book/source/edit/BookSourceEditActivity.kt` | 369 | 截断预览字段点开（openFullEdit，本次事故路径） |
| 同上 | 395 | 聚焦字段后点工具栏全屏编辑 |
| `ui/rss/source/edit/RssSourceEditActivity.kt` | 224 | 聚焦字段后点工具栏全屏编辑 |
| 同上 | 258 | 截断预览字段点开 |
| `ui/widget/dialog/BaseContentEditDialog.kt` | 168 | 对话框内容转全屏编辑（含**整源 JSON**，400KB+） |
| `ui/dict/rule/DictRuleEditDialog.kt` | 80 | 词典规则编辑 |
| `ui/replace/edit/ReplaceEditActivity.kt` | 97 | 替换规则编辑 |
| `ui/config/CoverRuleConfigDialog.kt` | 109、118 | 封面规则编辑（两处） |
| `ui/config/BubbleManageActivity.kt` | 378 | 气泡 SVG 编辑（可能很大） |
| `ui/config/ShareNoteTemplateManageActivity.kt` | 286 | 共享笔记模板 HTML（可能很大） |
| `ui/book/read/config/HttpTtsEditDialog.kt` | 125 | TTS 源码编辑 |
| `ui/book/read/config/TtsDebugActivity.kt` | 221 | TTS 调试源码 |
| `ui/debug/HttpDebugScreen.kt` | 226 | HTTP 调试响应查看（响应体可能很大） |
| `ui/debug/CurlTestScreen.kt` | 412 | Curl 测试响应查看（响应体可能很大） |

### 5.2 回程接收（9 处，`getStringExtra("text")` → `CodeEditLauncher.readResultText(result)`）

| 文件 | 行 |
| --- | --- |
| `ui/book/source/edit/BookSourceEditActivity.kt` | 211 |
| `ui/rss/source/edit/RssSourceEditActivity.kt` | 193 |
| `ui/widget/dialog/BaseContentEditDialog.kt` | 68 |
| `ui/dict/rule/DictRuleEditDialog.kt` | 65 |
| `ui/replace/edit/ReplaceEditActivity.kt` | 80 |
| `ui/config/CoverRuleConfigDialog.kt` | 44 |
| `ui/config/BubbleManageActivity.kt` | 110 |
| `ui/config/ShareNoteTemplateManageActivity.kt` | 79 |
| `ui/book/read/config/HttpTtsEditDialog.kt` | 110 |

无回程接收的调用点（`TtsDebugActivity` / `HttpDebugScreen` / `CurlTestScreen`）为只读打开，实施时逐一确认 `writable` 语义，只改去程。

## 六、验证操作要点（模拟器免点击路径）

前置：`adb` 连接模拟器，安装 appMax debug 包（`gradlew installAppMaxDebug`），导入搬山人大源（jsLib 259,609 字符）。

- **直启通道**：`CodeEditActivity` 已在 debug 构建导出（`app/src/debug/AndroidManifest.xml`），可 `adb shell am start -n io.legado.app.yuedu.debug/io.legado.app.ui.code.CodeEditActivity --es title 测试 --es text "<文本>"` 免 UI 直起编辑器；但 shell 单参数上限约 128KB 且多行文本转义繁琐，jsLib 级大文本的完整验证仍走下方 UI 路径；

- **入口路径**（实测记录）：我的 → 书源管理 → 搬山人大条目「编辑」→ 基本页下滑到 jsLib 截断预览 → 点击预览文本进入全屏编辑；
- **坐标不要照抄截图**：控件位置会变，用 `adb shell uiautomator dump /sdcard/window.xml` 拿 bounds 后取中心点点击（Git Bash 下 adb 参数路径加 `MSYS_NO_PATHCONV=1` 防 `/sdcard/...` 被转成本地路径）；
- **进程存活断言**：点击前后各跑一次 `adb shell pidof io.legado.app.yuedu.debug`，两者相同才算过；
- **异常断言**：`adb logcat -c` 后操作，回读 `adb logcat -d | grep -E "TransactionTooLarge|CodeEditActivity"`，不得出现 `TransactionTooLargeException`，且应出现 `Displayed ...CodeEditActivity`；
- **对照项**：小字段（并发率）聚焦后点工具栏全屏编辑按钮，回归往返正常；
- 参考方法论：`docs/architecture/adb免点击验证数据驱动功能.md`。

## 七、本次排查的原始证据（存档备查）

- 复现环境：MuMu 模拟器镜像（mayfly / houdini ARM 转译），Android 15（API 35），应用 `io.legado.app.yuedu.debug`；
- 关键日志：`TransactionTooLargeException: data parcel size 523180 bytes`（去程，出现两次——系统重启进程后重试拉起编辑器再次失败）；`Process ... has died: fg TOP` + `signal 9`；旧版源（238,209 字符）同路径 `Displayed +208ms` 正常；
- 内存证据：编辑器打开成功 PSS 283MB → 309MB（+26MB），死亡时系统 `MemFree 3.6GB`，内核无 OOM 记录；
- jsLib 形态：259,609 字符 / 13,603 行 / 最长行 190 字符（中文 Markdown 文档，非压缩单行 JS），排除"超长单行渲染"假设。
