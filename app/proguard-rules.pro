# Nisaba R8 keep rules (M6 / audit P2-2).
# Minification is on for release; these keep the reflection-adjacent pieces alive.

# ---- kotlinx.serialization -----------------------------------------------
# The plugin generates a $$serializer class per @Serializable model; R8 must
# keep them, their descriptor classes, and the Companion lookups that the
# generated serializers() functions use.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.nisaba.**$$serializer { *; }
-keepclassmembers class com.nisaba.** {
    *** Companion;
}
-keepclasseswithmembers class com.nisaba.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- PdfBox-Android (com.tom_roush.pdfbox) -------------------------------
# The engine resolves fonts/encodings via its own lookup tables and loads
# resources reflectively; keep the whole package. It ships its own consumer
# rules, but be explicit: this is the working PDF engine on the Boox.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# ---- Onyx/Boox EPD reflection (Epd.kt, Oec.kt) ---------------------------
# These classes live in the system framework, never in the APK — nothing to
# keep, but silence any "library class" warnings.
-dontwarn com.onyx.**

# ---- Misc warnings from optional dependencies ------------------------------
-dontwarn org.slf4j.**
# Tink (EncryptedSharedPreferences backend) references errorprone annotations
# that are compile-time-only and never shipped.
-dontwarn com.google.errorprone.annotations.**
