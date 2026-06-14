package io.github.mayusi.calibratesoc.data.util

import okio.FileSystem
import okio.Path

/**
 * Lightweight sysfs read helper. Returns null on missing path, blank content,
 * or any I/O error — the standard "degrade gracefully" contract used throughout
 * this codebase.
 *
 * Used by [GpuLoadSampler], [NoopWriter], and [ShizukuWriter] which all have
 * identical read semantics. [SysfsProber.readStringOrNull] keeps its own
 * try/catch (it catches [okio.IOException] specifically) and is left untouched.
 */
fun FileSystem.readSysfsString(path: Path): String? =
    if (!exists(path)) null
    else runCatching { read(path) { readUtf8() }.trim().ifBlank { null } }.getOrNull()
