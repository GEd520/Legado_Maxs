/*
 * Copyright (c) 2005, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.script.rhino

import com.script.AbstractScriptEngine
import com.script.Bindings
import com.script.Compilable
import com.script.CompiledScript
import com.script.Invocable
import com.script.ScriptBindings
import com.script.ScriptContext
import com.script.ScriptException
import com.script.SimpleBindings
import androidx.collection.LruCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Callable
import org.mozilla.javascript.ConsString
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.ContinuationPending
import org.mozilla.javascript.Function
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.lang.reflect.Method
import java.security.AccessControlContext
import java.security.AccessControlException
import java.security.AccessController
import java.security.AllPermission
import java.security.PrivilegedAction
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * Implementation of `ScriptEngine` using the Mozilla Rhino
 * interpreter.
 *
 * @author Mike Grogan
 * @author A. Sundararajan
 * @since 1.6
 */
@Suppress("MemberVisibilityCanBePrivate")
object RhinoScriptEngine : AbstractScriptEngine(), Invocable, Compilable {
    var accessContext: AccessControlContext? = null
    private var topLevel: RhinoTopLevel? = null
    private val indexedProps: MutableMap<Any, Any?>
    private val implementor: InterfaceImplementor

    /**
     * 源码缓存：key 为 sourceName（形如 `<script-N>` 或调用方给定的文件名），value 为脚本全文。
     * 供异常时按行号取回源码上下文渲染增强错误消息（类似 Python traceback）。
     */
    private val sourceId = AtomicLong()
    private val sourceCache = LruCache<String, String>(256)

    /** 缓存源码并返回其 sourceName；fileName 非空则用它作 key，否则生成 `<script-N>` */
    private fun registerSource(source: String, fileName: String?): String {
        val sourceName = if (!fileName.isNullOrBlank()) {
            fileName
        } else {
            "<script-${sourceId.incrementAndGet()}>"
        }
        sourceCache.put(sourceName, source)
        return sourceName
    }

    /**
     * 从 RhinoException 构建带源码上下文的 ScriptException。
     * 按 exception.sourceName() 从缓存取回源码；取不到时用 fallbackSource（需 sourceName 匹配）。
     */
    internal fun createScriptException(
        exception: RhinoException,
        fallbackSource: String,
        fallbackSourceName: String,
    ): ScriptException {
        val line = exception.lineNumber().takeIf { it > 0 } ?: -1
        val column = exception.columnNumber().takeIf { it > 0 } ?: -1
        val sourceName = exception.sourceName()
        val source = sourceCache[sourceName]
            ?: fallbackSource.takeIf { sourceName == fallbackSourceName }
        val baseMessage = if (exception is JavaScriptException) {
            exception.value.toString()
        } else {
            exception.toString()
        }
        val message = source?.let {
            buildEnhancedErrorMessage(baseMessage, it, line, column)
        } ?: baseMessage
        return ScriptException(message, sourceName, line, column).also {
            it.initCause(exception)
        }
    }

