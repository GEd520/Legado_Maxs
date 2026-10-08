package io.legado.app.ui.rss.source.debug

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.model.Debug

class RssSourceDebugModel(application: Application) : BaseViewModel(application),
    Debug.Callback {
    var rssSource: RssSource? = null
    private var callback: ((Int, String) -> Unit)? = null
    var listSrc: String? = null
    var contentSrc: String? = null

    fun initData(sourceUrl: String?, finally: () -> Unit) {
        sourceUrl?.let {
            execute {
                rssSource = appDb.rssSourceDao.getByKey(sourceUrl)
            }.onFinally {
                finally()
            }
        }
    }

    fun observe(callback: (Int, String) -> Unit) {
        this.callback = callback
    }

    fun startDebug(
        key: String,
        start: (() -> Unit)? = null,
        error: (() -> Unit)? = null,
        localDebug: Boolean = false
    ) {
        execute {
            Debug.callback = this@RssSourceDebugModel
            Debug.startDebug(this, rssSource!!, key, localDebug)
        }.onStart {
            start?.invoke()
        }.onError {
            error?.invoke()
        }
    }

    /**
     * 手动停止调试，保留 callback 以便停止后仍能收到收尾日志
     */
    fun stopDebug() {
        Debug.cancelDebug(false)
    }

    override fun printLog(state: Int, msg: String) {
        when (state) {
            10 -> listSrc = msg
            20 -> contentSrc = msg
            else -> callback?.invoke(state, msg)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Debug.cancelDebug(true)
    }

}
