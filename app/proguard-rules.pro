# Keep Room entities
-keep class com.audiobookapp.data.model.** { *; }

# Keep POI (DOCX parsing)
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**

# Keep PDFBox
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# Keep epublib
-keep class nl.siegmann.epublib.** { *; }
-dontwarn nl.siegmann.epublib.**

# Keep Google Sign-In
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }

# Keep Media3
-keep class androidx.media3.** { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
