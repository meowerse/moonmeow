package com.limelight.shadows;

import com.limelight.meow.viewport.MeowViewportBridge;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * A viewport bridge whose sends succeed, for tests that run a whole {@code Game} against a
 * fake meow host. Without it the JVM has no native library, the capability probe reports
 * "library unavailable", the reporter latches the host off and every echo the test delivers
 * is refused -- which is not what a device does.
 */
@Implements(value = MeowViewportBridge.class, isInAndroidSdk = false)
public class ShadowMeowViewportBridge {

    public static int sends;

    @Implementation
    protected int send(int x, int y, int width, int height, boolean force) {
        sends++;
        return 0;
    }
}
