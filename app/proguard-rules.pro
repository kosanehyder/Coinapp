# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.coinfutures.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.coinfutures.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp platform warnings
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
