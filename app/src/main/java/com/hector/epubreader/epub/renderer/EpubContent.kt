package com.hector.epubreader.epub.renderer

import com.hector.epubreader.data.preferences.ReaderPreferences
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

object EpubContent {
    data class Colors(val background: String, val text: String, val link: String, val selection: String, val dark: Boolean)

    fun render(bytes: ByteArray, preferences: ReaderPreferences, fontScale: Float = 1f, colors: Colors = Colors(
        preferences.palette.background, preferences.palette.text, preferences.palette.link, preferences.palette.selection,
        preferences.palette.name in setOf("DARK", "OLED")
    )): String {
        val p = preferences.validated()
        val doc = Jsoup.parse(bytes.inputStream(), null, "")
        doc.select("script, iframe, frame, frameset, object, embed, applet, form, input, button, textarea, select, base, meta[http-equiv], foreignObject, audio, video").remove()
        doc.allElements.forEach { element ->
            element.attributes().asList().filter { it.key.startsWith("on", true) || it.key.equals("srcdoc", true) }.forEach { element.removeAttr(it.key) }
            listOf("href", "src", "xlink:href", "action").forEach { attr ->
                val value = element.attr(attr).trim().lowercase()
                if (value.startsWith("javascript:") || value.startsWith("intent:") || value.startsWith("file:") || value.startsWith("content:") || value.startsWith("data:text/html")) element.removeAttr(attr)
            }
        }
        doc.head().prependElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
        when (p.readingMode) {
            "pages" -> {
                val body = doc.body()
                val flow = body.appendElement("div").attr("id", "reader-flow")
                while (body.childNodeSize() > 1) flow.appendChild(body.childNode(0))
            }
            "paragraphs" -> makeParagraphSlides(doc.body())
        }
        val family = if (p.font == "publisher") "" else "font-family:${p.font} !important;"
        // Keep root vertical overflow enabled: Chromium can otherwise clip multicol content entirely.
        val pageCss = if (p.readingMode == "pages") """
            html { overflow-x:auto !important; overflow-y:hidden !important; }
            body { width:100vw !important; height:var(--reader-viewport-height, 100vh) !important; padding:0 !important; overflow:visible !important; }
            #reader-flow { width:100vw; height:var(--reader-viewport-height, 100vh) !important; box-sizing:border-box;
                padding:${p.verticalMargin}px ${p.horizontalMargin}px;
                column-width:calc(100vw - ${p.horizontalMargin * 2}px) !important;
                column-gap:${p.horizontalMargin * 2}px !important; column-fill:auto !important; }
            img, svg { max-height:calc(var(--reader-viewport-height, 100vh) - ${p.verticalMargin * 2}px) !important; break-inside:avoid; }
            figure, table { break-inside:avoid; }
        """.trimIndent() else ""
        val paragraphCss = if (p.readingMode == "paragraphs") """
            html { overflow-x:auto !important; overflow-y:hidden !important; }
            body { display:flex !important; width:max-content !important; min-width:100vw;
                height:var(--reader-viewport-height, 100vh) !important; padding:0 !important; align-items:stretch; }
            body > .reader-paragraph { flex:0 0 100vw; width:100vw; height:var(--reader-viewport-height, 100vh);
                box-sizing:border-box; overflow-y:auto; overflow-x:hidden;
                padding:${p.verticalMargin}px ${p.horizontalMargin}px; }
        """.trimIndent() else ""
        val css = """
            :root { color-scheme: ${if (colors.dark) "dark" else "light"}; }
            html, body { background:${colors.background} !important; color:${colors.text} !important; }
            html { overflow-wrap:break-word; }
            body { margin:0 !important; padding:${p.verticalMargin}px ${p.horizontalMargin}px !important;
                font-size:${p.fontSize * fontScale.coerceIn(0.8f, 2f)}px !important; line-height:${p.lineHeight} !important; $family }
            body * { color:inherit !important; background-color:transparent !important; max-width:100%; }
            p, li, blockquote, div { font-size:inherit !important; line-height:inherit !important; $family }
            p { margin-block:${p.paragraphSpacing}em !important; text-align:${if (p.justified) "justify" else "start"} !important; }
            img, svg { max-width:100% !important; height:auto; object-fit:contain; }
            pre { white-space:pre-wrap; } table { max-width:100%; overflow-wrap:anywhere; }
            a, a * { color:${colors.link} !important; }
            ::selection { background:${colors.selection} !important; color:${colors.text} !important; }
            $pageCss
            $paragraphCss
        """.trimIndent()
        doc.head().appendElement("style").text(css)
        return doc.outerHtml()
    }

    private fun makeParagraphSlides(body: Element) {
        val selector = "p, li, h1, h2, h3, h4, h5, h6, pre, figure, table, img, svg, hr"
        val slides = mutableListOf<Node>()
        fun add(node: Node, ancestors: List<Element>) {
            var content = node.clone()
            for (ancestor in ancestors.asReversed()) {
                val wrapper = ancestor.clone().empty().removeAttr("id")
                wrapper.appendChild(content)
                content = wrapper
            }
            slides.add(content)
        }
        fun collect(parent: Element, ancestors: List<Element>) {
            val inline = mutableListOf<Node>()
            fun flushInline() {
                if (inline.any { it is Element || it is TextNode && it.text().isNotBlank() }) {
                    val paragraph = Element("p")
                    inline.forEach { paragraph.appendChild(it.clone()) }
                    add(paragraph, ancestors)
                }
                inline.clear()
            }
            parent.childNodes().forEach { node ->
                if (node !is Element) { inline.add(node); return@forEach }
                when {
                    node.`is`(selector) -> { flushInline(); add(node, ancestors) }
                    node.select(selector).isNotEmpty() -> {
                        flushInline()
                        collect(node, ancestors + listOf(node))
                    }
                    node.isBlock -> { flushInline(); add(node, ancestors) }
                    else -> inline.add(node)
                }
            }
            flushInline()
        }
        collect(body, emptyList())
        body.empty()
        slides.forEach { slide -> body.appendElement("div").addClass("reader-paragraph").appendChild(slide) }
    }
}
