package com.limelight.meow.video;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoCodecPolicyTest {

    @Test
    public void aHardwareLowLatencyDecoderIsOfferedInAuto() {
        assertTrue(AutoCodecPolicy.offerAv1(true, true, true, true, "c2.mtk.av1.decoder"));
        assertTrue("a dedicated low-latency codec counts",
                AutoCodecPolicy.offerAv1(true, true, true, false, "c2.mtk.av1.decoder.lowlatency"));
    }

    @Test
    public void anythingLessKeepsUpstreamsChoice() {
        assertFalse("not in automatic mode",
                AutoCodecPolicy.offerAv1(false, true, true, true, "c2.x.av1.decoder"));
        assertFalse("software", AutoCodecPolicy.offerAv1(true, false, true, true, "c2.android.av1.decoder"));
        assertFalse("not whitelisted", AutoCodecPolicy.offerAv1(true, true, false, true, "c2.x.av1.decoder"));
        assertFalse("no low-latency path",
                AutoCodecPolicy.offerAv1(true, true, true, false, "c2.qti.av1.decoder"));
        assertFalse(AutoCodecPolicy.offerAv1(true, true, true, false, null));
    }

    @Test
    public void withoutPreferencesNothingIsOffered() {
        assertFalse(AutoCodecPolicy.av1InAuto(null));
    }
}
