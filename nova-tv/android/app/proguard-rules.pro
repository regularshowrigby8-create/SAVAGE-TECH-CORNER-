# Release shrinking rules.
#
# Two things in nova-tv are referenced by name at runtime and must survive R8:
# kotlinx.serialization's generated serializers, and the addon/catalog JSON
# models they describe.

# kotlinx.serialization: keep the generated serializer plumbing.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.nova.tv.**$$serializer { *; }
-keepclassmembers class dev.nova.tv.** {
    *** Companion;
}
-keepclasseswithmembers class dev.nova.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Media3 ship their own consumer rules; nothing to add.

# Keep line numbers so a shipped crash is still diagnosable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Default values in @Serializable data classes generate a synthetic
# `-serializer` constructor with a bitmask. R8 full mode strips synthetic
# members it believes are unused, and the result is an InternalError at the
# first decode rather than a build failure -- the worst kind, because it passes
# every check here and fails on a viewer's device.
-keepclassmembers class dev.nova.tv.data.model.** {
    synthetic <init>(...);
}
-keepclassmembers,allowobfuscation class * implements kotlinx.serialization.KSerializer {
    <init>(...);
}

# kotlinx.serialization reads these by name.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,Exceptions,InnerClasses
-dontwarn kotlinx.serialization.**

# FFmpeg audio decoder (Jellyfin media3-ffmpeg-decoder): the native library
# calls back into these classes by name over JNI, which R8 cannot see.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
