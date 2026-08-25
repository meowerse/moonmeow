// New JNI translation unit for moonmeow's own native entry points.
//
// Per CLAUDE.md §2 our native entry points live in their own file so an upstream
// sync never conflicts on simplejni.c. The only upstream edit this needs is the
// one line adding this file to Android.mk.
//
// JNI HAZARD (CLAUDE.md): the symbol below binds by *static mangled name*, so it
// must match `com.limelight.meow.viewport.MeowViewportBridge` exactly. There is no
// FindClass() counterpart here because nothing calls back into Java from this file;
// if that ever changes, the slash-form string must be kept in sync with the package
// or the app will build green and die at launch with ClassNotFoundException.

#include <Limelight.h>

#include <jni.h>

// Clamp into the uint16 range the wire format uses. The Java side clamps too
// (ViewportRect), but the implicit conversion at the LiSendViewportEvent() call
// site truncates silently, so do not rely on a single layer for it.
static uint16_t clampU16(jint value) {
    if (value < 0) {
        return 0;
    }
    if (value > 65535) {
        return 65535;
    }
    return (uint16_t)value;
}

JNIEXPORT jint JNICALL
Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport(JNIEnv *env, jclass clazz,
                                                                 jint x, jint y,
                                                                 jint width, jint height) {
    (void)env;
    (void)clazz;
    return LiSendViewportEvent(clampU16(x), clampU16(y), clampU16(width), clampU16(height));
}
