package io.legado.app.model.analyzeRule

import io.legado.app.model.analyzeRule.AnalyzeUrl.LocalDebugResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AnalyzeUrl.judgeLocalDebugResult] 单元测试。
 *
 * 本地调试用这个判定决定"规则算出的结果是当作网址去打印请求后结束，还是当作内容直接交给解析规则"。
 * 判定错了方向，本地调试就会出现"JSON 被当网址"或"该联网的不联网"两类体验问题。
 */
class AnalyzeUrlLocalDebugTest {

    @Test
    fun `http 网址判为请求`() {
        assertEquals(
            LocalDebugResult.Request,
            AnalyzeUrl.judgeLocalDebugResult("http://www.x.com/search?q=1"),
        )
    }

    @Test
    fun `https 大写前缀判为请求`() {
        assertEquals(
            LocalDebugResult.Request,
            AnalyzeUrl.judgeLocalDebugResult("HTTPS://www.x.com/api"),
        )
    }

    @Test
    fun `带选项段的网址判为请求`() {
        assertEquals(
            LocalDebugResult.Request,
            AnalyzeUrl.judgeLocalDebugResult("https://www.x.com/api,{\"method\":\"POST\",\"body\":\"a=1\"}"),
        )
    }

    @Test
    fun `data 网址判为请求`() {
        assertEquals(
            LocalDebugResult.Request,
            AnalyzeUrl.judgeLocalDebugResult("data:text/html;charset=utf-8,<h1>hello</h1>"),
        )
    }

    @Test
    fun `相对路径判为请求`() {
        assertEquals(
            LocalDebugResult.Request,
            AnalyzeUrl.judgeLocalDebugResult("/api/search?q=1"),
        )
    }

    @Test
    fun `json 数组判为内容`() {
        assertEquals(
            LocalDebugResult.Content,
            AnalyzeUrl.judgeLocalDebugResult("[{\"title\":\"a\"}, {\"title\":\"b\"}]"),
        )
    }

    @Test
    fun `json 对象判为内容`() {
        assertEquals(
            LocalDebugResult.Content,
            AnalyzeUrl.judgeLocalDebugResult("{\"data\":{\"list\":[]}}"),
        )
    }

    @Test
    fun `html 判为内容`() {
        assertEquals(
            LocalDebugResult.Content,
            AnalyzeUrl.judgeLocalDebugResult("<html><body>正文</body></html>"),
        )
    }

    @Test
    fun `含换行的文本判为内容`() {
        assertEquals(
            LocalDebugResult.Content,
            AnalyzeUrl.judgeLocalDebugResult("第一行\n第二行"),
        )
    }

    @Test
    fun `空字符串判为空结果`() {
        assertEquals(
            LocalDebugResult.Empty,
            AnalyzeUrl.judgeLocalDebugResult(""),
        )
    }

    @Test
    fun `空白字符串判为空结果`() {
        assertEquals(
            LocalDebugResult.Empty,
            AnalyzeUrl.judgeLocalDebugResult("   \n  "),
        )
    }

    @Test
    fun `只有选项段判为空结果`() {
        assertEquals(
            LocalDebugResult.Empty,
            AnalyzeUrl.judgeLocalDebugResult(",{\"method\":\"POST\"}"),
        )
    }
}
