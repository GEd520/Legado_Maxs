package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.view.size
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import io.legado.app.constant.AppLog
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.http.CookieManager as AppCookieManager
import androidx.core.net.toUri
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import io.legado.app.model.Download
import splitties.systemservices.powerManager
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLDecoder
import androidx.core.graphics.createBitmap
import io.legado.app.help.WebCacheManager
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.ui.widget.dialog.CookieViewerDialog
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import android.content.Context   
import android.content.Intent
import android.app.Activity      
import androidx.activity.result.contract.ActivityResultContract


/**
 * WebView 浏览器活动
 * 用于显示网页内容，支持源验证、图片保存、全屏浏览等功能
 * 使用 WebViewPool 管理 WebView 实例，提高性能和内存利用率
 */
class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false
    }

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<WebViewModel>()
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var isFullScreen = false
    private var isfullscreen = false
    private var wasScreenOff = false
    private var needClearHistory = true
    // 文件上传回调，记录网页 <input type="file"> 触发的选择器回调
    // Android 5.0+ 系统回调类型为 ValueCallback<Uri[]>，泛型擦除会掩盖类型错位，这里必须用数组
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    // 网页文件上传选择器
    // 用 ActivityResultContract 自定义：支持 <input multiple> 多选，并通过 EXTRA_MIME_TYPES 传多 accept 类型
    // （GetContent 只支持单个 mime，多个 join 会用逗号串起来导致选择器空白甚至抛异常）
    private val uploadFile = registerForActivityResult(object :
        ActivityResultContract<Array<String>, Array<Uri>?>() {
        override fun createIntent(context: Context, input: Array<String>): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                if (input.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, input)
                } else {
                    type = input.firstOrNull() ?: "*/*"
                }
                // 允许多选
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Array<Uri>? {
            if (resultCode != Activity.RESULT_OK) return null
            return intent?.clipData?.let { clip ->
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } ?: intent?.data?.let { arrayOf(it) }
        }
    }) { uris ->
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    // 网页 <input capture> 拍照上传：走 TakePicture 拿到图片 Uri，再回填给 filePathCallback
    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            // 拍照成功：只回传 Uri，让网页自行读取。此文件不能删！
            // （若此时删除，网页还没读到文件内容，上传必失败）
            takePictureUri?.let { filePathCallback?.onReceiveValue(arrayOf(it)) }
            takePictureUri = null
            // 回传完成后清掉回调引用，与取消分支对称
            filePathCallback = null
        } else {
            // 用户取消/失败：回填 null 让网页结束等待，临时文件可以删
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            deleteCaptureFile()
        }
    }
    private var takePictureUri: Uri? = null

    // 删除拍照临时文件。仅用于取消/失败场景；成功场景的文件交由 onDestroy 兜底清理，
    // 避免网页还没读完就被删。FileProvider 返回的都是 content://，无需判 file scheme。
    private fun deleteCaptureFile() {
        takePictureUri?.let { uri ->
            try {
                contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        takePictureUri = null
    }

    private fun refresh() {
        currentWebView.reload()
    }

    /**
     * 活动创建时初始化
     * 从 WebViewPool 获取 WebView 实例，加载 URL 或 HTML 内容
     */
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        currentWebView.post {
            currentWebView.clearHistory()
        }
        binding.titleBar.title = intent.getStringExtra("title") ?: getString(R.string.loading)
        binding.titleBar.subtitle = intent.getStringExtra("sourceName")
        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            initWebView(url, headerMap)
            val html = viewModel.html
            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(url, headerMap)
            } else {
                if (viewModel.localHtml) {
                    viewModel.source?.let {
                        val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                        currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                    }
                    currentWebView.addJavascriptInterface(WebCacheManager, nameCache)
                }
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
        }
        currentWebView.clearHistory()
        onBackPressedDispatcher.addCallback(this) {
            if (binding.customWebView.size > 0) { //网页全屏
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            if (isFullScreen) { //按钮全屏
                toggleFullScreen()
                return@addCallback
            }
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList()
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                var steps = 1
                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || currentTitle != item.title) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }
                if (steps == size) {
                    finish()
                    return@addCallback
                }
                currentWebView.goBackOrForward(-steps)
                return@addCallback
            }
            finish()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_view, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (viewModel.sourceOrigin.isNotEmpty()) {
            menu.findItem(R.id.menu_disable_source)?.isVisible = true
            menu.findItem(R.id.menu_delete_source)?.isVisible = true
        }
        menu.findItem(R.id.menu_show_web_log)?.isChecked = sessionShowWebLog
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_web_refresh -> refresh()
            R.id.menu_open_in_browser -> openUrl(viewModel.baseUrl)
            R.id.menu_copy_url -> sendToClip(viewModel.baseUrl)
            R.id.menu_view_cookie -> currentWebView.url?.let {
                showDialogFragment(CookieViewerDialog(it))
            } ?: toastOnUi("url null")
            R.id.menu_ok -> {
                if (viewModel.sourceVerificationEnable) {
                    viewModel.saveVerificationResult(currentWebView) {
                        finish()
                    }
                } else {
                    finish()
                }
            }

            R.id.menu_full_screen -> toggleFullScreen()
            R.id.menu_show_web_log -> {
                sessionShowWebLog = !sessionShowWebLog
                item.isChecked = sessionShowWebLog
            }
            R.id.menu_disable_source -> {
                viewModel.disableSource {
                    finish()
                }
            }

            R.id.menu_delete_source -> {
                alert(R.string.draw) {
                    setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                    noButton()
                    yesButton {
                        viewModel.deleteSource {
                            finish()
                        }
                    }
                }
            }
            R.id.menu_query_content -> showQueryContentDialog()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    //实现starBrowser调起页面全屏
    /**
     * 切换全屏模式
     * 显示/隐藏系统状态栏和操作栏
     */
    private fun toggleFullScreen() {
        isFullScreen = !isFullScreen
        toggleSystemBar(!isFullScreen)
        if (isFullScreen) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }
    }

    private fun showQueryContentDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.query_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextAppearance(android.R.style.TextAppearance_Medium)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(editText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.query_content)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val queryText = editText.text.toString().trim()
                if (queryText.isNotEmpty()) {
                    searchInPage(queryText)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun searchInPage(query: String) {
        val escapedQuery = query
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("$", "\\$")
        val jsCode = """
            (function() {
                var count = 0;
                var searchText = '$escapedQuery';
                
                // 移除之前的高亮
                var prevHighlight = document.querySelectorAll('.legado-search-highlight');
                prevHighlight.forEach(function(el) {
                    el.outerHTML = el.innerHTML;
                });
                
                if (window.find && window.getSelection) {
                    // 使用浏览器原生搜索
                    window.find(searchText, false, false, true, false, true, false);
                    count = 1;
                } else {
                    // 手动高亮搜索
                    var regex = new RegExp(searchText.replace(/[.*+?^${'$'}{}()|[\\]\\\\]/g, '\\\\$&'), 'gi');
                    function highlight(node) {
                        if (node.nodeType === Node.TEXT_NODE) {
                            var text = node.textContent;
                            if (regex.test(text)) {
                                var span = document.createElement('span');
                                span.className = 'legado-search-highlight';
                                span.style.backgroundColor = '#ffff00';
                                span.style.color = '#000';
                                span.innerHTML = text.replace(regex, '<mark style="background-color: #ffff00; color: #000;">$&</mark>');
                                node.parentNode.replaceChild(span, node);
                                count++;
                            }
                            regex.lastIndex = 0;
                        } else if (node.nodeType === Node.ELEMENT_NODE && node.nodeName !== 'SCRIPT' && node.nodeName !== 'STYLE') {
                            var children = Array.from(node.childNodes);
                            children.forEach(highlight);
                        }
                    }
                    highlight(document.body);
                }
                
                // 返回匹配数量
                if (count === 0) {
                    // 重新计数
                    var marks = document.querySelectorAll('mark');
                    count = marks.length;
                }
                return count;
            })()
        """.trimIndent()
        currentWebView.evaluateJavascript(jsCode) { result ->
            val matchCount = result.toIntOrNull() ?: 0
            if (matchCount > 0) {
                toastOnUi(getString(R.string.query_result, matchCount))
            } else {
                toastOnUi(R.string.query_no_result)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    /**
     * 初始化 WebView 设置
     * 配置 WebView 客户端、JavaScript 接口、下载监听器等
     * @param url 要加载的 URL
     * @param headerMap 请求头信息
     */
    private fun initWebView(url: String, headerMap: HashMap<String, String>) {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient()
        // 添加 JavaScript 接口
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        AppCookieManager.applyToWebView(url)
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
        currentWebView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            currentWebView.longSnackbar(fileName, getString(R.string.action_download)) {
                Download.start(this, url, fileName, currentWebView.url)
            }
        }
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    /**
     * 为 <input capture> 拍照上传创建图片临时文件，并返回 FileProvider Uri。
     * 缓存在 cacheDir 下，避免占用外部存储；TakePicture 写入后由 filePathCallback 回传给网页。
     */
    private fun createImageUri(): Uri? {
        return try {
            val dir = File(cacheDir, "web_capture").apply { mkdirs() }
            val file = File.createTempFile("capture_", ".jpg", dir)
            FileProvider.getUriForFile(this, AppConst.authority, file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun finish() {
        SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
        super.finish()
    }

    private fun close() {
        if (!isCloudflareChallenge) {
            if (viewModel.sourceVerificationEnable) {
                viewModel.saveVerificationResult(currentWebView) {
                    finish()
                }
            }
            else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (powerManager.isInteractive) {
            wasScreenOff = false
            currentWebView.onPause()
        } else {
            wasScreenOff = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wasScreenOff) {
            currentWebView.onResume()
        }
    }

    override fun onDestroy() {
        // Activity 销毁前必须回填 null 并清掉引用，否则文件选择回调一直挂着导致 WebView 等待/泄漏
        if (filePathCallback != null) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
        // 兜底清理拍照临时目录：成功场景的文件在 onDestroy 时统一清除，避免残留
        try {
            File(cacheDir, "web_capture").deleteRecursively()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        WebViewPool.release(pooledWebView)
        super.onDestroy()
    }

    @Suppress("unused")
    /**
     * JavaScript 接口类
     * 用于网页与原生代码交互，支持锁定屏幕方向和关闭页面
     */
    private class JSInterface(activity: WebViewActivity) {
        private val activityRef: WeakReference<WebViewActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && ctx.isfullscreen  && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.requestedOrientation = when (orientation) {
                        "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE //横屏且受重力控制正反
                        "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE //正向横屏
                        "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE //反向横屏
                        "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
            }
        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.close()
                }
            }
        }
    }

    /**
     * 自定义 WebChromeClient
     * 处理网页进度、全屏视频播放、控制台日志等
     */
    inner class CustomWebChromeClient : WebChromeClient() {
        override fun getDefaultVideoPoster(): Bitmap {
            return super.getDefaultVideoPoster() ?: createBitmap(100, 100)
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setDurProgress(newProgress)
            binding.progressBar.gone(newProgress == 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isfullscreen = true
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            isfullscreen = false
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 处理网页 <input type="file"> 文件上传 */
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            // 回调为 null 时不要拦截，让 WebView 走默认行为
            val callback = filePathCallback ?: return false
            // 取消上一次可能未完成的回调，防止页面残留导致泄漏
            this@WebViewActivity.filePathCallback?.onReceiveValue(null)
            this@WebViewActivity.filePathCallback = callback

            // 网页声明 <input capture> 时优先走拍照；TakePicture 需要预先给一个可写入的 FileProvider Uri
            if (fileChooserParams?.isCaptureEnabled == true) {
                val uri = createImageUri() ?: run {
                    // 创建失败则回退到文件选择器
                    uploadFile.launch(arrayOf("image/*"))
                    return true
                }
                takePictureUri = uri
                takePicture.launch(uri)
                return true
            }

            // 解析 accept 类型；多个类型用数组传给 EXTRA_MIME_TYPES，避免 GetContent 逗号拼接的坑
            val acceptTypes = fileChooserParams?.acceptTypes
                ?.filter { it.isNotBlank() }
                ?.toTypedArray()
                ?.takeIf { it.isNotEmpty() }
                ?: arrayOf("*/*")
            uploadFile.launch(acceptTypes)
            return true
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            close()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.source?.let { source ->
                if (sessionShowWebLog) {
                    val messageLevel = consoleMessage.messageLevel().name
                    val message = consoleMessage.message()
                    AppLog.put("${source.getTag()}${messageLevel}: $message",
                        NoStackTraceException("\n${message}\n- Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"))
                    return true
                }
            }
            return false
        }
        
    }

    /**
     * 自定义 WebViewClient
     * 处理页面加载、URL 跳转、SSL 错误等
     */
    inner class CustomWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            // 每次进入新页面都复位，否则上次挑战标记残留会导致 window.close() 永久失效
            isCloudflareChallenge = false
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }
        
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val cookieManager = CookieManager.getInstance()
            url?.let {
                CookieStore.setCookie(it, cookieManager.getCookie(it))
            }
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    binding.titleBar.title = title
                } else {
                    binding.titleBar.title = intent.getStringExtra("title")
                }
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (it == "true") {
                        isCloudflareChallenge = true
                    } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                        viewModel.saveVerificationResult(currentWebView) {
                            finish()
                        }
                    }
                }
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }

    }

}