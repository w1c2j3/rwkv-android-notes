package com.example.rwkvnotes.ingest

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class IngestedDocument(
    val fileName: String,
    val mediaType: String,
    val plainText: String,
)

@Singleton
class DocumentIngestionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun ingest(uri: Uri): Result<IngestedDocument> {
        return runCatching {
            val resolver = context.contentResolver
            val fileName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex("_display_name")
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            } ?: "unknown"
            val mime = resolver.getType(uri).orEmpty()
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val text = when {
                mime.contains("pdf") || ext == "pdf" -> parsePdf(uri)
                mime.contains("word") || ext == "docx" -> parseDocx(uri)
                ext == "md" || ext == "markdown" || mime.contains("markdown") -> parseUtf8(uri)
                ext == "tex" || mime.contains("tex") -> parseLatex(uri)
                else -> parseUtf8(uri)
            }
            IngestedDocument(fileName = fileName, mediaType = mime, plainText = text.trim())
        }
    }

    private fun parseUtf8(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun parsePdf(uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        if (bytes.isEmpty()) return ""
        PDDocument.load(bytes).use { doc ->
            return PDFTextStripper().getText(doc)
        }
    }

    private fun parseDocx(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        return extractDocxText(input)
    }

    private fun parseLatex(uri: Uri): String {
        val raw = parseUtf8(uri)
        if (raw.isBlank()) return raw
        return raw
            .replace(Regex("(?s)\\\\begin\\{equation\\}(.*?)\\\\end\\{equation\\}")) {
                "\n[FORMULA_BLOCK] ${it.groupValues[1].trim()} [/FORMULA_BLOCK]\n"
            }
            .replace(Regex("(?s)\\$\\$(.*?)\\$\\$")) {
                "\n[FORMULA_BLOCK] ${it.groupValues[1].trim()} [/FORMULA_BLOCK]\n"
            }
            .replace(Regex("\\$(.+?)\\$")) {
                "[FORMULA_INLINE ${it.groupValues[1].trim()}]"
            }
    }
}

private fun extractDocxText(input: InputStream): String {
    ZipInputStream(input).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name == "word/document.xml") {
                val xml = zip.readBytes().toString(Charsets.UTF_8)
                return xml
                    .replace(Regex("<w:p[^>]*>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace(Regex("\n+"), "\n")
                    .trim()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return ""
}
