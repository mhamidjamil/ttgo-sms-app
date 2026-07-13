# R8 configuration for the release build.
#
# The release variant minifies AND shrinks resources, and this app had never
# produced one before, so everything below is about code that is reached by
# name at runtime rather than by a reference R8 can see.

# ── Reflection attributes ─────────────────────────────────────────────────────
# proguard-android-optimize.txt supplies only "-keepattributes *Annotation*".
# Firestore's CustomClassMapper reads Method.getGenericParameterTypes() and
# Field.getGenericType() to work out what a property holds; without Signature
# every List/Map property on a DTO resolves to a raw type and toObject() throws
# at runtime, in release only.
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# ── App models ────────────────────────────────────────────────────────────────
# Firestore maps these by field name, and @PropertyName ties Kotlin names to the
# snake_case stored on the document. Renaming either half breaks every read.
-keep class com.textgate.app.data.model.** { *; }
-keepclassmembers class com.textgate.app.data.model.** { *; }

# ── Firebase ──────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# ── Koin ──────────────────────────────────────────────────────────────────────
-keep class org.koin.** { *; }

# ── Kotlin ────────────────────────────────────────────────────────────────────
# Kotlin emits these for null checks and reflection metadata.
-keepclassmembers class ** {
    @kotlin.jvm.JvmStatic *;
}
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**

# Keep the line numbers useful in a Play crash report while still obfuscating.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
