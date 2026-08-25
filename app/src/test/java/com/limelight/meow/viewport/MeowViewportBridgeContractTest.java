package com.limelight.meow.viewport;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Guards the JNI hazard called out in {@code CLAUDE.md}.
 *
 * <p>Native methods bind by <em>static mangled name</em>, so
 * {@code MeowViewportBridge.sendViewport} only resolves if
 * {@code Java_com_limelight_meow_viewport_MeowViewportBridge_sendViewport} exists in the
 * built library. Rename or move the class and the app compiles cleanly, links cleanly, and
 * then dies at first call with {@code UnsatisfiedLinkError} — this repo has already shipped
 * one green build that died at launch on exactly this class of mistake.
 *
 * <p>The mangled name is derived here from the class object rather than being written out,
 * so moving the class breaks the test instead of silently breaking the app. The library
 * itself cannot be loaded on the JVM, which is why this reads the source rather than
 * calling the method.
 */
public class MeowViewportBridgeContractTest {

    private static final String JNI_SOURCE = "app/src/main/jni/moonlight-core/meowjni.c";
    private static final String ANDROID_MK = "app/src/main/jni/moonlight-core/Android.mk";

    @Test
    public void theNativeSymbolMatchesThisClassesPackageAndName() throws IOException {
        String expected = "Java_"
                + MeowViewportBridge.class.getName().replace('.', '_')
                + "_sendViewport";
        String source = read(JNI_SOURCE);
        assertTrue("expected " + expected + " in " + JNI_SOURCE + "; found:\n" + signatureLines(source),
                source.contains(expected));
    }

    @Test
    public void theNativeSignatureMatchesTheJavaOne() throws IOException {
        // The name is only half of the binding. Adding a parameter or widening one to long
        // keeps the name identical and still fails to resolve at runtime, so pin the shape
        // the Java declaration actually implies: four jints after the env/class pair.
        String source = read(JNI_SOURCE);
        int start = source.indexOf("Java_"
                + MeowViewportBridge.class.getName().replace('.', '_') + "_sendViewport");
        assertTrue("symbol not found at all", start > 0);
        String signature = source.substring(start, source.indexOf('{', start))
                .replaceAll("\\s+", " ");
        assertTrue("expected four jint parameters, got: " + signature,
                signature.contains("JNIEnv *env, jclass clazz, jint x, jint y, jint width, jint height"));
    }

    @Test
    public void theNativeSourceIsActuallyCompiledIn() throws IOException {
        // A symbol in a file the build never compiles is not a symbol.
        assertTrue("meowjni.c must be listed in Android.mk",
                read(ANDROID_MK).contains("meowjni.c"));
    }

    @Test
    public void theBridgeDoesNotCallBackIntoJava() throws IOException {
        // The other half of the CLAUDE.md hazard: a FindClass() string is looked up by name
        // and does not move when the package does. There is none here, and there must not be
        // one added without also updating the package string.
        String source = read(JNI_SOURCE);
        // The call form, not the word: the file's own comment explains why there is none.
        if (source.contains("->FindClass") || source.contains("env->FindClass")) {
            fail("meowjni.c now calls FindClass; the slash-form class string must be kept in "
                    + "sync with the package or the app will die at launch");
        }
        String slashForm = MeowViewportBridge.class.getName().replace('.', '/');
        if (source.contains(slashForm)) {
            fail("meowjni.c now hard-codes the slash-form class name; keep it in sync with "
                    + "the package or the app will build green and die at launch");
        }
    }

    private static String signatureLines(String source) {
        StringBuilder found = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.contains("Java_")) {
                found.append(line).append('\n');
            }
        }
        return found.toString();
    }

    private static String read(String relativePath) throws IOException {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParentFile()) {
            File candidate = new File(dir, relativePath);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            File here = new File(dir, relativePath.replaceFirst("^app/", ""));
            if (here.isFile()) {
                return new String(Files.readAllBytes(here.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not locate " + relativePath + " from "
                + new File("").getAbsolutePath());
    }
}
