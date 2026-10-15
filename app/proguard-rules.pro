# Nisaba release (R8). Libraries ship most of their own rules; these cover reflection the shrinker can't see.

# kotlinx.serialization: ink files (protobuf) and DeepSeek DTOs (JSON)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.nisaba.**$$serializer { *; }
-keepclassmembers class com.nisaba.** { *** Companion; }
-keepclasseswithmembers class com.nisaba.** { kotlinx.serialization.KSerializer serializer(...); }

# PdfBox-Android: fonts, filters and CMaps found by name at run time
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# Onyx pen and e-ink SDK: reaches the framework (android.onyx.*) and itself by reflection
-keep class com.onyx.** { *; }
-keep class android.onyx.** { *; }
-dontwarn com.onyx.**
-dontwarn android.onyx.**
-dontwarn com.android.internal.**

# HiddenApiBypass (opens just the hidden APIs the Onyx SDK needs; NisabaApp)
-keep class org.lsposed.hiddenapibypass.** { *; }

# OkHttp's optional TLS providers
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.jsse.**

# Our broadcast receiver and activities are named in the manifest (kept by AAPT rules); nothing else is reflective.

# Optional dependencies of transitive libraries (joda-time's converters, slf4j's bindings) that are never on the path
-dontwarn org.joda.convert.**
-dontwarn org.slf4j.**
