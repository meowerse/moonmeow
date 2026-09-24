package com.limelight.nvstream.http;

import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Covers the one behavioural change in the OkHttp 5.5 back-port (upstream 98c12beb):
 * OkHttp 5 turns fast fallback (Happy Eyeballs) on by default, and it is intolerant of
 * thread interruption -- which ComputerManagerService does to its polling threads
 * routinely. Upstream switches it off on the base client; the short-connect and
 * no-read-timeout clients are built from it with newBuilder(), so they must inherit that.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
public class NvHTTPFastFallbackTest {

    private static OkHttpClient client(NvHTTP http, String field) throws Exception {
        Field f = NvHTTP.class.getDeclaredField(field);
        f.setAccessible(true);
        OkHttpClient c = (OkHttpClient) f.get(http);
        assertNotNull(field, c);
        return c;
    }

    @Test
    public void everyHttpClientHasFastFallbackDisabled() throws Exception {
        NvHTTP http = new NvHTTP(new ComputerDetails.AddressTuple("192.0.2.1", 47989),
                47984, "0123456789ABCDEF", null, mock(LimelightCryptoProvider.class));

        assertFalse(client(http, "httpClientLongConnectTimeout").fastFallback());
        assertFalse(client(http, "httpClientShortConnectTimeout").fastFallback());
        assertFalse(client(http, "httpClientLongConnectNoReadTimeout").fastFallback());
    }
}
