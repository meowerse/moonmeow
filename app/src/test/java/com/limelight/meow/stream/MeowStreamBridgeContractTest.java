package com.limelight.meow.stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.limelight.meow.SourceFiles;

import org.junit.Test;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * The CLAUDE.md JNI hazard, pinned for {@link MeowStreamBridge}: every native method binds by
 * static mangled name, and both callbacks are resolved in C by name and descriptor. Each
 * expected name is derived from the class object, so moving the class fails this test
 * instead of producing a green build that dies at first call. The library cannot be loaded
 * on the JVM, which is why this reads the sources.
 */
public class MeowStreamBridgeContractTest {

    private static final String JNI_SOURCE = "app/src/main/jni/moonlight-core/meowjni.c";
    private static final String JNI_HEADER = "app/src/main/jni/moonlight-core/meowjni.h";
    private static final String CALLBACKS = "app/src/main/jni/moonlight-core/callbacks.c";
    private static final String PROGUARD = "app/proguard-rules.pro";

    private static String mangled(String method) {
        return "Java_" + MeowStreamBridge.class.getName().replace('.', '_') + "_" + method;
    }

    private static String signatureOf(String source, String method) {
        int start = source.indexOf(mangled(method));
        assertTrue(mangled(method) + " not found in " + JNI_SOURCE, start > 0);
        return source.substring(start, source.indexOf('{', start)).replaceAll("\\s+", " ");
    }

    @Test
    public void everyNativeMethodHasItsMangledSymbol() throws IOException {
        String source = SourceFiles.read(JNI_SOURCE);
        for (String method : new String[] {"nativeInit", "sendCursorSubscribe",
                "sendReceiverReport", "getVideoNetworkStats"}) {
            assertTrue("expected " + mangled(method) + " in " + JNI_SOURCE,
                    source.contains(mangled(method)));
        }
    }

    @Test
    public void theNativeParameterListsMatchTheJavaDeclarations() throws IOException {
        // The name is only half of the binding: a widened or added parameter keeps the name
        // and still fails to resolve at runtime.
        String source = SourceFiles.read(JNI_SOURCE);
        assertTrue(signatureOf(source, "sendCursorSubscribe")
                .contains("JNIEnv *env, jclass clazz, jboolean subscribe)"));
        assertTrue(signatureOf(source, "sendReceiverReport").contains(
                "JNIEnv *env, jclass clazz, jboolean autoBitrate, jint intervalMs, "
                        + "jint receivedKbps, jint lossPermille, jint rttMs, jint rttVarianceMs, "
                        + "jint decodeQueueFrames, jint avgDecodeMs, jint maxKbps)"));
        assertTrue(signatureOf(source, "getVideoNetworkStats")
                .contains("JNIEnv *env, jclass clazz, jintArray out)"));
    }

    @Test
    public void theCallbackDescriptorsMatchTheJavaMethods() throws IOException {
        // GetStaticMethodID resolves by name AND descriptor; a mismatch returns NULL and the
        // callback is silently never delivered.
        String source = SourceFiles.read(JNI_SOURCE);
        assertTrue(Pattern.compile("\"onCursorPosition\"\\s*,\\s*\"\\(IIZI\\)V\"")
                .matcher(source).find());
        assertTrue(Pattern.compile("\"onBitrateApplied\"\\s*,\\s*\"\\(I\\)V\"")
                .matcher(source).find());
    }

    @Test
    public void bothCallbacksAreInstalledThroughTheSharedHeader() throws IOException {
        String callbacks = SourceFiles.stripComments(SourceFiles.read(CALLBACKS));
        assertTrue(Pattern.compile("\\.cursorPosition\\s*=\\s*MeowBridgeClCursorPosition")
                .matcher(callbacks).find());
        assertTrue(Pattern.compile("\\.bitrateApplied\\s*=\\s*MeowBridgeClBitrateApplied")
                .matcher(callbacks).find());
        String header = SourceFiles.read(JNI_HEADER);
        assertTrue(header.contains("void MeowBridgeClCursorPosition("));
        assertTrue(header.contains("void MeowBridgeClBitrateApplied("));
        assertFalse("callbacks.c must not re-declare them",
                callbacks.contains("extern void MeowBridgeCl"));
    }

    @Test
    public void theCallbacksSurviveR8() throws IOException {
        // Nothing in Java calls them, so R8 strips them unless kept -- the viewport echo
        // really was stripped once. The unit tests run unminified and cannot see it.
        String rules = SourceFiles.read(PROGUARD);
        assertTrue(Pattern.compile("-keepclassmembers\\s+class\\s+"
                + Pattern.quote(MeowStreamBridge.class.getName())).matcher(rules).find());
        assertTrue(Pattern.compile("static\\s+void\\s+onCursorPosition\\s*\\(\\s*int\\s*,\\s*int\\s*,"
                + "\\s*boolean\\s*,\\s*int\\s*\\)").matcher(rules).find());
        assertTrue(Pattern.compile("static\\s+void\\s+onBitrateApplied\\s*\\(\\s*int\\s*\\)")
                .matcher(rules).find());
    }

    @Test
    public void theBridgeIsNeverLookedUpByString() throws IOException {
        String source = SourceFiles.read(JNI_SOURCE);
        assertFalse(source.contains("->FindClass"));
        assertFalse(source.contains(MeowStreamBridge.class.getName().replace('.', '/')));
    }
}
