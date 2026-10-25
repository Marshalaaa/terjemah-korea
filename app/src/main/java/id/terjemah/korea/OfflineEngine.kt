package id.terjemah.korea

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mode offline: OCR Korea + terjemahan Korea->Indonesia, semuanya di HP (Google ML Kit).
 *
 * ML Kit mengembalikan teks per baris. Baris-baris yang bertumpuk rapat dan rata kiri digabung
 * jadi satu paragraf (satu postingan / komentar), lalu diterjemahkan sebagai satu kalimat utuh.
 */
class OfflineEngine {

    private class Line(val rect: Rect, val text: String) {
        val size: Int get() = max(1, min(rect.width(), rect.height()))
    }

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val translator = newTranslator()

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
    }

    /** @param exclude area yang diabaikan (tombol mengambang kita sendiri). */
    suspend fun translate(screen: Bitmap, exclude: Rect?): List<TextBox> {
        val result = recognizer.process(InputImage.fromBitmap(screen, 0)).await()
        val lines = toLines(result, exclude)

        val out = mutableListOf<TextBox>()
        for (group in groupLines(lines)) {
            val rect = union(group)
            val ko = group.sortedBy { it.rect.top }.joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ").trim()
            if (!isMostlyKorean(ko)) continue // lewati tombol/UI, angka, nama pengguna Latin
            val id = cache[ko] ?: translateText(ko).also { cache[ko] = it }
            if (id.isNotEmpty()) out += TextBox(rect, id)
        }
        return out
    }

    private fun toLines(result: Text, exclude: Rect?): List<Line> {
        val lines = mutableListOf<Line>()
        for (block in result.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            val t = line.text.trim()
            if (t.isEmpty()) continue
            if (exclude != null && Rect.intersects(r, exclude)) continue
            lines += Line(Rect(r), t)
        }
        return lines
    }

    /** Gabungkan baris yang satu paragraf (union-find). */
    private fun groupLines(lines: List<Line>): List<List<Line>> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            if (samePara(lines[i], lines[j])) parent[find(i)] = find(j)
        }
        return lines.indices.groupBy { find(it) }.values.map { idx -> idx.map { lines[it] } }
    }

    /**
     * Satu paragraf: baris bertumpuk dengan jarak kecil, tinggi huruf mirip, dan rata kiri.
     * Syarat rata kiri & tinggi huruf mencegah nama pengguna/waktu ikut tergabung ke komentar.
     */
    private fun samePara(a: Line, b: Line): Boolean {
        val size = (a.size + b.size) / 2f
        val vGap = max(0, max(a.rect.top, b.rect.top) - min(a.rect.bottom, b.rect.bottom))
        val hOverlap = min(a.rect.right, b.rect.right) - max(a.rect.left, b.rect.left)
        val similarHeight = max(a.size, b.size) <= min(a.size, b.size) * 1.4f
        return vGap <= size * 0.5f &&
            hOverlap > min(a.rect.width(), b.rect.width()) * 0.2f &&
            abs(a.rect.left - b.rect.left) <= size * 2f &&
            similarHeight
    }

    /** Minimal sepertiga huruf Korea: postingan sering bercampur kata Inggris, angka, emoji. */
    private fun isMostlyKorean(s: String): Boolean {
        val core = s.filter { !it.isWhitespace() && it !in PUNCT && !Character.isSurrogate(it) }
        val n = core.count { containsKorean(it.toString()) }
        return n >= 2 && n * 3 >= core.length
    }

    /**
     * Terjemahan ML Kit sering kacau untuk slang chat (ㅋㅋㅋ, ㅎㅎ, ㅠㅠ), jadi slang itu
     * dicopot dulu, kalimatnya diterjemahkan, lalu padanan Indonesianya ditempel di akhir.
     */
    private suspend fun translateText(src: String): String {
        val tails = mutableListOf<String>()
        if (Regex("ㅋ{2,}").containsMatchIn(src)) tails += "wkwk"
        if (Regex("ㅎ{2,}").containsMatchIn(src)) tails += "hehe"
        if (Regex("[ㅠㅜ]{2,}").containsMatchIn(src)) tails += "ㅠㅠ"
        val body = src.replace(Regex("[ㅋㅎㅠㅜ]{2,}"), " ").replace(Regex("\\s+"), " ").trim()
        val translated = if (containsKorean(body)) translator.translate(body).await().trim() else body
        return (listOf(translated) + tails).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun union(group: List<Line>): Rect {
        val r = Rect(group.first().rect)
        for (l in group) r.union(l.rect)
        return r
    }

    fun close() {
        recognizer.close()
        translator.close()
    }

    companion object {
        private const val PUNCT = "!?.,~-…·\"'()[]♡♥♪★☆^_:;/"

        private fun newTranslator(): Translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(TranslateLanguage.INDONESIAN)
                .build()
        )

        /** Unduh model terjemahan Korea + Indonesia (sekali saja, butuh internet). */
        suspend fun downloadModels() {
            val t = newTranslator()
            try {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            } finally {
                t.close()
            }
        }

        /** Apakah model Korea & Indonesia sudah ada di HP. */
        suspend fun modelsReady(): Boolean {
            val mgr = RemoteModelManager.getInstance()
            val ko = TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build()
            val id = TranslateRemoteModel.Builder(TranslateLanguage.INDONESIAN).build()
            return mgr.isModelDownloaded(ko).await() && mgr.isModelDownloaded(id).await()
        }
    }
}
