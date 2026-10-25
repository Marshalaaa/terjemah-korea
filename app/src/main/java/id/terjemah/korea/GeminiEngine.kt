package id.terjemah.korea

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mode online: tangkapan layar dikirim ke Gemini (Google AI Studio, ada tier gratis).
 * Gemini membaca teks Korea sekaligus menerjemahkan sesuai konteks (Weverse, media sosial).
 */
class GeminiEngine(private val apiKey: String, private val model: String) {

    /** Kuota (gratis) habis atau terlalu banyak permintaan -> HTTP 429. */
    class QuotaException(msg: String) : IOException(msg)

    suspend fun translate(screen: Bitmap, exclude: Rect?): List<TextBox> = withContext(Dispatchers.IO) {
        // Perkecil supaya cepat & hemat kuota; tutup tombol 번역 agar tidak ikut terbaca.
        val scale = min(1f, MAX_SIDE / max(screen.width, screen.height).toFloat())
        val w = (screen.width * scale).roundToInt()
        val h = (screen.height * scale).roundToInt()
        val img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(img)
        c.drawBitmap(screen, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        if (exclude != null) {
            c.drawRect(
                exclude.left * scale, exclude.top * scale, exclude.right * scale, exclude.bottom * scale,
                Paint().apply { color = Color.GRAY }
            )
        }
        val jpeg = ByteArrayOutputStream().use { bos ->
            img.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            bos.toByteArray()
        }
        img.recycle()

        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
            .put(JSONObject().put("text", PROMPT))

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.3)
            )

        parse(post(body), screen.width, screen.height)
    }

    /** Kirim permintaan teks kecil untuk mengecek API key. Mengembalikan balasan model. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put(
            "contents", JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts", JSONArray().put(
                        JSONObject().put(
                            "text",
                            "Terjemahkan ke bahasa Indonesia: 고마워요"
                        )
                    )
                )
            )
        )
        post(body).trim()
    }

    private fun post(body: JSONObject): String {
        if (apiKey.isBlank()) throw IOException("API key Gemini belum diisi")
        val conn = (URL("$BASE/$model:generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(raw).getJSONObject("error").getString("message")
                }.getOrDefault(raw.take(200))
                if (code == 429) throw QuotaException(msg)
                throw IOException("Gemini $code: $msg")
            }

            val json = JSONObject(raw)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val reason = json.optJSONObject("promptFeedback")?.optString("blockReason")
                throw IOException("Gemini tidak membalas" + (reason?.let { " ($it)" } ?: ""))
            }
            val out = StringBuilder()
            val partsArr = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return ""
            for (i in 0 until partsArr.length()) {
                val p = partsArr.getJSONObject(i)
                if (!p.optBoolean("thought", false)) out.append(p.optString("text"))
            }
            return out.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** box_2d = [ymin, xmin, ymax, xmax] dinormalisasi 0..1000 (format bawaan Gemini). */
    private fun parse(reply: String, screenW: Int, screenH: Int): List<TextBox> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { JSONArray(reply.substring(start, end + 1)) }.getOrNull()
            ?: return emptyList()

        val out = mutableListOf<TextBox>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val b = o.optJSONArray("box_2d") ?: continue
            if (b.length() < 4) continue
            val text = o.optString("id").trim()
            if (text.isEmpty()) continue
            val y1 = (b.optDouble(0) / 1000.0 * screenH).roundToInt().coerceIn(0, screenH)
            val x1 = (b.optDouble(1) / 1000.0 * screenW).roundToInt().coerceIn(0, screenW)
            val y2 = (b.optDouble(2) / 1000.0 * screenH).roundToInt().coerceIn(0, screenH)
            val x2 = (b.optDouble(3) / 1000.0 * screenW).roundToInt().coerceIn(0, screenW)
            val r = Rect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
            if (r.width() > 4 && r.height() > 4) out += TextBox(r, text)
        }
        return out
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_SIDE = 1600f

        private val PROMPT = """
            You are an expert Korean -> Indonesian translator for K-pop fan platforms
            such as Weverse. The image is a phone screenshot of a social app or web page.

            Find every piece of Korean text written by people: artist posts, fan posts,
            comments, artist replies, live-stream chat, notices, captions, and Korean text
            inside photos or images.
            Treat one post, one comment or one chat message as ONE item, even when it wraps
            over several lines. Never merge two different comments or messages into one item.

            Do NOT include:
            - app or website interface text: buttons, tabs, menus, like/comment counts,
              timestamps, "translate"/"see translation" links, input placeholders
            - usernames or nicknames standing alone, and lines that are only hashtags
            - text that is not Korean (English, Indonesian, Japanese, ...), including the
              app's own translation shown under a post

            Translate into natural, casual Indonesian the way Indonesian K-pop fans write.
            Keep the writer's tone: warm, playful, aegyo, teasing, formal notice, etc.
            Keep names of people, groups and fandoms in their usual romanized form.
            Keep fan address terms romanized: oppa, unnie, hyung, noona, sunbae, maknae.
            Internet slang: ㅋㅋㅋ -> wkwk, ㅎㅎ -> hehe, ㅠㅠ/ㅜㅜ stay as ㅠㅠ; keep emoji.
            Do not add explanations or notes. Keep the translation concise.

            Return ONLY a JSON array, in reading order (top to bottom):
            [{"box_2d":[ymin,xmin,ymax,xmax],"src":"original Korean","id":"terjemahan Indonesia"}]
            box_2d must tightly cover the ORIGINAL Korean text of that item, normalized to 0-1000.
            Return [] if there is no Korean text.
        """.trimIndent()
    }
}
