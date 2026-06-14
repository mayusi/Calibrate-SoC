package io.github.mayusi.calibratesoc.data.share

/**
 * Thin abstraction over Base64 encoding so [PresetShareCodec] is pure-JVM
 * testable. The production implementation delegates to [android.util.Base64]
 * (NO_WRAP | URL_SAFE). Unit tests inject [JavaBase64Encoder] which uses
 * [java.util.Base64] instead — identical semantics, no Android runtime needed.
 */
interface Base64Encoder {
    fun encode(bytes: ByteArray): String
    fun decode(text: String): ByteArray
}

/**
 * Production implementation — uses android.util.Base64 with NO_WRAP + URL_SAFE
 * so the result is a single line with no padding characters that would confuse
 * copy-paste tools.
 */
class AndroidBase64Encoder : Base64Encoder {
    override fun encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
        )

    override fun decode(text: String): ByteArray =
        android.util.Base64.decode(
            text,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
        )
}
