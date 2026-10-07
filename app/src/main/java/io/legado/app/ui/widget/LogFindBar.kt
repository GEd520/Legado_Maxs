package io.legado.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import io.legado.app.R
import io.legado.app.databinding.ViewLogFindBarBinding
import io.legado.app.utils.getCompatColor
import splitties.views.onClick

/**
 * 调试日志页内查找栏：输入关键词实时回调搜索，配合日志适配器高亮匹配行、定位跳转。
 */
class LogFindBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs, 0) {

    private val binding: ViewLogFindBarBinding =
        ViewLogFindBarBinding.inflate(LayoutInflater.from(context), this)

    /** 关键词变化时回调（含清空，空串表示退出查找） */
    var onSearch: ((String) -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    val query: String
        get() = binding.etFindInput.text?.toString().orEmpty()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getCompatColor(R.color.background_menu))
        binding.etFindInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onSearch?.invoke(s?.toString().orEmpty())
            }
        })
        binding.etFindInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onNext?.invoke()
                true
            } else {
                false
            }
        }
        binding.btnFindPrev.onClick { onPrev?.invoke() }
        binding.btnFindNext.onClick { onNext?.invoke() }
        binding.btnFindClose.onClick { onClose?.invoke() }
    }

    fun show() {
        visibility = VISIBLE
        binding.etFindInput.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etFindInput, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        visibility = GONE
        binding.etFindInput.setText("")
        clearFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    fun setCount(current: Int, total: Int) {
        binding.tvFindCount.text = "$current/$total"
    }

    fun clearCount() {
        binding.tvFindCount.text = ""
    }
}

/**
 * 一个匹配出现处：所在日志条目号 + 条目内字符偏移。
 * 计数与跳转按出现次数进行——一段多行堆栈属于同一日志条目，但可能包含多个匹配。
 */
data class FindOccurrence(val item: Int, val start: Int)

/**
 * 为日志行生成高亮文本：每个匹配子串加背景色；
 * [currentStart] 指向当前定位的匹配处，用更深的背景色区分。
 */
fun highlightLogLine(
    text: String,
    query: String?,
    currentStart: Int?,
    accentColor: Int
): CharSequence {
    if (query.isNullOrEmpty()) {
        return text
    }
    val builder = SpannableStringBuilder(text)
    var index = text.indexOf(query, ignoreCase = true)
    while (index >= 0) {
        val alpha = if (index == currentStart) 140 else 60
        val color = Color.argb(
            alpha,
            Color.red(accentColor),
            Color.green(accentColor),
            Color.blue(accentColor)
        )
        builder.setSpan(
            BackgroundColorSpan(color),
            index,
            index + query.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        index = text.indexOf(query, index + query.length, ignoreCase = true)
    }
    return builder
}
