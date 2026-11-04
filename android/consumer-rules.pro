# Keep JNI entry points: they are called from native code by name.
-keepclasseswithmembernames class dev.localintelligence.android.inference.** { native <methods>; }

# LlamaBridge.TokenSink.onToken is called BY NAME from llama_jni.cpp (GetMethodID "onToken",
# "(Ljava/lang/String;)V") on the sink object, so the interface method and every implementation
# (lambdas included) must keep its name.
-keep interface dev.localintelligence.android.inference.LlamaBridge$TokenSink { void onToken(java.lang.String); }
-keepclassmembers class * implements dev.localintelligence.android.inference.LlamaBridge$TokenSink { void onToken(java.lang.String); }
