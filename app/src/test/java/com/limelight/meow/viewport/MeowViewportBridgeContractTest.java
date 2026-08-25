package com.limelight.meow.viewport;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

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
 * <p>Every expected name is derived here from the class object rather than being written
 * out, so moving the class breaks the test instead of silently breaking the app. The library
 * itself cannot be loaded on the JVM, which is why this reads the source rather than calling
 * the methods.
 */
public class MeowViewportBridgeContractTest {

    private static final String JNI_SOURCE = "app/src/main/jni/moonlight-core/meowjni.c";
    private static final String ANDROID_MK = "app/src/main/jni/moonlight-core/Android.mk";
    private static final String CALLBACKS = "app/src/main/jni/moonlight-core/callbacks.c";
    private static final String PROGUARD = "app/proguard-rules.pro";

    private static String mangled(String method) {
        return "Java_" + MeowViewportBridge.class.getName().replace('.', '_') + "_" + method;
    }

    @Test
    public void theSendSymbolMatchesThisClassesPackageAndName() throws IOException {
        String expected = mangled("sendViewport");
        String source = read(JNI_SOURCE);
        assertTrue("expected " + expected + " in " + JNI_SOURCE + "; found:\n"
                        + signatureLines(source), source.contains(expected));
    }

    @Test
    public void theInitSymbolMatchesThisClassesPackageAndName() throws IOException {
        // nativeInit() is what hands the native side its jclass. Without it the echo
        // callback has nothing to call and capability detection silently never fires --
        // which degrades to "no host supports this", not to a crash, so nothing else
        // would notice.
        String expected = mangled("nativeInit");
        String source = read(JNI_SOURCE);
        assertTrue("expected " + expected + " in " + JNI_SOURCE + "; found:\n"
                        + signatureLines(source), source.contains(expected));
    }

    @Test
    public void theNativeSignatureMatchesTheJavaOne() throws IOException {
        // The name is only half of the binding. Adding a parameter or widening one to long
        // keeps the name identical and still fails to resolve at runtime, so pin the shape
        // the Java declaration actually implies: four jints and the probe's force flag,
        // after the env/class pair.
        String source = read(JNI_SOURCE);
        int start = source.indexOf(mangled("sendViewport"));
        assertTrue("symbol not found at all", start > 0);
        String signature = source.substring(start, source.indexOf('{', start))
                .replaceAll("\\s+", " ");
        assertTrue("expected four jints and a jboolean, got: " + signature,
                signature.contains("JNIEnv *env, jclass clazz, jint x, jint y, jint width, jint height, "
                        + "jboolean force"));
    }

    @Test
    public void theEchoMethodSignatureMatchesWhatTheNativeSideLooksUp() throws IOException {
        // GetStaticMethodID resolves by name AND descriptor. A mismatch returns NULL and the
        // echo is silently never delivered.
        String source = read(JNI_SOURCE);
        assertTrue("meowjni.c must resolve onViewportEcho as (IIIIII)V; found:\n" + source,
                Pattern.compile("\"onViewportEcho\"\\s*,\\s*\"\\(IIIIII\\)V\"")
                        .matcher(source).find());
    }

    @Test
    public void theNativeSourceIsActuallyCompiledIn() throws IOException {
        // A symbol in a file the build never compiles is not a symbol -- and a commented-out
        // line mentioning the file would satisfy a naive substring check while producing
        // exactly that.
        String mk = read(ANDROID_MK);
        boolean listed = false;
        for (String line : mk.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.contains("meowjni.c")
                    && Pattern.compile("LOCAL_SRC_FILES\\s*\\+?=").matcher(trimmed).find()) {
                listed = true;
                break;
            }
        }
        assertTrue("meowjni.c must be assigned to LOCAL_SRC_FILES on an uncommented line in "
                + ANDROID_MK + "; found:\n" + mk, listed);
    }

    @Test
    public void theEchoCallbackIsInstalledInTheConnectionListener() throws IOException {
        // The C function in meowjni.c is reached through a struct member in callbacks.c.
        // Present but unreferenced, it links fine and is never called.
        String callbacks = read(CALLBACKS);
        assertTrue("callbacks.c must point .setViewport at MeowBridgeClSetViewport; found:\n"
                        + declarationLines(callbacks),
                Pattern.compile("\\.setViewport\\s*=\\s*MeowBridgeClSetViewport")
                        .matcher(callbacks).find());
        assertTrue("meowjni.c must define MeowBridgeClSetViewport",
                read(JNI_SOURCE).contains("void MeowBridgeClSetViewport("));
    }

    @Test
    public void theEchoEntryPointSurvivesR8() throws IOException {
        // onViewportEcho() has no Java caller -- it is invoked from meowjni.c -- so R8
        // strips it from the release dex unless it is kept. Verified: it really did, and
        // the result is a build that passes every other check while the host's echo is
        // silently dropped and capability detection never succeeds. Nothing else in this
        // suite can see that, because the unit tests run against unminified classes.
        String rules = read(PROGUARD);
        String type = MeowViewportBridge.class.getName();
        assertTrue("proguard-rules.pro must keep " + type + " members; found:\n"
                        + matchingLines(rules, "meow.viewport"),
                Pattern.compile("-keepclassmembers\\s+class\\s+" + Pattern.quote(type))
                        .matcher(rules).find());
        assertTrue("the keep rule must name onViewportEcho with its exact parameter list",
                Pattern.compile("static\\s+void\\s+onViewportEcho\\s*\\(\\s*int\\s*,\\s*int\\s*,"
                        + "\\s*int\\s*,\\s*int\\s*,\\s*int\\s*,\\s*int\\s*\\)")
                        .matcher(rules).find());
    }

    @Test
    public void theBridgeDoesNotLookUpItsOwnClassByString() throws IOException {
        // The other half of the CLAUDE.md hazard: a FindClass() string is looked up by name
        // and does not move when the package does, and `nm -D` cannot see the mistake at
        // all. nativeInit() is handed its jclass by the calling convention instead, so there
        // must be no such string here.
        String source = read(JNI_SOURCE);
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
        return matchingLines(source, "Java_");
    }

    private static String declarationLines(String source) {
        return matchingLines(source, "setViewport");
    }

    private static String matchingLines(String source, String needle) {
        StringBuilder found = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.contains(needle)) {
                found.append(line).append('\n');
            }
        }
        return found.length() == 0 ? "  (no matching lines at all)" : found.toString();
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
