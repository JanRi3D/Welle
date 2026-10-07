package me.ri3d.welle;

import android.annotation.TargetApi;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;

/**
 * Media keys, Bluetooth remotes and the system media controls on Android 5+. Kept in its
 * own class so Android 4.x never loads code that refers to these API 21 types; there the
 * legacy MediaButtonReceiver does the job.
 */
@TargetApi(21)
final class MediaSession21 {
    private final MediaSession session;

    MediaSession21(final RadioService service) {
        session = new MediaSession(service, "Welle");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { service.play(); }
            @Override public void onPause() { service.pause(); }
            @Override public void onStop() { service.pause(); }
            @Override public void onSkipToNext() { service.next(); }
            @Override public void onSkipToPrevious() { service.prev(); }
        });
    }

    void update(String title, String text, boolean playing) {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                .build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        session.setActive(playing);
    }

    void release() {
        session.release();
    }
}
