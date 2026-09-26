// New JNI translation unit for moonmeow's own native entry points.
//
// Per CLAUDE.md §2 our native entry points live in their own file so an upstream
// sync never conflicts on simplejni.c or callbacks.c. The only upstream edits this
// needs are the one line adding this file to Android.mk and the include plus the
// struct members in callbacks.c that point CONNECTION_LISTENER_CALLBACKS at the
// callbacks at the bottom of this file.
//
// JNI HAZARD (CLAUDE.md): the symbols below bind by *static mangled name*, so they
// must match `com.limelight.meow.viewport.MeowViewportBridge` and
// `com.limelight.meow.stream.MeowStreamBridge` exactly. Moving or renaming either
// class without renaming them produces a build that succeeds and dies at first call
// with UnsatisfiedLinkError.
//
// There is deliberately **no** FindClass() here even though this file now calls
// back into Java. FindClass() takes a slash-form class string that a package move
// does not update, and `nm -D` cannot see that mistake at all -- it is the exact
// shape of the bug that already shipped in this repo once. nativeInit() is handed
// the jclass by the JNI calling convention instead, so the class identity is
// carried by the mangled name and there is only one thing to keep in sync.
// MeowViewportBridgeContractTest and MeowStreamBridgeContractTest derive those names
// from the class objects and fail if a FindClass or a slash-form string appears here.

#include "meowjni.h"

#include <Limelight.h>

#include <jni.h>
#include <string.h>

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
        (*env)->GetStaticMethodID(env, clazz, "onViewportEcho", "(IIIIIII)V");
}

JNIEXPORT jint JNICALL
Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport(JNIEnv *env, jclass clazz,
                                                                 jint x, jint y,
                                                                 jint width, jint height,
                                                                 jboolean force) {
    (void)env;
    (void)clazz;
    // `force` is for the capability probe only: it skips the library's "the host
    // already has this rectangle" check, without which the retry probe -- the same
    // rectangle by definition -- would be deduplicated away and never sent.
    if (force) {
        return LiSendViewportEventForced(clampU16(x), clampU16(y), clampU16(width), clampU16(height));
    }
    return LiSendViewportEvent(clampU16(x), clampU16(y), clampU16(width), clampU16(height));
}

// ConnListenerSetViewportV2: the host's echo of the rectangle it actually applied,
// in the same reference space the request was sent in (the negotiated stream
// resolution), plus the captured desktop size -- 0/0 when the host did not report it
// -- and the host frame number of the first frame carrying the crop (0 from an
// echo-v1 host; real frame numbers start at 1).
//
// This is the ONLY capability signal this extension has. LiSendViewportEvent()
// returns 0 against every encrypted-Gen-7 host, implemented or not, so a client
// that reads its return value as capability detection talks to stock Sunshine
// forever. See its comment in ControlStream.c.
//
// Passed to Java as jint rather than jshort: these are uint16 values and CheckJNI
// aborts on anything above 32767 in a jshort parameter. The neighbouring callbacks
// in callbacks.c cast to (short) because their Java side re-reads the sign; there
// is no reason to inherit that here. frameIndex is a uint32 passed through a jint;
// frame numbers stay below 2^31 for any session shorter than ~100 days at 240 FPS.
void MeowBridgeClSetViewportV2(uint16_t x, uint16_t y, uint16_t width, uint16_t height,
                               uint16_t desktopWidth, uint16_t desktopHeight,
                               uint32_t frameIndex) {
    JNIEnv* env;

    if (MeowBridgeClass == NULL || MeowBridgeOnViewportEchoMethod == NULL) {
        return;
    }

    env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, MeowBridgeClass, MeowBridgeOnViewportEchoMethod,
                                 (jint)x, (jint)y, (jint)width, (jint)height,
                                 (jint)desktopWidth, (jint)desktopHeight, (jint)frameIndex);

    // The Java side catches everything itself, so this should be unreachable.
    // Clear rather than detach: an exception left pending on this thread would
    // abort the next JNI call made from it, and that call belongs to a different
    // feature entirely.
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

// -----------------------------------------------------------------------------
// com.limelight.meow.stream.MeowStreamBridge: host cursor (0x3004) and receiver
// reports / applied bitrate (0x3005). Same rules as above: the jclass is handed over
// by nativeInit(), never looked up by string, and both callbacks are inert until it
// has run.
// -----------------------------------------------------------------------------

static jclass MeowStreamClass;
static jmethodID MeowStreamOnCursorPositionMethod;
static jmethodID MeowStreamOnBitrateAppliedMethod;

