package com.limelight.meow.video;

import android.media.MediaCodecInfo;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Locale;

/**
 * Whether "automatic" codec selection may offer AV1.
 *
 * <p>Upstream offers AV1 only when the user forces it, because early AV1 decoders were slow or
 * software. The negotiation already prefers AV1 whenever both sides offer it
 * ({@code RtspConnection.c}), and AV1 needs noticeably fewer bits than HEVC for the same
 * desktop detail -- which is what a bandwidth-limited zoomed stream wants. So in automatic
 * mode AV1 is offered, but only on a decoder that is <b>hardware</b>, passes the existing
 * AV1 whitelist, has a <b>low-latency</b> path ({@code FEATURE_LowLatency}, or a dedicated
 * low-latency codec -- MediaTek names them {@code *.lowlatency}), and supports
 * <b>reference-frame invalidation</b>, without which every lost packet costs an IDR frame on
 * exactly the lossy links this is for. When HDR is requested it must also decode AV1 Main10,
 * because the negotiation prefers AV1 Main8 over HEVC Main10 and would silently drop HDR.
 * Without all of that, automatic keeps choosing HEVC or H.264 exactly as before.
 */
public final class AutoCodecPolicy {

    private AutoCodecPolicy() {
    }

    /** The decision, over plain facts about the decoder. */
    public static boolean offerAv1(boolean automatic, boolean hardware, boolean whitelisted,
                                   boolean lowLatencyFeature, String decoderName,
                                   boolean refFrameInvalidation, boolean hdrWanted,
                                   boolean main10) {
        if (!automatic || !hardware || !whitelisted || !refFrameInvalidation
                || (hdrWanted && !main10)) {
            return false;
        }
        String name = decoderName == null ? "" : decoderName.toLowerCase(Locale.ROOT);
        return lowLatencyFeature || name.contains("lowlatency") || name.contains("low_latency")
                || name.contains("low-latency");
    }

    /** Hook in {@code MediaCodecDecoderRenderer.findAv1Decoder}: may AUTO use AV1 here? */
    public static boolean av1InAuto(PreferenceConfiguration prefs) {
        if (prefs == null || prefs.videoFormat != PreferenceConfiguration.FormatOption.AUTO) {
            return false;
        }
        try {
            MediaCodecInfo info = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1);
            if (info == null) {
                return false;
            }
            boolean lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && info.getCapabilitiesForType("video/av01")
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
            boolean main10 = false;
            for (MediaCodecInfo.CodecProfileLevel level
                    : info.getCapabilitiesForType("video/av01").profileLevels) {
                if (level.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                    main10 = true;
                    break;
                }
            }
            boolean offer = offerAv1(true,
                    info.isHardwareAccelerated() && !info.isSoftwareOnly(),
                    MediaCodecHelper.isDecoderWhitelistedForAv1(info), lowLatency, info.getName(),
                    MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(info),
                    prefs.enableHdr, main10);
            LimeLog.info("Automatic codec: AV1 " + (offer ? "offered" : "not offered")
                    + " (" + info.getName() + ", low latency " + lowLatency + ")");
            return offer;
        } catch (RuntimeException e) {
            // A codec probe must never take the stream down; fall back to upstream's choice.
            return false;
        }
    }
}
