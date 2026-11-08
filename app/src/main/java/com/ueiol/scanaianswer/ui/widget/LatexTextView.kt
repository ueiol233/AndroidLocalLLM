package com.ueiol.scanaianswer.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.util.LruCache
import androidx.appcompat.widget.AppCompatTextView
import ru.noties.jlatexmath.JLatexMathDrawable
import timber.log.Timber

/**
 * 支持 Markdown + LaTeX 公式渲染的 TextView。
 *
 * - Markdown：标题(#)、粗体(**)、斜体(*)、~~删除线~~、`行内代码`、
 *   代码块(```)、引用(>)、无序/有序列表、[链接](url)、分割线(---)。
 * - LaTeX：自动检测 $...$（行内）和 $$...$$（块级），用 JLaTeXMath
 *   渲染为图片插入到文本中。
 *
 * 全部自研零依赖，带有公式渲染缓存，避免流式输出时重复渲染同一公式。
 */
class LatexTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        /** 缓存大小：最多缓存 20 个公式 Drawable */
        private const val CACHE_SIZE = 20

        /** 公式渲染共享缓存：跨实例共用，避免同一公式重复构建 */
        private val drawableCache = LruCache<String, Drawable>(CACHE_SIZE)

        /** 缓存 key 的生成参数记录（共享），检测样式变化时清空 */
        @Volatile
        private var cachedTextSize: Float = -1f
        @Volatile
        private var cachedTextColor: Int = -1

        // MD 渲染配色（与 values/colors.xml 保持一致）
        private val CODE_BG = 0x1A000000          // 代码背景黑 10%
        private val LINK_COLOR = 0xFF1565C0.toInt()   // 链接蓝
        private val QUOTE_BAR = 0xFF757575.toInt()    // 引用条（text_secondary）
        private val LIST_DOT = 0xFF3F51B5.toInt()     // 列表圆点（primary）

        private val HEADING_RE = Regex("^(#{1,6})\\s+(.+)$")
        private val HR_RE = Regex("^(\\*{3,}|-{3,}|_{3,})$")
        private val QUOTE_RE = Regex("^\\s*>\\s?(.*)$")
        private val UL_RE = Regex("^\\s*[-*+]\\s+(.+)$")
        private val OL_RE = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
        private val FENCE_RE = Regex("^\\s*```(.*)$")
    }

    /** 上次绑定文本；流式时内容未变直接跳过，避免无意义全量重解析 */
    private var lastBound: String? = null

    /**
     * 设置支持 Markdown 与 LaTeX 的文本。
     * 会先解析 Markdown 结构，再渲染其中的 LaTeX 公式。
     */
    fun setLatexText(text: String) {
        if (text == lastBound) return
        lastBound = text

        if (text.isBlank()) {
            this.text = text
            return
        }

        try {
            val spannable = renderMarkdown(text)
            setText(spannable, BufferType.SPANNABLE)
        } catch (e: Exception) {
            Timber.e(e, "Markdown/LaTeX render failed, fallback to plain text")
            this.text = text
        }
    }

    /** 清空公式渲染缓存。
     * 在内存紧张或样式变化时调用。 */
    fun clearCache() {
        drawableCache.evictAll()
        cachedTextSize = -1f
        cachedTextColor = -1
        lastBound = null
    }

    // ================= Markdown 解析 =================

    private fun renderMarkdown(text: String): Spannable {
        val builder = SpannableStringBuilder()
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        val n = lines.size

        while (i < n) {
            var line = lines[i]

            // 代码块 ``` ```
            val fence = FENCE_RE.find(line)
            if (fence != null) {
                i++
                val sb = StringBuilder()
                var closed = false
                while (i < n) {
                    val l = lines[i]
                    if (FENCE_RE.find(l) != null) { closed = true; i++; break }
                    sb.append(l).append('\n')
                    i++
                }
                if (!closed) sb.setLength(sb.length - 1) // 去掉多余尾部换行
                appendCodeBlock(builder, sb.toString())
                continue
            }

            // 标题 #
            val heading = HEADING_RE.find(line)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val content = heading.groupValues[2].trim()
                val start = builder.length
                parseInline(content, builder)
                builder.setSpan(RelativeSizeSpan(headingScale(level)), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(Color.BLACK), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                appendBreak(builder)
                i++
                continue
            }

            // 分割线
            if (HR_RE.find(line.trim()) != null) {
                appendHr(builder)
                i++
                continue
            }

            // 引用 >
            val quoteMatch = QUOTE_RE.find(line)
            if (quoteMatch != null) {
                val sb = StringBuilder()
                var j = i
                while (j < n && QUOTE_RE.find(lines[j]) != null) {
                    sb.append(QUOTE_RE.find(lines[j])!!.groupValues[1]).append('\n')
                    j++
                }
                val start = builder.length
                parseInline(sb.toString().trimEnd('\n'), builder)
                if (start < builder.length) {
                    builder.setSpan(QuoteSpan(QUOTE_BAR), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(QUOTE_BAR), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                appendBreak(builder)
                i = j
                continue
            }

            // 无序列表
            val ul = UL_RE.find(line)
            if (ul != null) {
                val start = builder.length
                builder.append("•  ")
                builder.setSpan(ForegroundColorSpan(LIST_DOT), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                parseInline(ul.groupValues[1].trim(), builder)
                appendBreak(builder)
                i++
                continue
            }

            // 有序列表
            val ol = OL_RE.find(line)
            if (ol != null) {
                val start = builder.length
                builder.append(ol.groupValues[1]).append(".  ")
                parseInline(ol.groupValues[2].trim(), builder)
                appendBreak(builder)
                i++
                continue
            }

            // 空行
            if (line.isBlank()) {
                appendBreak(builder)
                i++
                continue
            }

            // 普通段落
            val start = builder.length
            parseInline(line.trim(), builder)
            appendBreak(builder)
            i++
        }

        // 去掉末尾多余换行，避免气泡底部出现空行
        while (builder.isNotEmpty() && builder.last() == '\n') {
            builder.delete(builder.length - 1, builder.length)
        }
        return builder
    }

    /** 行内解析：Markdown 行内语法 + LaTeX 公式。递归支持嵌套。 */
    private fun parseInline(text: String, builder: SpannableStringBuilder) {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]

            // 块级公式 $$...$$
            if (c == '$' && i + 1 < n && text[i + 1] == '$') {
                val close = text.indexOf("$$", i + 2)
                if (close > i + 2) {
                    appendLatex(text.substring(i + 2, close), builder)
                    i = close + 2
                    continue
                }
            }
            // 行内公式 $...$
            if (c == '$') {
                val close = text.indexOf('$', i + 1)
                if (close > i) {
                    appendLatex(text.substring(i + 1, close), builder)
                    i = close + 1
                    continue
                }
            }
            // 块级公式 \[...\]
            if (c == '\\' && i + 1 < n && text[i + 1] == '[') {
                val close = text.indexOf("\\]", i + 2)
                if (close > i + 2) {
                    appendLatex(text.substring(i + 2, close), builder)
                    i = close + 2
                    continue
                }
            }
            // 行内公式 \(...\)
            if (c == '\\' && i + 1 < n && text[i + 1] == '(') {
                val close = text.indexOf("\\)", i + 2)
                if (close > i + 2) {
                    appendLatex(text.substring(i + 2, close), builder)
                    i = close + 2
                    continue
                }
            }

            // **粗体**
            if ((c == '*' || c == '_') && i + 1 < n && text[i + 1] == c) {
                val cc = c.toString() + c
                val close = text.indexOf(cc, i + 2)
                if (close > i + 1) {
                    val start = builder.length
                    parseInline(text.substring(i + 2, close), builder)
                    builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 2
                    continue
                }
            }

            // *斜体*
            if (c == '*' || c == '_') {
                val close = text.indexOf(c, i + 1)
                if (close > i + 1) {
                    val start = builder.length
                    parseInline(text.substring(i + 1, close), builder)
                    builder.setSpan(StyleSpan(Typeface.ITALIC), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 1
                    continue
                }
            }

            // ~~删除线~~
            if (c == '~' && i + 1 < n && text[i + 1] == '~') {
                val close = text.indexOf("~~", i + 2)
                if (close > i + 1) {
                    val start = builder.length
                    parseInline(text.substring(i + 2, close), builder)
                    builder.setSpan(StrikethroughSpan(), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 2
                    continue
                }
            }

            // `行内代码`
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i) {
                    val start = builder.length
                    builder.append(text.substring(i + 1, close))
                    builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(BackgroundColorSpan(CODE_BG), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = close + 1
                    continue
                }
            }

            // [链接](url)
            if (c == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket)
                    if (closeParen > closeBracket + 1) {
                        val start = builder.length
                        parseInline(text.substring(i + 1, closeBracket), builder)
                        builder.setSpan(ForegroundColorSpan(LINK_COLOR), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        builder.setSpan(UnderlineSpan(), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = closeParen + 1
                        continue
                    }
                }
            }

            builder.append(c)
            i++
        }
    }

    private fun appendLatex(latex: String, builder: SpannableStringBuilder) {
        val drawable = getOrCreateLatexDrawable(latex.trim()) ?: return
        val start = builder.length
        builder.append('\uFFFC')
        builder.setSpan(CenteredImageSpan(drawable), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendCodeBlock(builder: SpannableStringBuilder, code: String) {
        val start = builder.length
        builder.append(code)
        if (start < builder.length) {
            builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(BackgroundColorSpan(CODE_BG), start, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        appendBreak(builder)
    }

    private fun appendHr(builder: SpannableStringBuilder) {
        builder.append("――――――――――\n")
    }

    private fun appendBreak(builder: SpannableStringBuilder) {
        if (builder.isEmpty() || builder.last() != '\n') {
            builder.append('\n')
        }
    }

    private fun headingScale(level: Int): Float = when (level) {
        1 -> 1.4f
        2 -> 1.3f
        3 -> 1.2f
        4 -> 1.1f
        else -> 1.05f
    }

    // ================= LaTeX 渲染 =================

    private fun getOrCreateLatexDrawable(latex: String): Drawable? {
        // 检测样式变化，若变化则清空缓存
        if (textSize != cachedTextSize || currentTextColor != cachedTextColor) {
            clearCache()
            cachedTextSize = textSize
            cachedTextColor = currentTextColor
        }

        val cacheKey = "$latex\u0000$textSize\u0000$currentTextColor"
        val cached = drawableCache.get(cacheKey)
        if (cached != null) {
            return cached
        }

        val drawable = createLatexDrawable(latex) ?: return null
        drawableCache.put(cacheKey, drawable)
        return drawable
    }

    private fun createLatexDrawable(latex: String): Drawable? {
        return try {
            // 公式字号与文字保持一致（JLatexMath 的 textSize 单位是 px，和 TextView 一致）
            // 尝试用 \mathbf 包裹实现加粗，失败则回退到原始公式
            val boldLatex = "\\mathbf{$latex}"
            val drawable = try {
                JLatexMathDrawable.builder(boldLatex)
                    .textSize(textSize)
                    .color(currentTextColor)
                    .padding(4)
                    .build()
            } catch (e: Exception) {
                JLatexMathDrawable.builder(latex)
                    .textSize(textSize)
                    .color(currentTextColor)
                    .padding(4)
                    .build()
            }
            drawable
        } catch (e: Exception) {
            Timber.e(e, "Failed to render LaTeX: $latex")
            null
        }
    }

    /**
     * 自定义 ImageSpan，让公式图片垂直居中对齐到当前行文字，
     * 解决默认 ALIGN_BASELINE 位置偏移问题。
     * 不修改 FontMetrics，避免影响行高计算。
     */
    private class CenteredImageSpan(drawable: Drawable) : ImageSpan(drawable, ALIGN_BASELINE) {
        override fun getSize(
            paint: android.graphics.Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt?
        ): Int {
            return drawable.bounds.right
        }

        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint
        ) {
            val d = drawable
            val fm = paint.fontMetricsInt
            val fontHeight = fm.descent - fm.ascent
            val centerY = y + fm.ascent + fontHeight / 2
            val transY = centerY - d.bounds.height() / 2
            canvas.save()
            canvas.translate(x, transY.toFloat())
            d.draw(canvas)
            canvas.restore()
        }
    }
}