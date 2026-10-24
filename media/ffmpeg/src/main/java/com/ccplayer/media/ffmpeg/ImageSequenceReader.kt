package com.ccplayer.media.ffmpeg

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.util.Locale

data class SequenceFrame(val uri: Uri, val name: String, val frameNumber: Long?)
data class ImageSequenceInfo(
    val treeUri: Uri,
    val frames: List<SequenceFrame>,
    val gapCount: Int,
) {
    init { require(frames.isNotEmpty()) { "Image sequence cannot be empty" } }
}

/** SAF tree reader with deterministic natural sorting and explicit (non-silent) gap reporting. */
class ImageSequenceReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun read(treeUri: Uri): ImageSequenceInfo = withContext(Dispatchers.IO) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val frames = mutableListOf<SequenceFrame>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(mimeColumn)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR || extension(name) !in supportedExtensions) continue
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                frames += SequenceFrame(documentUri, name, trailingNumber(name))
            }
        }
        val sorted = frames.sortedWith(naturalComparator)
        require(sorted.isNotEmpty()) { "The selected folder contains no supported EXR, DPX, PNG, TIFF, JPEG or RAW image frames" }
        val numbered = sorted.mapNotNull(SequenceFrame::frameNumber)
        val gaps = if (numbered.size == sorted.size && numbered.size > 1) {
            numbered.zipWithNext().sumOf { (left, right) -> (right - left - 1).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        } else 0
        ImageSequenceInfo(treeUri, sorted, gaps)
    }

    private fun trailingNumber(name: String): Long? {
        val base = name.substringBeforeLast('.')
        val digits = base.takeLastWhile(Char::isDigit)
        return digits.toLongOrNull()
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private companion object {
        val supportedExtensions = setOf("exr", "dpx", "cin", "png", "tif", "tiff", "jpg", "jpeg", "j2c", "j2k", "jp2", "dng", "tga", "bmp", "webp")
        val chunks = Regex("\\d+|\\D+")
        val naturalComparator = Comparator<SequenceFrame> { left, right ->
            val leftChunks = chunks.findAll(left.name.lowercase(Locale.ROOT)).map(MatchResult::value).toList()
            val rightChunks = chunks.findAll(right.name.lowercase(Locale.ROOT)).map(MatchResult::value).toList()
            var result = 0
            for (index in 0 until minOf(leftChunks.size, rightChunks.size)) {
                val a = leftChunks[index]
                val b = rightChunks[index]
                result = if (a.first().isDigit() && b.first().isDigit()) {
                    BigInteger(a).compareTo(BigInteger(b))
                } else a.compareTo(b)
                if (result != 0) break
            }
            if (result != 0) result else leftChunks.size.compareTo(rightChunks.size)
        }
    }
}
