# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# KeyConfigHelper - keep classes and fields for Gson
-keep class com.limelight.utils.KeyConfigHelper {*;}
-keep class com.limelight.utils.KeyConfigHelper$ShortcutFile {*;}
-keep class com.limelight.utils.KeyConfigHelper$Shortcut {*;}

# Keep TensorFlow Lite GPU delegate classes that R8 might incorrectly remove
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.opencv.** { *; }

# Profiles
-keep class com.limelight.profiles.ProfilesManager$ProfilesData {*;}
-keep class com.limelight.profiles.SettingsProfile {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# viewport-follow (docs/meow/TOUCHPOINTS.md). onViewportEcho() is called ONLY from
# meowjni.c, so R8 sees no caller and strips it from the release dex -- verified, it
# really did. GetStaticMethodID() would then return NULL, the host's echo would be
# silently dropped, and capability detection would never succeed: a green build in
# which the feature simply never engages. The native methods are kept for the same
# reason in reverse (nothing but JNI resolves them).
# This file is outside app/src, so the `git grep -n 'MEOW-TOUCH' -- app/src` audit in
# CLAUDE.md would not see a marker here; the site is recorded in TOUCHPOINTS.md
# instead, deliberately without the token, so that audit stays exact.
-keepclassmembers class com.limelight.meow.viewport.MeowViewportBridge {
    static void onViewportEcho(int, int, int, int, int, int);
    native <methods>;
}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**