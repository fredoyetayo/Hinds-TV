# Media3, Room, OkHttp, Coil and kotlinx.serialization ship their own consumer rules.
# We parse JSON as JsonElement trees (no @Serializable reflection), so nothing extra is needed.

# Keep line numbers so crash stack traces from the device are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
