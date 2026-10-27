# frpc 客户端 ProGuard 规则
# Kotlin/kotlinx.serialization 需要保留生成的 serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.frpc.shared.** {
    *** Companion;
}
-keepclasseswithmembers class com.frpc.shared.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.frpc.**$$serializer { *; }
-keepclassmembers class com.frpc.** {
    *** Companion;
}
-keepclasseswithmembers class com.frpc.** {
    kotlinx.serialization.KSerializer serializer(...);
}
