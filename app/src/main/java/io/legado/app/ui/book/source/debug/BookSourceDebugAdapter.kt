package io.legado.app.ui.book.source.debug

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemLogBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.FindOccurrence
import io.legado.app.ui.widget.highlightLogLine

class BookSourceDebugAdapter(context: Context) :
    RecyclerAdapter<String, ItemLogBinding>(context) {

    private var searchQuery: String? = null
    private var currentOccurrence: FindOccurrence? = null

    /**
     * 设置查找关键词并刷新高亮；query 为空时退出查找状态
     */
    fun setSearch(query: String?, current: FindOccurrence? = null) {
        searchQuery = query?.takeIf { it.isNotEmpty() }
        currentOccurrence = current
        notifyDataSetChanged()
    }

    /**
     * 更新当前定位的匹配出现处（不改变关键词）
     */
    fun setCurrentOccurrence(current: FindOccurrence?) {
        currentOccurrence = current
        notifyDataSetChanged()
    }

    override fun getViewBinding(parent: ViewGroup): ItemLogBinding {
        return ItemLogBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemLogBinding,
        item: String,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            if (textView.getTag(R.id.tag1) == null) {
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        textView.isCursorVisible = false
                        textView.isCursorVisible = true
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                }
                textView.addOnAttachStateChangeListener(listener)
                textView.setTag(R.id.tag1, listener)
            }
            val position = holder.bindingAdapterPosition
            val current = currentOccurrence?.takeIf { it.item == position }
            textView.text = highlightLogLine(
                item,
                searchQuery,
                current?.start,
                context.accentColor
            )
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemLogBinding) {
        //nothing
    }
}
