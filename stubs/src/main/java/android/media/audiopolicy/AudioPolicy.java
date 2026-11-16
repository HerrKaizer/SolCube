package android.media.audiopolicy;

import android.media.AudioFocusInfo;

/** Stub of the framework's @SystemApi class (only the listener Suite subclasses). */
public class AudioPolicy {
    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusLoss(AudioFocusInfo afi, boolean wasNotified) {}
        public void onAudioFocusRequest(AudioFocusInfo afi, int requestResult) {}
        public void onAudioFocusAbandon(AudioFocusInfo afi) {}
    }
}
