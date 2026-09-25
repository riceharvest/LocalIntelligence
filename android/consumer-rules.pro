# Keep JNI entry points: they are called from native code by name.
-keepclasseswithmembernames class dev.pidroid.android.inference.** { native <methods>; }
