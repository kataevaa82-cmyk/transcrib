package ru.transcrib.app.export

import ru.transcrib.app.model.Transcript
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal Office Open XML (.docx) writer: a zip with the few parts Word, LibreOffice,
 * Google Docs and МойОфис need. No third-party libraries.
 */
object DocxWriter {
    private val speakerHex = listOf("2F6FEB", "E5492C", "0F9F8F", "D48806", "D6336C", "2B9A48", "0B8BC4", "8A5A2B")

    fun write(t: Transcript, meta: String, timestamps: Boolean, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", RELS)
            entry("word/_rels/document.xml.rels", DOC_RELS)
            entry("word/styles.xml", STYLES)
            entry("docProps/core.xml", core(t.title))
            entry("word/document.xml", document(t, meta, timestamps))
        }
    }

    private fun document(t: Transcript, meta: String, timestamps: Boolean): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        append("""<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr>""").append(run(t.title)).append("</w:p>")
        append("""<w:p><w:pPr><w:pStyle w:val="Meta"/></w:pPr>""").append(run(meta)).append("</w:p>")

        for (p in t.paragraphs()) {
            val head = t.hasSpeakers || timestamps
            if (head) {
                append("""<w:p><w:pPr><w:pStyle w:val="Speaker"/></w:pPr>""")
                if (t.hasSpeakers) {
                    val color = speakerHex[((p.speaker % speakerHex.size) + speakerHex.size) % speakerHex.size]
                    append(run(t.speakerName(p.speaker), bold = true, color = color))
                    if (timestamps) append(run("   "))
                }
                if (timestamps) append(run(Exporter.formatTime(p.start), color = "8A909C", size = 18))
                append("</w:p>")
            }
            append("""<w:p><w:pPr><w:pStyle w:val="Body"/></w:pPr>""").append(run(p.text)).append("</w:p>")
        }
        append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""")
        append("""<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1418" w:header="709" w:footer="709" w:gutter="0"/>""")
        append("</w:sectPr></w:body></w:document>")
    }

    private fun run(text: String, bold: Boolean = false, color: String? = null, size: Int? = null): String {
        val props = buildString {
            if (bold) append("<w:b/>")
            if (color != null) append("""<w:color w:val="$color"/>""")
            if (size != null) append("""<w:sz w:val="$size"/>""")
        }
        val rPr = if (props.isEmpty()) "" else "<w:rPr>$props</w:rPr>"
        return """<w:r>$rPr<w:t xml:space="preserve">${esc(text)}</w:t></w:r>"""
    }

    private fun esc(s: String) = buildString(s.length) {
        for (c in s) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                // Control characters are not allowed in XML 1.0.
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> append(c)
            }
        }
    }

    private fun core(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title>""" +
        """<dc:creator>Транскрибатор</dc:creator></cp:coreProperties>"""

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri" w:eastAsia="Calibri"/><w:sz w:val="24"/><w:lang w:val="ru-RU"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="300" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="80"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/><w:color w:val="15181E"/></w:rPr></w:style>
<w:style w:type="paragraph" w:customStyle="1" w:styleId="Meta"><w:name w:val="Meta"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:sz w:val="20"/><w:color w:val="8A909C"/></w:rPr></w:style>
<w:style w:type="paragraph" w:customStyle="1" w:styleId="Speaker"><w:name w:val="Speaker"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="40"/></w:pPr><w:rPr><w:sz w:val="21"/></w:rPr></w:style>
<w:style w:type="paragraph" w:customStyle="1" w:styleId="Body"><w:name w:val="Body"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="120"/></w:pPr></w:style>
</w:styles>"""
}
