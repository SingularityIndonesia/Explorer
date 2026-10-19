# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ---- Remote access (libadb-android) ----
# libadb looks up Conscrypt's provider by class name for the TLS 1.3 pairing handshake.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
# SPAKE2 pairing uses JNI.
-keep class io.github.muntashirakon.crypto.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
# BouncyCastle (certificate generation) references JDK-only classes that Android doesn't ship.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---- SSH servers (JSch) ----
# JSch instantiates ciphers, key exchanges and signatures from class names in its config.
-keep class com.jcraft.jsch.** { *; }
# Optional integrations JSch can use on desktop JVMs (logging, Unix sockets, PKCS#11).
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn sun.security.pkcs11.**
# Kerberos (gssapi-with-mic) login: not available on Android and not used.
-dontwarn org.ietf.jgss.**
