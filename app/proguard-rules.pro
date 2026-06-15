# Conservative ProGuard rules. Tighten after first release-build test passes.

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class io.github.mayusi.emutran.**$$serializer { *; }
-keepclassmembers class io.github.mayusi.emutran.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.mayusi.emutran.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *

# OkHttp / Okio (already shipping rules but be explicit)
-dontwarn okhttp3.**
-dontwarn okio.**

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# PServerWriter — R8 must not rename, inline, or reorder the volatile transactableCache
# field or the binder transact + cache logic. The RELEASE build's minification otherwise
# produces a class whose field layout differs from DEBUG, which hid the stale-cache bug.
-keep class io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter { *; }
-keepclassmembers class io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter {
    volatile io.github.mayusi.calibratesoc.data.tunables.writer.PServerWriter$Companion *;
    volatile kotlin.Boolean transactableCache;
    volatile long openUntilMs;
}
