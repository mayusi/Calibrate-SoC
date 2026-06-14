package io.github.mayusi.calibratesoc.data.util

/**
 * Strips filesystem-hostile characters from an arbitrary string and returns a
 * name safe for VFAT (sdcard), ext4, and Android's MediaStore.
 *
 * Rules:
 *  - Keep A-Z, a-z, 0-9, dot, underscore, hyphen.
 *  - Replace every run of other characters with a single underscore.
 *  - Trim leading/trailing underscores.
 *  - If the result is blank, return [fallback].
 *
 * [fallback] must itself be a safe literal (caller's responsibility — it is
 * never transformed). Pass "unknown" for device model names, "preset" for
 * preset names.
 */
fun toSafeFilename(name: String, fallback: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { fallback }
