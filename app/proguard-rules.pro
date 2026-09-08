# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers,allowshrinking class * { @kotlinx.serialization.SerialName <fields>; }
-keep,includedescriptorclasses class com.jugaad.agent.data.model.**$$serializer { *; }
-keepclassmembers class com.jugaad.agent.data.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional ML runtimes are reached by reflection — do not warn when absent.
-dontwarn org.pytorch.executorch.**
-dontwarn com.google.mediapipe.**
-keep class org.pytorch.executorch.** { *; }
-keep class com.google.mediapipe.tasks.genai.** { *; }

# JTransforms
-dontwarn org.jtransforms.**
-dontwarn pl.edu.icm.jlargearrays.**