JNIEXPORT void JNICALL
Java_com_limelight_meow_stream_MeowStreamBridge_nativeInit(JNIEnv *env, jclass clazz) {
    MeowStreamClass = (*env)->NewGlobalRef(env, clazz);
    MeowStreamOnCursorPositionMethod =
        (*env)->GetStaticMethodID(env, clazz, "onCursorPosition", "(IIZI)V");
    MeowStreamOnBitrateAppliedMethod =
        (*env)->GetStaticMethodID(env, clazz, "onBitrateApplied", "(I)V");
}

JNIEXPORT jint JNICALL
Java_com_limelight_meow_stream_MeowStreamBridge_sendCursorSubscribe(JNIEnv *env, jclass clazz,
                                                                     jboolean subscribe) {
    (void)env;
    (void)clazz;
    return LiSendCursorSubscribe(subscribe ? true : false);
}

// Flat arguments rather than a Java object: this runs once a second, and flat jints
// need no field lookups, no allocation and no reflection-shaped JNI to keep in sync.
// Negative values cannot be represented on the wire and are clamped to 0; the uint16
// fields saturate rather than wrap.
static uint32_t clampReportU32(jint value) {
    return value < 0 ? 0 : (uint32_t)value;
}

JNIEXPORT jint JNICALL
Java_com_limelight_meow_stream_MeowStreamBridge_sendReceiverReport(JNIEnv *env, jclass clazz,
                                                                    jboolean autoBitrate,
                                                                    jint intervalMs,
                                                                    jint receivedKbps,
                                                                    jint lossPermille,
                                                                    jint rttMs,
                                                                    jint rttVarianceMs,
                                                                    jint decodeQueueFrames,
                                                                    jint avgDecodeMs,
                                                                    jint maxKbps) {
    MEOW_RECEIVER_REPORT report;

    (void)env;
    (void)clazz;

    memset(&report, 0, sizeof(report));
    report.autoBitrate = autoBitrate ? true : false;
    report.intervalMs = clampU16(intervalMs);
    report.receivedKbps = clampReportU32(receivedKbps);
    report.lossPermille = clampU16(lossPermille);
    report.rttMs = clampU16(rttMs);
    report.rttVarianceMs = clampU16(rttVarianceMs);
    report.decodeQueueFrames = clampU16(decodeQueueFrames);
    report.avgDecodeMs = clampU16(avgDecodeMs);
    report.maxKbps = clampReportU32(maxKbps);
    return LiSendReceiverReport(&report);
}

// Fills out[0..2] with packetsReceived, packetsExpected and bytesReceived. They are
// free-running uint32 counters; Java takes differences with unsigned arithmetic. The
// caller owns and reuses the array, so a report costs no allocation.
JNIEXPORT void JNICALL
Java_com_limelight_meow_stream_MeowStreamBridge_getVideoNetworkStats(JNIEnv *env, jclass clazz,
                                                                      jintArray out) {
    MEOW_VIDEO_NETWORK_STATS stats;
    jint values[3];

    (void)clazz;

    if (out == NULL || (*env)->GetArrayLength(env, out) < 3) {
        return;
    }

    memset(&stats, 0, sizeof(stats));
    LiGetMeowVideoNetworkStats(&stats);
    values[0] = (jint)stats.packetsReceived;
    values[1] = (jint)stats.packetsExpected;
    values[2] = (jint)stats.bytesReceived;
    (*env)->SetIntArrayRegion(env, out, 0, 3, values);
}

static void MeowStreamClearPendingException(JNIEnv* env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

// ConnListenerCursorPosition. Runs on the library's async callback thread, up to
// ~60 times a second while the host cursor moves, so it does nothing but forward.
void MeowBridgeClCursorPosition(uint16_t x, uint16_t y, bool visible, uint16_t seq) {
    JNIEnv* env;

    if (MeowStreamClass == NULL || MeowStreamOnCursorPositionMethod == NULL) {
        return;
    }

    env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, MeowStreamClass, MeowStreamOnCursorPositionMethod,
                                 (jint)x, (jint)y, visible ? JNI_TRUE : JNI_FALSE, (jint)seq);
    MeowStreamClearPendingException(env);
}

// ConnListenerBitrateApplied. kbps is never 0 (the library drops that).
void MeowBridgeClBitrateApplied(uint32_t kbps) {
    JNIEnv* env;

    if (MeowStreamClass == NULL || MeowStreamOnBitrateAppliedMethod == NULL) {
        return;
    }

    env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, MeowStreamClass, MeowStreamOnBitrateAppliedMethod,
                                 (jint)(kbps > 0x7FFFFFFFu ? 0x7FFFFFFFu : kbps));
    MeowStreamClearPendingException(env);
}
