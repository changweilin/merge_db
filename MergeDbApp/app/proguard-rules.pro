# ── Kotlin & Coroutines ───────────────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── Core DB / parser data classes (serialized by name reflection) ─────────────
-keep class com.mergedb.app.db.LatLon { *; }
-keep class com.mergedb.app.db.TspConfig { *; }
-keep class com.mergedb.app.db.TspResult { *; }
-keep class com.mergedb.app.db.TspRouteResult { *; }
-keepclassmembers enum com.mergedb.app.db.** { *; }

# ── Android storage / content-resolver (used via reflection) ─────────────────
-keep class android.provider.OpenableColumns { *; }

# ── Aggressive R8 optimisations ───────────────────────────────────────────────
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
