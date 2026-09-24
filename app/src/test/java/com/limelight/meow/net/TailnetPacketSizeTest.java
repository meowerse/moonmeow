package com.limelight.meow.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.meow.SourceFiles;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.shadows.ShadowMoonBridge;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * N3: over Tailscale every video datagram must fit the tailnet's 1280-byte MTU, or WireGuard
 * fragments it and a single lost fragment loses the packet. The client guarantees that through
 * an inherited path: a VPN network is classified {@code STREAM_CFG_REMOTE}, and remote streams
 * negotiate 1024-byte video packets instead of 1392. Nothing in the new bitrate or viewport
 * work may disturb that, so it is pinned here — the classification by running it, the size
 * decision and the header budget by reading the sources they live in.
 */
@Config(sdk = {33}, shadows = {ShadowMoonBridge.class})
@RunWith(RobolectricTestRunner.class)
public class TailnetPacketSizeTest {

    private static final int TAILNET_MTU = 1280;
    private static final int IPV6_HEADER = 40;
    private static final int UDP_HEADER = 8;
    private static final int REMOTE_PACKET_SIZE = 1024;
    private static final int LAN_PACKET_SIZE = 1392;

    @Test
    public void aVpnNetworkIsClassifiedRemote() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.getActiveNetwork(), caps);

        StreamConfiguration config = new StreamConfiguration.Builder()
                .setMaxPacketSize(LAN_PACKET_SIZE)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                .build();
        NvConnection conn = new NvConnection(context,
                new ComputerDetails.AddressTuple("100.118.62.58", 47989), 0, "0123456789ABCDEF",
                config, null, null);
        Method detect = NvConnection.class.getDeclaredMethod("detectServerConnectionType");
        detect.setAccessible(true);
        assertEquals(StreamConfiguration.STREAM_CFG_REMOTE, detect.invoke(conn));
    }

    @Test
    public void aRemoteStreamNegotiatesTheSmallPacketSize() throws IOException {
        String source = SourceFiles.stripComments(SourceFiles.read(
                "app/src/main/java/com/limelight/nvstream/NvConnection.java"));
        Matcher m = Pattern.compile("negotiatedRemoteStreaming\\s*==\\s*StreamConfiguration\\."
                + "STREAM_CFG_REMOTE\\s*\\?\\s*(\\d+)\\s*:").matcher(source);
        assertTrue("the REMOTE -> small packet decision is gone from NvConnection", m.find());
        assertEquals(REMOTE_PACKET_SIZE, Integer.parseInt(m.group(1)));
        assertTrue("the negotiation must still classify the connection when asked to",
                source.contains("context.negotiatedRemoteStreaming = detectServerConnectionType();"));
        String game = SourceFiles.stripComments(SourceFiles.read(
                "app/src/main/java/com/limelight/Game.java"));
        assertTrue("Game must still ask for automatic LAN/VPN detection",
                game.contains(".setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)"));
    }

    @Test
    public void theLargestRemoteDatagramFitsTheTailnetMtu() throws IOException {
        String video = SourceFiles.read(
                "app/src/main/jni/moonlight-core/moonlight-common-c/src/Video.h");
        Matcher rtp = Pattern.compile("#define\\s+MAX_RTP_HEADER_SIZE\\s+(\\d+)").matcher(video);
        assertTrue(rtp.find());
        int rtpHeader = Integer.parseInt(rtp.group(1));
        // ENC_VIDEO_HEADER: uint8_t iv[12]; uint32_t frameNumber; uint8_t tag[16];
        assertTrue(video.contains("uint8_t iv[12];") && video.contains("uint32_t frameNumber;")
                && video.contains("uint8_t tag[16];"));
        int encryptionHeader = 12 + 4 + 16;

        int remote = REMOTE_PACKET_SIZE + rtpHeader + encryptionHeader + UDP_HEADER + IPV6_HEADER;
        assertTrue("remote datagram " + remote + " must fit " + TAILNET_MTU, remote <= TAILNET_MTU);

        // And the reason the classification matters: the LAN size would not fit.
        int lan = LAN_PACKET_SIZE + rtpHeader + encryptionHeader + UDP_HEADER + IPV6_HEADER;
        assertTrue(lan > TAILNET_MTU);
    }

    @Test
    public void everyNewControlMessageIsTiny() throws IOException {
        // N1/N2: the new messages ride the existing control stream, far under the MTU.
        String protocol = SourceFiles.read(
                "app/src/main/jni/moonlight-core/moonlight-common-c/src/MeowProtocol.h");
        for (String name : new String[] {"MEOW_VIEWPORT_REQUEST_LENGTH",
                "MEOW_CURSOR_SUBSCRIBE_LENGTH", "MEOW_CURSOR_POSITION_LENGTH",
                "MEOW_RECEIVER_REPORT_LENGTH", "MEOW_BITRATE_APPLIED_LENGTH"}) {
            Matcher m = Pattern.compile("#define\\s+" + name + "\\s+(\\d+)").matcher(protocol);
            assertTrue(name, m.find());
            assertTrue(name + " must stay under 64 bytes", Integer.parseInt(m.group(1)) < 64);
        }
    }
}
