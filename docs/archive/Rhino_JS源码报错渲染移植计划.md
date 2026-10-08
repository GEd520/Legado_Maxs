# Rhino JS 源码报错渲染移植

> 状态：**已实现并真机验证通过**（commit `bebbe392f`）。
> 归档说明：文件名沿用早期方案占位，实际内容为 Rhino JS 源码报错上下文渲染移植。

## 目标

复刻 legadoT 的效果：书源 JS 执行报错时，错误消息里带出出错行及其上下文源码，用 `→` 标记错误行、`^` 精确指向出错列。

```
com.script.ScriptException: ...EvaluatorException: 找不到方法 "..."。 (<script-5>#196)

  源码位置（第 196 行）:
  ────────────────────────────────────────────────────────────
  194 │ function qdGetDoc(url, kind) {
  195 │     url = qdUrl(url, "https://www.qidian.com/");
→ 196 │     var doc = qdDoc(java.ajax(url, 15000), url);
        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
  197 │     var selector = ...
  ────────────────────────────────────────────────────────────
```

## 原理（legadoT 实现位置）

全部在 `modules/rhino/src/main/java/com/script/rhino/`：
- `RhinoScriptEngine` 用 `sourceCache: LruCache<String,String>` + `sourceId: AtomicLong` 缓存源码全文，key 为 `<script-N>`。
- eval/compile 前 `registerSource()` 缓存源码并返回 `sourceName`；执行改用 `cx.evaluateString(scope, source, sourceName, 1, null)`。
- 捕获 `RhinoException` 时 `createScriptException(re, source, sourceName)` 按 `re.sourceName()` 取回源码，调 `buildEnhancedErrorMessage(baseMsg, source, line, column)` 拼出增强消息，塞进 `ScriptException.message`。
- 增强消息随异常链上抛，源调试视图里 `it.stackTraceStr` 会原样打印——无需改 app 层。

注意：当前项目 rhino 用的是 `org.mozilla.javascript.*`（legadoT 用 `org.htmlunit.corejs.javascript.*`），且 scope 类型是 `Scriptable`（legadoT 是 `VarScope`）。`RhinoException` 在两边都有 `lineNumber()/columnNumber()/sourceName()`，移植可行。

---

## Task 1：给 RhinoScriptEngine 增加源码缓存与增强异常工具

文件：`modules/rhino/src/main/java/com/script/rhino/RhinoScriptEngine.kt`

在 object 顶部新增字段：
```kotlin
private val sourceId = java.util.concurrent.atomic.AtomicLong()
private val sourceCache = androidx.collection.LruCache<String, String>(256)
```
（确认 rhino 模块已依赖 `androidx.collection`；若无则在 `modules/rhino/build.gradle` 添加 `implementation libs.androidx.collection` 或使用等价的 `object: android.util.LruCache`）

新增私有/内部方法：
- `private fun registerSource(source: String, fileName: String?): String`：`fileName` 非空则用它作 key，否则生成 `<script-${sourceId.incrementAndGet()}>`；`sourceCache.put(key, source)`；返回 key。
- `internal fun createScriptException(exception: RhinoException, fallbackSource: String, fallbackSourceName: String): ScriptException`：
  ```kotlin
  val line = exception.lineNumber().takeIf { it > 0 } ?: -1
  val column = exception.columnNumber().takeIf { it > 0 } ?: -1
  val sourceName = exception.sourceName()
  val source = sourceCache[sourceName] ?: fallbackSource.takeIf { sourceName == fallbackSourceName }
  val baseMessage = if (exception is JavaScriptException) exception.value.toString() else exception.toString()
  val message = source?.let { buildEnhancedErrorMessage(baseMessage, it, line, column) } ?: baseMessage
  return ScriptException(message, sourceName, line, column).also { it.initCause(exception) }
  ```
- `private fun buildEnhancedErrorMessage(baseMsg, source, errorLine, errorColumn)`：逐字照搬 legadoT 第 278-330 行逻辑（上下各 2 行、行号列宽自适应、`→` 标记错误行、`^` 指向 `errorColumn-1` 列、列缺失时整行加 `^`、`─` 分隔线 60 字符）。

## Task 2：改造 eval(Reader) 路径