    /**
     * 构建增强的错误消息，包含出错行的源码和上下文（类似 Python traceback）。
     * 出错位置的 `^` 精确指向 errorColumn（1-indexed 列偏移），
     * 而非笼统标出整行——书源脚本经常是拼接/压缩的长单行，整行加波浪线基本无诊断价值。
     * @param baseMsg 原始错误消息
     * @param source 完整源码
     * @param errorLine 出错行号（1-indexed，<=0 表示未知）
     * @param errorColumn 出错列号（1-indexed，Rhino 提供，0 表示未知）
     */
    private fun buildEnhancedErrorMessage(
        baseMsg: String,
        source: String,
        errorLine: Int,
        errorColumn: Int
    ): String {
        if (errorLine <= 0) return baseMsg

        val lines = source.lines()
        if (errorLine > lines.size) return baseMsg

        val contextBefore = 2  // 显示出错行前2行
        val contextAfter = 2   // 显示出错行后2行

        val startLine = maxOf(1, errorLine - contextBefore)
        val endLine = minOf(lines.size, errorLine + contextAfter)

        // 行号列宽对齐:按本次显示范围内最大行号的位数,而非固定4位(源码上千行时错位)
        val lineNumWidth = endLine.toString().length

        val sb = StringBuilder(baseMsg)
        sb.append("\n\n")
        sb.append("  源码位置（第 ").append(errorLine)
        if (errorColumn > 0) sb.append(" 行, 第 ").append(errorColumn).append(" 列") else sb.append(" 行")
        sb.append("）:\n")
        sb.append("  ").append("─".repeat(60)).append("\n")

        for (i in startLine..endLine) {
            val lineContent = lines[i - 1]  // lines 是 0-indexed
            val prefix = if (i == errorLine) "→ " else "  "
            val lineNumStr = i.toString().padStart(lineNumWidth)
            sb.append(prefix).append(lineNumStr).append(" │ ").append(lineContent).append("\n")

            if (i == errorLine) {
                // 缩进 = "  " + 行号列宽 + " │ " 的长度,与上一行的 " │ " 分隔符对齐
                val gutterWidth = 2 + lineNumWidth + 3
                sb.append(" ".repeat(gutterWidth))
                if (errorColumn > 0) {
                    // errorColumn 是 1-indexed 列偏移,前面填 errorColumn-1 个空格再放置指示符
                    sb.append(" ".repeat((errorColumn - 1).coerceAtMost(400)))
                    sb.append("^")
                } else {
                    // 列信息缺失时回退:整行加波浪线(优于完全不给指示)
                    sb.append("^".repeat(lineContent.length.coerceIn(1, 60)))
                }
                sb.append("\n")
            }
        }

        sb.append("  ").append("─".repeat(60))

        return sb.toString()
    }

    fun eval(js: String, bindingsConfig: ScriptBindings.() -> Unit = {}): Any? {
        val bindings = ScriptBindings()
        Context.enter()
        try {
            bindings.apply(bindingsConfig)
        } finally {
            Context.exit()
        }
        return eval(js, bindings)
    }

    override fun eval(
        reader: Reader,
        scope: Scriptable,
        coroutineContext: CoroutineContext?
    ): Any? {
        // 先读取全部内容以便在异常时显示源码上下文
        val source = reader.readText()
        val sourceName = registerSource(
            source,
            (this["javax.script.filename"] as? String)?.takeIf { it != "<Unknown source>" }
        )
        val cx = Context.enter() as RhinoContext
        val previousCoroutineContext = cx.coroutineContext
        if (coroutineContext != null && coroutineContext[Job] != null) {
            cx.coroutineContext = coroutineContext
        }
        cx.allowScriptRun = true
        cx.recursiveCount++
        val ret: Any?
        try {
            cx.checkRecursive()
            ret = cx.evaluateString(scope, source, sourceName, 1, null)
        } catch (re: RhinoException) {
            throw createScriptException(re, source, sourceName)
        } catch (var14: IOException) {
            throw ScriptException(var14)
        } finally {
            cx.coroutineContext = previousCoroutineContext
            cx.allowScriptRun = false
            cx.recursiveCount--
            Context.exit()
        }
        return unwrapReturnValue(ret)
    }

