package com.limelight.meow.audio;

/**
 * "Play audio on host PC" ({@code checkbox_host_audio}), Moonlight's own switch, on by default
 * in moonmeow.
 *
 * <p>The owner listens on the PC's headphones and on the phone at the same time (2026-09-26).
 * With the switch off, the launch asks the host for {@code localAudioPlayMode=0}, and a
 * Sunshine host then points its default sink at its own virtual sink for the session: the
 * PC goes silent and only the stream carries the sound. On, it sends
 * {@code localAudioPlayMode=1}, the host leaves the default sink alone and captures its
 * monitor, and both play. Fresh installs get it from the XML default; installs that predate
 * it get it once from {@code MeowDefaults} (schema 3); the switch stays in Settings.
 */
public final class HostAudioPreference {

    public static final String KEY = "checkbox_host_audio";
    // MEOW-TOUCH(defaults): must match the android:defaultValue of checkbox_host_audio.
    public static final boolean DEFAULT = true;

    private HostAudioPreference() {
    }
}
