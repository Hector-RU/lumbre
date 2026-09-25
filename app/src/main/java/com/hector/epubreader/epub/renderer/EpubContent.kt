package com.hector.epubreader.epub.renderer

import com.hector.epubreader.data.preferences.ReaderPreferences
import org.jsoup.Jsoup

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
        val family = if (p.font == "publisher") "" else "font-family:${p.font} !important;"
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
        """.trimIndent()
        doc.head().appendElement("style").text(css)
        return doc.outerHtml()
    }
}
