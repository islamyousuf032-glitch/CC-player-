# JNI method names must remain stable in release builds.
-keepclasseswithmembernames,includedescriptorclasses class com.ccplayer.media.mcraw.McrawDecoder {
    native <methods>;
}

# Keep simple data models used across JNI/UI boundaries.
-keep class com.ccplayer.media.mcraw.McrawInfo { *; }