文件：`RhinoScriptEngine.kt` 第 88-125 行 `eval(reader, scope, coroutineContext)`

- 入口先 `val source = reader.readText()`
- `val sourceName = registerSource(source, (this["javax.script.filename"] as? String)?.takeIf { it != "<Unknown source>" })`
- 执行改 `cx.evaluateString(scope, source, sourceName, 1, null)`
- catch `RhinoException` 分支改为 `throw createScriptException(re, source, sourceName)`（删除原手拼 `ScriptException(msg, ...)`）

## Task 3：改造 evalSuspend(Reader) 路径

文件：`RhinoScriptEngine.kt` 第 127-175 行

- 同样先 `readText()` + `registerSource()`
- `compileReader` 改 `compileString(source, sourceName, 1, null)`
- catch 分支改 `throw createScriptException(re, source, sourceName)`

## Task 4：改造 invoke(...) 反射调用路径（可选增强）

文件：`RhinoScriptEngine.kt` 第 195-227 行

此路径 scope 里已缓存的脚本可用 `re.sourceName()` 命中 `sourceCache`。catch `RhinoException` 分支改为：
```kotlin
throw createScriptException(re, "", re.sourceName() ?: "")
```
（无 fallback 源码，但能从缓存取到出错脚本）

## Task 5：改造 compile 路径 + RhinoCompiledScript

文件：`RhinoScriptEngine.kt` 第 276-293 行 `compile(script: Reader)`
- `val source = script.readText()`
- `val sourceName = registerSource(source, fileName.takeUnless{...})`
- `cx.compileString(source, sourceName, 1, null)`
- `RhinoCompiledScript(this, scr, source, sourceName)`

文件：`modules/rhino/src/main/java/com/script/rhino/RhinoCompiledScript.kt`
- 构造器增加 `private val source: String, private val sourceName: String`
- 两处 catch（第 73-82、119-128 行）改为 `throw RhinoScriptEngine.createScriptException(re, source, sourceName)`
- `getEngine()` 保持不变

## Task 6：ScriptException 携带列号（核对，多半无需改）

文件：`modules/rhino/src/main/java/com/script/ScriptException.kt`

已存在 4 参构造 `ScriptException(message, fileName, lineNumber, columnNumber)` 与 `toString()` 拼接逻辑。核对 `toString()` 是否附带 `<sourceName>#line` 展示（legadoT 消息尾部形如 `(<script-5>#196)`）。若缺失，按 legadoT 的 `ScriptException.kt` 补齐格式化（对照 legadoT 同文件）。

---

## 验证

1. `./gradlew :modules:rhino:compileDebugKotlin` 通过。
2. 打开一个含错误 JS 规则的书源，进入源调试，触发 `java.ajax` 之类的未定义方法调用。
3. 调试日志出现截图同款"源码位置（第 N 行）"块，`→` 与 `^` 正确对齐出错表达式。
4. 用一个纯 `getString` 里 `@js:` 规则报错也验证非编译路径。

## 文件变更清单

| 文件 | 操作 |
|------|------|
| `modules/rhino/src/main/java/com/script/rhino/RhinoScriptEngine.kt` | 改：缓存字段 + 三个工具方法 + eval/evalSuspend/invoke/compile 四条异常路径 |
| `modules/rhino/src/main/java/com/script/rhino/RhinoCompiledScript.kt` | 改：构造器加 source/sourceName，两处 catch 用 createScriptException |
| `modules/rhino/src/main/java/com/script/ScriptException.kt` | 核对/改：toString 携带列号与 sourceName#line |
| `modules/rhino/build.gradle` | 视情况：补 androidx.collection 依赖 |

## 风险与假设

- 不改 app 层（Debug/AnalyzeRule）：增强消息随异常自动呈现。若发现 `stackTraceStr` 对多行消息有截断，再单独处理（当前 [长文本 Unicode 安全截断] 规范已知）。
- `evaluateString` 与 `evaluateReader` 行为一致，仅多了显式 sourceName，风险低。
- 源码缓存 LruCache(256) 足够覆盖并发脚本数；超长单行书源由 `^` 单点指向（legadoT 已针对压缩单行优化）。
