package com.limelight.meow.viewport;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.media.MediaCodec;
import android.view.Surface;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@Config(sdk = {33})
@RunWith(RobolectricTestRunner.class)
public class DecoderSurfaceSwitchTest {

    @After
    public void tearDown() {
        DecoderSurfaceSwitch.reset();
    }

    @Test
    public void aRequestIsAppliedOnceOnTheRenderersNextLoop() {
        MediaCodec codec = mock(MediaCodec.class);
        Surface view = mock(Surface.class);
        DecoderSurfaceSwitch.apply(codec);
        verifyNoInteractions(codec);
        DecoderSurfaceSwitch.request(view);
        assertSame(view, DecoderSurfaceSwitch.pending());
        DecoderSurfaceSwitch.apply(codec);
        DecoderSurfaceSwitch.apply(codec);
        verify(codec, times(1)).setOutputSurface(view);
        assertNull(DecoderSurfaceSwitch.pending());
    }

    @Test
    public void aNewStreamDropsARequestFromTheOldOne() {
        MediaCodec codec = mock(MediaCodec.class);
        DecoderSurfaceSwitch.request(mock(Surface.class));
        DecoderSurfaceSwitch.reset();
        DecoderSurfaceSwitch.apply(codec);
        verifyNoInteractions(codec);
    }

    @Test
    public void aCodecThatRefusesDoesNotThrowIntoTheRenderLoop() {
        MediaCodec codec = mock(MediaCodec.class);
        Surface view = mock(Surface.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("released"))
                .when(codec).setOutputSurface(view);
        DecoderSurfaceSwitch.request(view);
        DecoderSurfaceSwitch.apply(codec);
        DecoderSurfaceSwitch.apply(null);
    }
}
