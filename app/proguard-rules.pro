# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.termorganize.**$$serializer { *; }
-keepclasseswithmembers class app.termorganize.** {
    kotlinx.serialization.KSerializer serializer(...);
}
