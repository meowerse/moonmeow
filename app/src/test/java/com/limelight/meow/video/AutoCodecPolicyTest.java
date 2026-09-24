package com.limelight.meow.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoCodecPolicyTest {

    @Test
    public void aHardwareLowLatencyDecoderIsOfferedInAuto() {
        assertTrue(AutoCodecPolicy.offerAv1(true, true, true, true, "c2.mtk.av1.decoder",
                true, false, false));
        assertTrue("a dedicated low-latency codec counts", AutoCodecPolicy.offerAv1(true, true,
                true, false, "c2.mtk.av1.decoder.lowlatency", true, false, false));
        assertTrue("HDR with Main10", AutoCodecPolicy.offerAv1(true, true, true, true,
                "c2.mtk.av1.decoder", true, true, true));
    }

    @Test
    public void anythingLessKeepsUpstreamsChoice() {
        assertFalse("not in automatic mode", AutoCodecPolicy.offerAv1(false, true, true, true,
                "c2.x.av1.decoder", true, false, false));
        assertFalse("software", AutoCodecPolicy.offerAv1(true, false, true, true,
                "c2.android.av1.decoder", true, false, false));
        assertFalse("not whitelisted", AutoCodecPolicy.offerAv1(true, true, false, true,
                "c2.x.av1.decoder", true, false, false));
        assertFalse("no low-latency path", AutoCodecPolicy.offerAv1(true, true, true, false,
                "c2.qti.av1.decoder", true, false, false));
        assertFalse(AutoCodecPolicy.offerAv1(true, true, true, false, null, true, false, false));
        assertFalse("no reference-frame invalidation: every loss would cost an IDR",
                AutoCodecPolicy.offerAv1(true, true, true, true, "c2.x.av1.decoder",
                        false, false, false));
        assertFalse("HDR wanted but no Main10: AV1 Main8 would win and drop HDR",
                AutoCodecPolicy.offerAv1(true, true, true, true, "c2.x.av1.decoder",
                        true, true, false));
    }

    @Test
    public void withoutPreferencesNothingIsOffered() {
        assertFalse(AutoCodecPolicy.av1InAuto(null));
    }
}
