// New JNI translation unit for moonmeow's own native entry points.
//
// Per CLAUDE.md §2 our native entry points live in their own file so an upstream
// sync never conflicts on simplejni.c or callbacks.c. The only upstream edits this
// needs are the one line adding this file to Android.mk and the two lines in
// callbacks.c that point CONNECTION_LISTENER_CALLBACKS.setViewport at the function
// at the bottom of this file.
//
// JNI HAZARD (CLAUDE.md): the symbols below bind by *static mangled name*, so they
// must match `com.limelight.meow.viewport.MeowViewportBridge` exactly. Moving or
// renaming that class without renaming them produces a build that succeeds and
// dies at first call with UnsatisfiedLinkError.
//
// There is deliberately **no** FindClass() here even though this file now calls
// back into Java. FindClass() takes a slash-form class string that a package move
// does not update, and `nm -D` cannot see that mistake at all -- it is the exact
// shape of the bug that already shipped in this repo once. nativeInit() is handed
// the jclass by the JNI calling convention instead, so the class identity is
// carried by the mangled name and there is only one thing to keep in sync.
// MeowViewportBridgeContractTest derives that name from the class object and fails
// if a FindClass or a slash-form string appears here.

#include <Limelight.h>

#include <jni.h>

// Defined in callbacks.c, which owns the JavaVM handle and the per-thread attach.
// The viewport echo arrives on moonlight-common-c's async callback thread, which
// is not a Java thread, so it must go through this rather than caching a JNIEnv.
extern JNIEnv* GetThreadEnv(void);

// Resolved once from a Java thread in nativeInit(), which only runs when
// MeowViewportBridge is initialised -- that is, only when the feature is actually
// wired up. NULL otherwise, which makes the callback below inert with the
// preference off, so callbacks.c needs no knowledge of the preference.
static jclass MeowBridgeClass;
static jmethodID MeowBridgeOnViewportEchoMethod;

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

JNIEXPORT void JNICALL
Java_com_limelight_meow_viewport_MeowViewportBridge_nativeInit(JNIEnv *env, jclass clazz) {
    // A global ref: the local one dies with this frame, and the echo callback runs
    // on a different thread entirely.
    MeowBridgeClass = (*env)->NewGlobalRef(env, clazz);
    MeowBridgeOnViewportEchoMethod =
        (*env)->GetStaticMethodID(env, clazz, "onViewportEcho", "(IIIIII)V");
}

JNIEXPORT jint JNICALL
Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport(JNIEnv *env, jclass clazz,
                                                                 jint x, jint y,
                                                                 jint width, jint height) {
    (void)env;
    (void)clazz;
    return LiSendViewportEvent(clampU16(x), clampU16(y), clampU16(width), clampU16(height));
}

// ConnListenerSetViewport: the host's echo of the rectangle it actually applied,
// in the same reference space the request was sent in (the negotiated stream
// resolution), plus the captured desktop size -- 0/0 when the host did not report
// it.
//
// This is the ONLY capability signal this extension has. LiSendViewportEvent()
// returns 0 against every encrypted-Gen-7 host, implemented or not, so a client
// that reads its return value as capability detection talks to stock Sunshine
// forever. See its comment in ControlStream.c.
//
// Passed to Java as jint rather than jshort: these are uint16 values and CheckJNI
// aborts on anything above 32767 in a jshort parameter. The neighbouring callbacks
// in callbacks.c cast to (short) because their Java side re-reads the sign; there
// is no reason to inherit that here.
void MeowBridgeClSetViewport(uint16_t x, uint16_t y, uint16_t width, uint16_t height,
                             uint16_t desktopWidth, uint16_t desktopHeight) {
    JNIEnv* env;

    if (MeowBridgeClass == NULL || MeowBridgeOnViewportEchoMethod == NULL) {
        return;
    }

    env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, MeowBridgeClass, MeowBridgeOnViewportEchoMethod,
                                 (jint)x, (jint)y, (jint)width, (jint)height,
                                 (jint)desktopWidth, (jint)desktopHeight);

    // The Java side catches everything itself, so this should be unreachable.
    // Clear rather than detach: an exception left pending on this thread would
    // abort the next JNI call made from it, and that call belongs to a different
    // feature entirely.
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}
