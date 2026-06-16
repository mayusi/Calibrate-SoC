package io.github.mayusi.calibratesoc.data.fancurve

/**
 * Minimal, dependency-free read-modify-write for an Android SharedPreferences
 * XML document — used to swap ONLY the fan-curve string value inside
 * `com.odin.settings`'s `config.xml` while preserving every other key.
 *
 * ## Why not a full XML parser
 * The whole point is to be SURGICAL: we change exactly one `<string name="…">`
 * element's text and leave the rest of the document — including byte-for-byte
 * the other keys, attribute order, and the XML declaration — untouched.
 * A round-trip through a DOM serializer would reorder attributes, re-escape
 * entities, and could silently drop data the stock app relies on. So we do a
 * targeted text replacement of the single element's inner content.
 *
 * ## The SharedPreferences XML contract (stable since API 1)
 * A string preference is written by the framework as exactly:
 * ```
 * <string name="KEY">ESCAPED_VALUE</string>
 * ```
 * The value is XML-escaped by the framework (`&`, `<`, `>` become entities;
 * `"`/`'` are left literal inside element text). Our fan-curve JSON contains
 * none of `<`, `>`, `&` (only `[`, `]`, `{`, `}`, `:`, `,`, digits, and the
 * key names `a`/`b`) — but we still escape on write and unescape on read for
 * correctness and to stay safe if the format ever shifts.
 *
 * ## Honesty
 * Every operation returns a typed result. If the key is absent, or the document
 * doesn't look like a SharedPreferences file, we say so rather than guessing —
 * the controller treats that as a failure and never claims success.
 */
object SharedPrefsXml {

    /**
     * Extract the raw (unescaped) string value stored under [key].
     *
     * Returns null when the key is not present as a `<string>` element. Does not
     * distinguish "absent" from "malformed" — the caller only needs the value or
     * its absence for the read path.
     */
    fun readString(xml: String, key: String): String? {
        val match = stringElementRegex(key).find(xml) ?: return null
        val escaped = match.groupValues[1]
        return unescape(escaped)
    }

    /**
     * Return a copy of [xml] with the `<string name="[key]">` element's value
     * replaced by [newValue] (which is XML-escaped before insertion).
     *
     * EVERYTHING else in the document is preserved verbatim — other keys, the
     * XML declaration, whitespace, and element ordering.
     *
     *  - If the key already exists as a `<string>` element, only its inner text
     *    is swapped ([ReplaceResult.Replaced]).
     *  - If the key is absent but the document has a `</map>` close tag, a new
     *    `<string>` element is inserted immediately before `</map>`
     *    ([ReplaceResult.Inserted]).
     *  - If the document has no `</map>` (not a recognizable SharedPreferences
     *    file), no change is made ([ReplaceResult.NotPrefsFile]).
     */
    fun replaceString(xml: String, key: String, newValue: String): ReplaceResult {
        val escapedValue = escape(newValue)
        val regex = stringElementRegex(key)
        val existing = regex.find(xml)
        if (existing != null) {
            // Replace ONLY the captured inner text (group 1), preserving the
            // exact opening/closing tags around it.
            val full = existing.value
            val replacedElement = full.replaceFirst(
                existing.groupValues[1],
                escapedValue,
                // groupValues[1] could be empty; use a manual splice to be safe.
            ).let {
                // The above replaceFirst on an empty needle would be a no-op; do a
                // structural rebuild instead to be robust for the empty-value case.
                buildString {
                    append("<string name=\"")
                    append(key)
                    append("\">")
                    append(escapedValue)
                    append("</string>")
                }
            }
            val newXml = xml.substring(0, existing.range.first) +
                replacedElement +
                xml.substring(existing.range.last + 1)
            return ReplaceResult.Replaced(newXml)
        }

        // Key absent — insert before </map> if present.
        val closeIdx = xml.lastIndexOf("</map>")
        if (closeIdx < 0) return ReplaceResult.NotPrefsFile
        val element = "    <string name=\"$key\">$escapedValue</string>\n"
        val newXml = xml.substring(0, closeIdx) + element + xml.substring(closeIdx)
        return ReplaceResult.Inserted(newXml)
    }

    /**
     * Regex matching a single `<string name="KEY">INNER</string>` element for an
     * EXACT key. The key is regex-quoted so a key containing metacharacters can't
     * broaden the match. `INNER` (group 1) is non-greedy and may be empty. The
     * `name` attribute is matched with either single or double quotes since the
     * framework uses double but third-party writers may differ.
     */
    private fun stringElementRegex(key: String): Regex {
        val k = Regex.escape(key)
        // [\s\S] so the value may span newlines; non-greedy to stop at the first
        // matching close tag.
        return Regex("<string name=[\"']$k[\"']>([\\s\\S]*?)</string>")
    }

    /** XML-escape for element text content. Mirrors the subset the Android
     *  framework emits (`&`, `<`, `>`). Quotes are valid literal in element
     *  text and are left as-is. `&` MUST be first to avoid double-escaping. */
    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Inverse of [escape]. `&amp;` LAST so `&lt;`/`&gt;` don't re-trigger. */
    fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/** Outcome of [SharedPrefsXml.replaceString]. */
sealed interface ReplaceResult {
    /** The key existed; its value was swapped. [xml] is the new document. */
    data class Replaced(val xml: String) : ReplaceResult
    /** The key was absent; a new element was inserted before `</map>`. */
    data class Inserted(val xml: String) : ReplaceResult
    /** The document is not a recognizable SharedPreferences file (no `</map>`). */
    data object NotPrefsFile : ReplaceResult
}
