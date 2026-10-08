# Intent 载荷大小红线（Binder 事务超限）

> 适用范围：所有通过 `Intent` / `Bundle` 跨进程或跨 Activity 传递数据的代码，尤其是把**大文本 / 大对象**放进 `putExtra` 的场景。
> 本文件是红线，Code Review 以此为准。核心结论：**超过约 3.2 万字符的文本禁止直接塞进 Intent。**

## 一、问题：Intent 大载荷会直接杀掉前台进程

Android 组件间 `Intent`/`Bundle` 的 extras 最终通过 **Binder 事务**在进程间传输。Binder 事务缓冲区是**进程级共享、约 1MB** 的环形缓冲，实测有效悬崖更低——**单个事务超过约 500KB 就会抛 `TransactionTooLargeException`**。

关键点：**这个异常不是普通可捕获的崩溃**。在 `startActivity` / `setResult` 路径上它会导致系统**直接 `SIGKILL` 前台进程**（`Process ... has died: fg TOP` + `signal 9`），随后应用整体重启。用户看到的是**白屏/黑屏一下再回到桌面**，很容易被误报成"内存占用过高被杀"——**实际与内存无关**（实测打开成功时 PSS 仅 +26MB、系统空闲 3.6GB）。

> 排查提示：报"打开某大内容就闪退/黑屏重启"时，先看 logcat 有没有 `TransactionTooLargeException: data parcel size NNNN bytes`，不要一头扎进 OOM / GC / 内存方向。

## 二、红线规则

1. **禁止把可能很大的文本 / 序列化对象直接 `putExtra` 进 Intent**。已知高危来源：
   - 全屏代码编辑器（`CodeEditActivity`）待编辑的整段文本；
   - 整源 JSON（书源/订阅源「编辑内容」，400KB+）；
   - HTTP / Curl 调试响应体；气泡 SVG、分享笔记模板 HTML 等用户可自定义的大内容。
2. **大文本改走进程内内存缓存中转，Intent 只传一个 key**。编辑器场景统一走 [`CodeEditLauncher`](../../app/src/main/java/io/legado/app/ui/code/CodeEditLauncher.kt)：
   - `putText(intent, text)`：`text.length > 32_000` 时 `CacheManager.putMemory(key, text)`、只 `putExtra("textCacheKey", key)`；否则维持 `putExtra("text", text)` 直传。
   - `readText(intent)`：优先读 `"text"` 直传，否则读 `"textCacheKey"` 并**读后即删**缓存。
3. **去程和回程都要收口**。回程（`setResult` 回传编辑结果）走同一条 Binder 通道、受同一悬崖约束，**只修去程是半成品**。`CodeEditActivity.save()` 与所有结果接收点必须对称使用 `putText` / `readText`。
4. **新增调用 `CodeEditActivity` 的页面，禁止再手写 `putExtra("text", …)` / `getStringExtra("text")`**，一律经 `CodeEditLauncher`。

## 三、为什么阈值取 32000

32,000 字符以 UTF-16 打包 ≈ 64KB parcel，远低于实测悬崖（≈480KB 过、523KB 死）。取保守值留出安全余量，也为 `setResult` 回传时同一 Intent 里其它 extras（`cursorPosition` / `fieldKey` / `tabKey` 等）预留空间。

## 四、注意事项 / 边界

- **别和只读预览的 `cacheKey` 混用**：`CodeEditViewModel` 里既有的 `"cacheKey"` 分支会强制 `writable = false`（`TextDialog` 只读预览在用）。可编辑大文本必须走独立 key `textCacheKey` 以保持 `writable = true`，否则编辑器会变只读、无法保存回传。
- **`singleTask` 复用实例的缓存滞留**：`CodeEditActivity` 是 `singleTask` 且无 `onNewIntent` 重读，编辑器已在栈顶被再次拉起时不重读新参，新写入的内存条目不会被读后即删（最多 260KB/次，落在 50MB LRU 内可回收）。属存量语义，量大时留意。
- **内存中转不是持久化**：`CacheManager.putMemory` 只在同进程内存存活期间有效，进程被杀即失效——读取失败的降级分支（toast「未获取到查看文本」）要保留。
- **`adb shell am start` 的 shell 参数上限约 128KB**，无法用它复现超长文本崩溃；超长场景验证走真实 UI 路径（见 `docs/architecture/adb免点击验证数据驱动功能.md`）。

## 五、参考

- 根因分析与实施记录：`docs/archive/代码编辑器大文本Intent超限修复计划.md`
- 中转实现：`app/src/main/java/io/legado/app/ui/code/CodeEditLauncher.kt`
- 内存层：`app/src/main/java/io/legado/app/help/CacheManager.kt`（`putMemory` / `getFromMemory` / `deleteMemory`，50MB `LruCache`）
