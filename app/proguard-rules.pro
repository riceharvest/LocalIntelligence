-keepattributes *Annotation*

# Enum constants: Enum.name() is persisted (BackgroundAccessActivity stores Answer.Dismissed.name
# in preferences). R8 renaming a constant would make a stored value unreadable after an update.
-keepclassmembers enum dev.localintelligence.** { <fields>; }

# LiteRT-LM: its JNI (liblitertlm_jni.so) calls FindClass and GetMethodID on com.google.ai.edge.litertlm
# classes, getters and callbacks (onMessage, onDone, onError). The AAR ships no consumer rules.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class * implements com.google.ai.edge.litertlm.** { *; }

# sherpa-onnx (wake word): the AAR's proguard.txt is empty and its JNI reads the Kotlin config
# classes' fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# JSch (SSH sandbox): loads cipher and key-exchange classes by name from config strings.
-keep class com.jcraft.jsch.** { *; }

# JavaMail (Email plugin): providers are listed in META-INF and instantiated by reflection.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }

# Optional dependencies that R8 reports missing. The app never reaches them: each is a code path
# jsch or pdfbox takes only when a feature or provider it names is configured, which this app does not do.
# JSch's Windows Pageant agent (JNA), Kerberos (JGSS), Unix-socket factory, optional BouncyCastle
# ciphers and its log4j/slf4j logging back-ends, all off unless configured.
-dontwarn com.sun.jna.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
# pdfbox's JPEG 2000 image decoder (JPXFilter). Text extraction does not decode images.
-dontwarn com.gemalto.jp2.**
