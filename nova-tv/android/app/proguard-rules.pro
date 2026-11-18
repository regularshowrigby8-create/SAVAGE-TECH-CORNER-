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
