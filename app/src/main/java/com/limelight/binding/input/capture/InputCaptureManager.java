package com.limelight.binding.input.capture;

import android.app.Activity;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.evdev.EvdevListener;

public class InputCaptureManager {
    // At minSdk 26 the Android O native pointer capture API is always available, so the
    // old fallback cascade (NVIDIA Shield capture extension -> rooted evdev reader ->
    // Android N pointer hiding -> no capture at all) is unreachable and has been removed
    // along with the `root` product flavor.
    // AndroidNativePointerCaptureProvider.isCaptureProviderSupported() was literally
    // `SDK_INT >= O`, so it won every time.
    //
    // rootListener is retained in the signature: Game still implements EvdevListener and
    // passes itself, and dropping the parameter would be an unrelated edit to an upstream
    // file (CLAUDE.md §2).
    public static InputCaptureProvider getInputCaptureProvider(Activity activity, EvdevListener rootListener) {
        LimeLog.info("Using Android O+ native mouse capture");
        return new AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.streamContainer));
    }
}