    @Throws(ContinuationPending::class)
    override suspend fun evalSuspend(reader: Reader, scope: Scriptable): Any? {
        // 先读取全部内容以便在异常时显示源码上下文
        val source = reader.readText()
        val sourceName = registerSource(
            source,
            (this@RhinoScriptEngine["javax.script.filename"] as? String)?.takeIf { it != "<Unknown source>" }
        )
        val cx = Context.enter() as RhinoContext
        var ret: Any?
        withContext(VMBridgeReflect.contextLocal.asContextElement()) {
            cx.allowScriptRun = true
            cx.recursiveCount++
            try {
                cx.checkRecursive()
                val script = cx.compileString(source, sourceName, 1, null)
                try {
                    ret = cx.executeScriptWithContinuations(script, scope)
                } catch (e: ContinuationPending) {
                    var pending = e
                    while (true) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val suspendFunction = pending.applicationState as suspend () -> Any?
                            val functionResult = suspendFunction()
                            val continuation = pending.continuation
                            ret = cx.resumeContinuation(continuation, scope, functionResult)
                            break
                        } catch (e: ContinuationPending) {
                            pending = e
                        }
                    }
                }
            } catch (re: RhinoException) {
                throw createScriptException(re, source, sourceName)
            } catch (var14: IOException) {
                throw ScriptException(var14)
            } finally {
                cx.allowScriptRun = false
                cx.recursiveCount--
                Context.exit()
            }
        }
        return unwrapReturnValue(ret)
    }

    override fun createBindings(): Bindings {
        return SimpleBindings()
    }

    @Throws(ScriptException::class, NoSuchMethodException::class)
    override fun invokeFunction(name: String, vararg args: Any): Any? {
        return this.invoke(null, name, *args)
    }

    @Throws(ScriptException::class, NoSuchMethodException::class)
    override fun invokeMethod(obj: Any?, name: String, vararg args: Any): Any? {
        return if (obj == null) {
            throw IllegalArgumentException("脚本对象不能为空")
        } else {
            this.invoke(obj, name, *args)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(ScriptException::class, NoSuchMethodException::class)
    private operator fun invoke(thiz: Any?, name: String?, vararg args: Any?): Any? {
        var thiz1 = thiz
        val cx = Context.enter()
        val var11: Any?
        try {
            if (name == null) {
                throw NullPointerException("方法名为空")
            }
            if (thiz1 != null && thiz1 !is Scriptable) {
                thiz1 = Context.toObject(thiz1, topLevel)
            }
            val engineScope = getRuntimeScope(context)
            val localScope = thiz1 ?: engineScope
            val obj = ScriptableObject.getProperty(localScope, name) as? Function
                ?: throw NoSuchMethodException("no such method: $name")
            var scope = obj.parentScope
            if (scope == null) {
                scope = engineScope
            }
            val result = obj.call(cx, scope, localScope, wrapArguments(args as? Array<Any?>))
            var11 = unwrapReturnValue(result)
        } catch (re: RhinoException) {
            // 无 fallback 源码，但出错脚本已注册到 sourceCache，可按 re.sourceName() 命中渲染增强消息
            throw createScriptException(re, "", re.sourceName() ?: "")
        } finally {
            Context.exit()
        }
        return var11
    }

    override fun <T> getInterface(clazz: Class<T>): T? {
        return try {
            implementor.getInterface(null, clazz)
        } catch (_: ScriptException) {
            null
        }
    }

    override fun <T> getInterface(obj: Any?, paramClass: Class<T>): T? {
        return if (obj == null) {
            throw IllegalArgumentException("脚本对象不能为空")
        } else {
            try {
                implementor.getInterface(obj, paramClass)
            } catch (_: ScriptException) {
                null
            }
        }
    }

    override fun getRuntimeScope(context: ScriptContext): Scriptable {
        val newScope: Scriptable = ExternalScriptable(context, indexedProps)
        val cx = Context.enter()
        try {
            newScope.prototype = RhinoTopLevel(cx, this)
        } finally {
            Context.exit()
        }
        //newScope.put("context", newScope, context)
        return newScope
    }

    override fun getRuntimeScope(bindings: ScriptBindings): Scriptable {
        val cx = Context.enter()
        try {
            bindings.prototype = cx.initStandardObjects()
        } finally {
            Context.exit()
        }
        return bindings
    }

    @Throws(ScriptException::class)
    override fun compile(script: String): CompiledScript {
        return this.compile(StringReader(script) as Reader)
    }

    @Throws(ScriptException::class)
    override fun compile(script: Reader): CompiledScript {
        val source = try {
            script.readText()
        } catch (e: IOException) {
            throw ScriptException(e)
        }
        val fileName = (this["javax.script.filename"] as? String)
            ?.takeIf { it != "<Unknown Source>" && it != "<Unknown source>" }
        val sourceName = registerSource(source, fileName)
        val cx = Context.enter()
        val ret: RhinoCompiledScript
        try {
            val scr = cx.compileString(source, sourceName, 1, null)
            ret = RhinoCompiledScript(this, scr, source, sourceName)
        } catch (re: RhinoException) {
            throw createScriptException(re, source, sourceName)
        } catch (var9: Exception) {
            throw ScriptException(var9)
        } finally {
            Context.exit()
        }
        return ret
    }

    fun wrapArguments(args: Array<Any?>?): Array<Any?> {
        return if (args == null) {
            Context.emptyArgs
        } else {
            val res = arrayOfNulls<Any>(args.size)
            for (i in res.indices) {
                res[i] = Context.javaToJS(args[i], topLevel)
            }
            res
        }
    }

    fun unwrapReturnValue(result: Any?): Any? {
        var result1 = result
        if (result1 is Wrapper) {
            result1 = result1.unwrap()
        }
        if (result1 is ConsString) {
            result1 = result1.toString()
        }
        return if (result1 is Undefined) null else result1
    }

    init {
        ContextFactory.initGlobal(object : ContextFactory() {

            override fun makeContext(): Context {
                val cx = RhinoContext(this)
                cx.languageVersion = Context.VERSION_ES6
                cx.setInterpretedMode(true)
                cx.setClassShutter(RhinoClassShutter)
                cx.wrapFactory = RhinoWrapFactory
                cx.instructionObserverThreshold = 10000
                cx.maximumInterpreterStackDepth = 1000
                return cx
            }

            override fun hasFeature(cx: Context, featureIndex: Int): Boolean {
                @Suppress("UNUSED_EXPRESSION")
                return when (featureIndex) {
                    Context.FEATURE_ENABLE_JAVA_MAP_ACCESS -> true
                    else -> super.hasFeature(cx, featureIndex)
                }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                if (cx is RhinoContext) {
                    cx.ensureActive()
                }
            }

            override fun doTopCall(
                callable: Callable,
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable?,
                args: Array<Any>
            ): Any? {
                var accContext: AccessControlContext? = null
                val global = ScriptableObject.getTopLevelScope(scope)
                val globalProto = global.prototype
                if (globalProto is RhinoTopLevel) {
                    accContext = globalProto.accessContext
                }
                return if (accContext != null) AccessController.doPrivileged(
                    PrivilegedAction {
                        superDoTopCall(callable, cx, scope, thisObj, args)
                    }, accContext
                ) else superDoTopCall(
                    callable,
                    cx,
                    scope,
                    thisObj,
                    args
                )
            }

            private fun superDoTopCall(
                callable: Callable,
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable?,
                args: Array<Any>
            ): Any? {
                try {
                    if (cx is RhinoContext) {
                        if (!cx.allowScriptRun) {
                            error("Not allow run script in unauthorized way.")
                        }
                        cx.ensureActive()
                    }
                    return super.doTopCall(callable, cx, scope, thisObj, args)
                } catch (e: RhinoInterruptError) {
                    throw e.cause
                }
            }
        })

        if (System.getSecurityManager() != null) {
            try {
                AccessController.checkPermission(AllPermission())
            } catch (_: AccessControlException) {
                accessContext = AccessController.getContext()
            }
        }
        val cx = Context.enter()
        try {
            topLevel = RhinoTopLevel(cx, this)
        } finally {
            Context.exit()
        }
        indexedProps = HashMap()
        implementor = object : InterfaceImplementor(this) {

            override fun isImplemented(obj: Any?, clazz: Class<*>): Boolean {
                var obj1 = obj
                return try {
                    if (obj1 != null && obj1 !is Scriptable) {
                        obj1 = Context.toObject(obj1, topLevel)
                    }
                    val engineScope = getRuntimeScope(context)
                    val localScope = obj1 ?: engineScope
                    val methods = clazz.methods
                    val methodsSize = methods.size
                    for (index in 0 until methodsSize) {
                        val method = methods[index]
                        if (method.declaringClass != Any::class.java) {
                            if (ScriptableObject.getProperty(
                                    localScope,
                                    method.name
                                ) !is Function
                            ) {
                                return false
                            }
                        }
                    }
                    true
                } finally {
                    Context.exit()
                }
            }

            override fun convertResult(method: Method?, res: Any?): Any? {
                method ?: return null
                val desiredType = method.returnType
                if (desiredType == Void.TYPE) return null
                return Context.jsToJava(res, desiredType)
            }
        }
    }

}
