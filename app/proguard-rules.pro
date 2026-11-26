# JSch memuat algoritma lewat nama class (refleksi) -> jangan diubah/dibuang.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# JSch memakai API opsional yang tidak ada di Android (JGSS, JNA, dll)
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn java.lang.management.**
-dontwarn sun.security.**

# Kelas JNI tun2socks (didaftarkan lewat RegisterNatives pada JNI_OnLoad)
-keep class hev.htproxy.TProxyService { *; }

# Model yang diserialisasi ke JSON
-keep class com.linvpn.app.data.** { *; }
-keepclassmembers class com.linvpn.app.data.** { *** Companion; }
