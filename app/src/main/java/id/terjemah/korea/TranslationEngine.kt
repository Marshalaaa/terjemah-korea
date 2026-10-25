package id.terjemah.korea

import android.graphics.Rect

/** Satu area teks di layar beserta terjemahannya (koordinat piksel layar). */
data class TextBox(val rect: Rect, val text: String)

/** Hangul: suku kata lengkap + jamo (termasuk ㅋ, ㅎ, ㅠ, ㅜ). */
private val KOREAN_CHARS = Regex("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]")

fun containsKorean(s: String): Boolean = KOREAN_CHARS.containsMatchIn(s)
