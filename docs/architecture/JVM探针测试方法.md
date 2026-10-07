# JVM 探针测试方法

> 一次性「量尺」测试：在本地 JVM 单测里，把两段候选代码各跑一遍，用确定性指标量出差距，并断言结果等价。
> 不装 APK、不碰模拟器，跑一次一两分钟。案例：订阅源导入解析优化（85.5MB → 10MB，commit 3f1a44f2b）。

## 定位：和单测、性能测试的分工

|            | 正式单测     | 探针测试           | 性能测试/E2E |
| ---------- | ------------ | ------------------ | ------------ |
| 回答的问题 | 逻辑对不对   | 差多少、是否等价   | 整机体验     |
| 生命周期   | 长期留在仓库 | 用后即删（或转正） | 长期         |
| 环境要求   | JVM 或设备   | 纯 JVM 即可        | 设备/模拟器  |

探针不是单测的替代——它用于**改造前拿证据**：证明旧写法确实浪费、新写法确实更好且结果一致。结论数字写进 commit message，然后探针删掉。

## 适用场景

- 解析/算法/匹配等**纯计算逻辑**：输入→输出不碰 UI、不依赖 Android 运行时（GSON、JsonPath、实体类、正则这些都可直接用生产 classpath）
- 对比两种实现的内存分配量、耗时
- 重写前后**行为等价性验证**（把两边输出序列化成字符串做 `assertEquals`）

不适用：要碰 `Context`、`LayoutInflater`、真实网络/UI 的逻辑——那部分老老实实上模拟器。

## 基本做法

1. **复制管线**：把旧写法的关键代码原样抄进测试方法（不要绕过它走捷径，否则数据不真实）；新写法同样抄一份，或直接调用将要改成的那个函数。
2. **真实数据**：用一个有代表性的真实文件当输入（放到 `app/build/` 下用 ASCII 文件名，避免中文路径在测试 JVM 里的编码问题；`app/build` 不入库，天然是临时数据的好去处）。
3. **预热**：正式测量前先跑一遍管线，排除 GSON/JsonPath 懒加载、类加载的噪声。
4. **测量**：见下节指标选择。
5. **一致性断言**：`assertEquals(GSON.toJson(结果A), GSON.toJson(结果B))`——逐字节比对，比抽查字段强得多。
6. **清理**：删除探针文件与 build 下的输入数据；结论沉淀进 commit message。

### 测量分配量的标准写法

```kotlin
/** 经已导出的 com.sun.management 接口取方法，避开未导出的内部实现类 */
private fun threadAllocated(): Long {
    return try {
        val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        val iface = Class.forName("com.sun.management.ThreadMXBean")
        val m = iface.methods.first { it.name == "getThreadAllocatedBytes" }
        m.invoke(bean, Thread.currentThread().id) as Long
    } catch (t: Throwable) {
        -1
    }
}

// 用法
val before = threadAllocated()
runPipeline(bytes)
val allocated = threadAllocated() - before   // 这段代码总共向 JVM 申请了多少内存
```

为什么要反射：android.jar 没编译期 API；且**必须从 `com.sun.management.ThreadMXBean` 接口上取 Method**——直接 `bean.javaClass.methods` 会碰到未导出的 `com.sun.management.internal.HotSpotThreadImpl`，抛 `IllegalAccessException`。

## 指标怎么选

| 指标                                   | 可靠性      | 说明                                                                                                      |
| -------------------------------------- | ----------- | --------------------------------------------------------------------------------------------------------- |
| 线程分配字节 `getThreadAllocatedBytes` | ✅ 首选     | 与 GC 时机完全无关，量出就是多少；分配量大 = GC 压力大，正是「内存飙升」的根源                            |
| GC 后堆差值（`System.gc()` 前后）      | ❌ 不可用   | Gradle 测试进程实测禁用了显式 GC：丢弃 20MB 数组后 6 次 `System.gc()` 只回收 37KB，差值里全是没回收的垃圾 |
| 不 GC 直接量堆增量                     | ⚠️ 仅作参考 | 含未回收垃圾，只能看相对趋势（本次 A≈20MB vs B≈10MB，方向与分配字节一致）                                 |
| 耗时 `System.nanoTime()`               | ✅ 可用     | 想比速度就用它，同样记得预热                                                                              |

## 两个已踩过的坑

1. **Gradle 测试进程禁用显式 GC**（见上表）。任何依赖「先 GC 再量堆」的测法在这个环境里都是自欺欺人。想测真驻留，需要独立 JVM + 可用的 GC，不值得为一次性探针搭这个。
2. **JUnit 4 每个测试方法新建实例**。实例字段在各方法间不共享——多个测量方法共写一个实例字段的 `MutableList` 再落盘，文件里只会剩最后一个方法的数据。共享状态放 `companion object`，或干脆一个方法测完所有方案。

## 运行命令（本项目）

```bash
# 单测必须带 flavor 变体（compileDebugKotlin 会因多变体歧义报错）
./gradlew :app:testAppMaxDebugUnitTest --tests "io.legado.app.utils.你的探针类"
```

结果落盘到 `app/build/*.txt` 再 `cat`，比依赖 Gradle 打印测试 stdout 省事（后者要 `-i` 才可见）。

## 何时转正为正式单测

探针默认用后即删。只有一种情况转正：**它变成了防回归的护栏**。例如「流式解析不得退化成全量 DOM」可以转正成一个断言分配量上限的单测——但要先给上限留足余量（不同机器数值会漂），别让它在 CI 里随机红。
