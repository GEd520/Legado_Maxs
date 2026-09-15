package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.dao.BookShelfDisplay
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.book.BookTagMatcher
import io.legado.app.help.book.toSmartTagSnapshot
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bookBorderBackground
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible
import io.legado.app.utils.dpToPx
import splitties.views.onLongClick

class BooksAdapterList(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle,
) : BaseBooksAdapter<ItemBookshelfListBinding>(context) {

    private companion object {
        /** 未读轨道色：主题强调色约 25% 透明度 */
        const val TRACK_ALPHA = 64
    }

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding = ItemBookshelfListBinding.inflate(inflater, parent, false)

    /**
     * 方案E：取消封面图片加载
     */
    override fun cancelCoverLoad(binding: ItemBookshelfListBinding) {
        binding.ivCover.cancelLoad()
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfListBinding,
        item: BookShelfDisplay,
        payloads: MutableList<Any>,
    ) = binding.run {
        if (payloads.isEmpty()) {
            // 根据配置控制书籍外边框显示和间距
            if (AppConfig.showBookBorder) {
                root.background = context.bookBorderBackground
                root.setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(
                    4.dpToPx(),
                    4.dpToPx(),
                    4.dpToPx(),
                    4.dpToPx(),
                )
            } else {
                root.background = null
                root.setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
            }
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivCover.load(item, false)
            upRefresh(binding, item)
            upLastUpdateTime(binding, item)
            upReadProgress(binding, item)
            // 显示简介和标签（仅在列表视图启用"显示更多信息"时）
            upMoreInfo(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "author" -> tvAuthor.text = item.author
                        "dur" -> tvRead.text = item.durChapterTitle
                        "last" -> tvLast.text = item.latestChapterTitle
                        "cover" -> ivCover.load(item, false, fragment, lifecycle)
                        "refresh" -> {
                            upRefresh(binding, item)
                            upReadProgress(binding, item)
                        }
                        "lastUpdateTime" -> upLastUpdateTime(binding, item)
                        "moreInfo" -> upMoreInfo(binding, item)
                    }
                }
            }
        }
    }

    private fun upRefresh(binding: ItemBookshelfListBinding, item: BookShelfDisplay) {
        if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
            binding.bvUnread.invisible()
            binding.rlLoading.visible()
        } else {
            binding.rlLoading.gone()
            if (AppConfig.showUnread) {
                binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
            } else {
                binding.bvUnread.invisible()
            }
        }
    }

    private fun upLastUpdateTime(binding: ItemBookshelfListBinding, item: BookShelfDisplay) {
        if (AppConfig.showLastUpdateTime && !item.isLocal) {
            val time = item.latestChapterTime.toTimeAgo()
            if (binding.tvLastUpdateTime.text != time) {
                binding.tvLastUpdateTime.text = time
            }
        } else {
            binding.tvLastUpdateTime.text = ""
        }
    }

    private fun upReadProgress(binding: ItemBookshelfListBinding, item: BookShelfDisplay) {
        val progress = if (AppConfig.showBookshelfReadProgress) item.readProgress() else null
        if (progress == null) {
            binding.pbReadProgress.gone()
            binding.tvReadPercent.gone()
        } else {
            // 未读轨道跟随主题强调色（半透明），避免默认轨道色与主题色脱节
            binding.pbReadProgress.setIndicatorColor(binding.pbReadProgress.context.accentColor)
            binding.pbReadProgress.setTrackColor(
                ColorUtils.setAlphaComponent(binding.pbReadProgress.context.accentColor, TRACK_ALPHA),
            )
            binding.pbReadProgress.visible()
            binding.pbReadProgress.progress = (progress * 100).toInt()
            binding.tvReadPercent.visible()
            binding.tvReadPercent.text = "${(progress * 100).toInt()}%"
        }
    }

    /** 更新简介和标签的显示状态 */
    private fun upMoreInfo(binding: ItemBookshelfListBinding, item: BookShelfDisplay) {
        // 显示标签（使用 FlexboxLayout，每个标签有外框）
        if (AppConfig.showMoreInfoInList && AppConfig.showCategoryInfoInList) {
            binding.flexboxTags.visible()
            updateTagViews(binding.flexboxTags, item)
        } else {
            binding.flexboxTags.gone()
        }
        // 显示简介（使用配置的行数）
        if (AppConfig.showMoreInfoInList && AppConfig.showIntroInList) {
            binding.tvIntro.visible()
            binding.tvIntro.text = item.getDisplayIntroPlainText()
            // 根据配置设置简介的最大行数
            binding.tvIntro.maxLines = AppConfig.introLinesInList
        } else {
            binding.tvIntro.gone()
        }
    }

    /**
     * 更新 FlexboxLayout 中的标签视图。
     *
     * 顺序固定为「书籍标签 → 字数 → 分类」：书籍标签（自定义标签 + 智能标签）优先显示，
     * 带菱形前缀以便与字数/分类等其他信息区分；其余信息仍然照旧全部展示。
     */
    private fun updateTagViews(flexboxLayout: FlexboxLayout, item: BookShelfDisplay) {
        flexboxLayout.removeAllViews()

        // 优先显示书籍标签，菱形前缀用于与其他信息区分
        val bookTags = BookTagMatcher.bookTagNames(
            item.customTag,
            item.toSmartTagSnapshot(),
            BookTagMatcher.enabledRules(context),
        )
        for (tag in bookTags) {
            flexboxLayout.addView(createTagView(BookTagMatcher.tagLabel(tag)))
        }

        // 其后显示字数标签
        if (item.wordCount?.isNotBlank() == true) {
            val wordCountTag = createTagView(item.wordCount!!)
            flexboxLayout.addView(wordCountTag)
        }

        // 最后显示分类标签（书源分类信息）
        val tagsText = item.kind ?: ""
        if (tagsText.isNotBlank()) {
            val tags = tagsText.splitNotBlank(",", "\n")
            for (tag in tags) {
                val tagView = createTagView(tag)
                flexboxLayout.addView(tagView)
            }
        }
    }

    /** 创建单个标签视图（带外框样式） */
    private fun createTagView(tag: CharSequence): TextView = TextView(context).apply {
        text = tag
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(context.resources.getColor(io.legado.app.R.color.tv_text_summary, null))
        // 根据书籍外边框状态同步标签外框：有边框时使用带描边的标签背景，无边框时仅显示纯文本
        if (AppConfig.showBookBorder) {
            setBackgroundResource(io.legado.app.R.drawable.bg_tag)
        }
        // 设置内边距
        setPadding(8, 4, 8, 4)
        // 设置 FlexboxLayout.LayoutParams
        layoutParams = FlexboxLayout.LayoutParams(
            FlexboxLayout.LayoutParams.WRAP_CONTENT,
            FlexboxLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            // 标签之间的间距
            setMargins(4, 2, 4, 2)
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfListBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it.toMinimalBook())
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it.toMinimalBook())
                }
            }
        }
    }
}
