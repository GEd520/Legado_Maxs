# adb 免点击验证数据驱动功能（书源 / JS 调试页）

> 适用范围：在模拟器/真机上验证「行为由数据触发、结果有 logcat 输出锚点、对应界面是可直起的内部页」这类功能的真实可用性——典型如 Rhino JS 引擎、书源 / 订阅源解析规则（`AnalyzeUrl` / `AnalyzeRule`）、书源调试页的报错渲染。核心思路：**能绕开 UI 就不点 UI**，把「验证」从界面里解耦出来。
> 有效状态：长期有效的技术笔记，工具链不变即持续有效；与被测功能的具体 bug 无关，任何同类数据驱动改动都可用它做回归验证。
> **定位**：本文件是通用 adb 操作手册（怎么跑、命令与坑），与《Web 服务端到端测试方法.md》平级——后者专讲内置 Web 服务（传书 / 备份 / 书架接口）的三层验证，本文讲的是**不经 Web 服务、也不点 UI** 的另一条路径。现成脚本见 [`scripts/book-source-debug-e2e.ps1`](../../scripts/book-source-debug-e2e.ps1)。

## 一、三根支柱：先在源码里找齐，再动手

这是省时间的根本。动手前先在**源码**里定位三样东西，缺一就退化成点 UI：

| 支柱     | 在源码里找什么                      | 本项目对应                                                                       |
| -------- | ----------------------------------- | -------------------------------------------------------------------------------- |
| 输出锚点 | 结果往哪儿打日志（`Log.d(tag, …)`） | `Debug.kt` 的 `Log.d("sourceDebug", safeMsg)`（DEBUG 构建），调试页每行进 logcat |
| 数据入口 | 被测行为由哪份数据驱动              | Room 库 `legado.db` 的 `book_sources` 表（书源规则字段）                         |
| 触发入口 | 目标页能否用 intent 直接拉起        | `BookSourceDebugActivity` 读 `getStringExtra("key")` = 书源 URL                  |

找齐后即可把「注入受控数据 → 直启页面 → 触发行为 → 回读 logcat」串成一条命令，全程零点击。

## 二、操作骨架

```bash
# 0) 前提：装的是 debug 包（可 run-as），且调试页已 exported（app/src/debug/AndroidManifest.xml）
# 1) 停 App 后直写私有库（避免 Room/WAL 与外部写冲突），SQL 走文件别内联
adb -s <serial> shell am force-stop <包名>
adb -s <serial> push inject.sql /data/local/tmp/inject.sql
adb -s <serial> shell "run-as <包名> sh -c 'sqlite3 databases/legado.db < /data/local/tmp/inject.sql'"

# 2) 直接拉起内部调试页并传参（跳过 我的→书源管理→更多→调试 整条导航）
adb -s <serial> shell "am start -n <包名>/<Activity> --es key <书源URL>"
adb -s <serial> shell "dumpsys activity activities | grep topResumedActivity"  # 确认到前台

# 3) 清缓冲后，用键盘把关键词喂进自动聚焦的搜索框（仍零点击），触发一次真实搜索
adb -s <serial> logcat -c
adb -s <serial> shell input text <关键词>
adb -s <serial> shell input keyevent 66   # KEYCODE_ENTER 提交

# 4) 回读结果：同步 dump，别用后台 Job（Job 子宿主不继承 UTF-8，中文会乱码）
adb -s <serial> logcat -d -v brief sourceDebug:V AndroidRuntime:E "*:S"
```

现成脚本封装了以上全流程并自动清理测试数据：

```powershell
pwsh -File scripts/book-source-debug-e2e.ps1 -Serial emulator-5554
```

它注入一个 `searchUrl` 里带多行坏 JS 的受控书源（`@js:` 调不存在的 `java.undefinedMethod`），触发一次搜索，断言 logcat 里出现「源码位置（第 N 行）+ 上下文 + `→` 指针 + `^` 列指示」的增强错误块，退出码即验证结论。

## 三、坑

- **`run-as` 只对 debug 包有效**：release 包不可调试，`run-as` 直接 permission denied。
- **写库前必 `force-stop`**：App 持有 DB 连接 + WAL 时外部写会打架 / 改了不生效。
- **内联 SQL 的引号会被 PowerShell 与设备 shell 双重拆解**报错；一律 `push` 一个 `.sql` 文件再 `sqlite3 < 文件`。
- **`am start` 启动未导出页抛 `SecurityException`**（`not exported from uid`）：本项目靠 `app/src/debug/AndroidManifest.xml` 给 `BookSourceDebugActivity` / `RssSourceDebugActivity` 标 `exported=true` 解决，release 不含该文件、对外仍不可启动。
- **logcat 中文乱码**：PowerShell 用 GBK 解码 adb 子进程 stdout。脚本开头设 `[Console]::OutputEncoding = UTF8` 且改用**同步 `logcat -d`**（后台 `Start-Job` 的子宿主不继承该编码，写出的文件仍是乱码）。
- **`input text` 需要目标输入框已聚焦**：调试页 `SearchView` 在 `onActionViewExpanded` 后自动聚焦，可直接喂字；换成别的页不保证。

## 四、失败回退（唯一需要退到 UI 的情形）

当键盘提交没命中输入框（logcat 里连「开始搜索关键字」都没有）时，才退回操作界面，且**以 UI 树为准、不以截图为准**：

```bash
# 取控件真实坐标：截图像素常 ≠ 设备像素，必须用 dump 出来的 bounds
adb -s <serial> exec-out uiautomator dump /dev/tty   # 找目标节点 bounds=[l,t][r,b]
adb -s <serial> shell input tap <(l+r)/2> <(t+b)/2>  # 先点聚焦，再键盘提交
adb -s <serial> shell input text <关键词>
adb -s <serial> shell input keyevent 66
```

- 截图（`screencap`）只作**最后兜底**（肉眼确认渲染外观 / OCR 提取），因为部分模拟器截图通路会白屏、且截图像素与设备像素不一致导致 tap 偏移；验证结论优先来自 logcat 与 UI 树文本。
- 优先级：**logcat 锚点 > UI 树 bounds > 截图**。能在前两者拿到结论就别碰第三者。
