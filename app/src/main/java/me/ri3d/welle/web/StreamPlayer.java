package me.ri3d.welle.web;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import me.ri3d.welle.audio.Asc;
import me.ri3d.welle.audio.AudioEngine;
import me.ri3d.welle.audio.CodecDecoder;
import me.ri3d.welle.core.Io;

/**
 * Plays one internet radio stream: resolves playlists, reads the stream on its own thread,
 * splits it into frames, decodes them with MediaCodec and writes PCM to the AudioEngine.
 *
 * Supported: MP3 and AAC/HE-AAC (ADTS) over HTTP/HTTPS, Icecast/Shoutcast incl. ICY titles,
 * .m3u/.m3u8/.pls indirection, and HLS with MPEG-TS or packed AAC/MP3 segments.
 * Declared unsupported (reported, not retried): Ogg/Opus/FLAC, fMP4 HLS, encrypted HLS.
 */
public final class StreamPlayer {
    public static final int STOPPED = 0;
    public static final int CONNECTING = 1;
    public static final int BUFFERING = 2;
    public static final int PLAYING = 3;
    public static final int RECONNECTING = 4;
    public static final int FAILED = 5;

    /** Called on the stream thread. */
    public interface Listener {
        void onStreamState(int state, String detail);
        void onStreamTitle(String title);
        void onStreamFormat(String codec, int kbps);
    }

    private static final int[] BACKOFF_S = {1, 2, 4, 8, 15, 30};
    /** Consecutive failed attempts before giving up; a connection that held for 30 s resets the count. */
    static final int MAX_FAILURES = 8;
    private static final int MAX_PLAYLIST_DEPTH = 3;
    private static final int MAX_SEGMENT_BYTES = 32 * 1024 * 1024;

    private final AudioEngine engine;
    private final Listener listener;
    private Session current;

    public StreamPlayer(AudioEngine engine, Listener listener) {
        this.engine = engine;
        this.listener = listener;
    }

    public synchronized void play(String url) {
        stop();
        current = new Session(url, engine.begin());
        current.start();
    }

    public synchronized void stop() {
        if (current != null) {
            current.cancel();
            current = null;
        }
    }

    /** A stream this player cannot handle; retrying would not help. */
    private static final class Unsupported extends IOException {
        Unsupported(String what) {
            super(what);
        }
    }

    private final class Session extends Thread implements FrameParser.Sink {
        private final String url;
        private final int audioSession;
        private volatile boolean cancelled;
        private volatile HttpURLConnection conn;
        private FrameParser parser;
        private CodecDecoder decoder;
        private String fatal;
        private boolean playing;
        private int unanswered; // frames fed to the current decoder with no PCM back yet
        private int icyKbps;

        Session(String url, int audioSession) {
            super("web-stream");
            this.url = url;
            this.audioSession = audioSession;
        }

        void cancel() {
            cancelled = true;
            interrupt();
            final HttpURLConnection c = conn;
            if (c != null) {
                // Closing a TLS socket writes to the network, which Android forbids on the main thread.
                new Thread("web-close") {
                    @Override
                    public void run() {
                        try {
                            c.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }.start();
            }
        }

        private void state(int s, String detail) {
            // Error texts can quote the address; never show or log credentials from it.
            if (!cancelled) listener.onStreamState(s, detail == null ? null : Net.redact(detail));
        }

        @Override
        public void run() {
            int failures = 0;
            try {
                while (!cancelled) {
                    long started = System.currentTimeMillis();
                    try {
                        state(failures == 0 ? CONNECTING : RECONNECTING, null);
                        playOnce(url, 0);
                        throw new IOException("stream ended");
                    } catch (Unsupported e) {
                        state(FAILED, e.getMessage());
                        return;
                    } catch (IOException e) {
                        if (cancelled) return;
                        if (System.currentTimeMillis() - started > 30000) failures = 0;
                        if (++failures > MAX_FAILURES) {
                            state(FAILED, e.getMessage());
                            return;
                        }
                        state(RECONNECTING, e.getMessage());
                        resetDecoder();
                        try {
                            Thread.sleep(BACKOFF_S[Math.min(failures - 1, BACKOFF_S.length - 1)] * 1000L);
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                }
            } catch (RuntimeException e) {
                state(FAILED, String.valueOf(e));
            } finally {
                resetDecoder();
            }
        }

        private void resetDecoder() {
            if (decoder != null) {
                decoder.release();
                decoder = null;
            }
            playing = false;
            unanswered = 0;
        }

        private void playOnce(String url, int depth) throws IOException {
            HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("Icy-MetaData", "1");
            HttpURLConnection c = Net.open(url, headers, 15000);
            conn = c;
            try {
                String finalUrl = c.getURL().toString();
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
                String path = c.getURL().getPath().toLowerCase(Locale.US);
                boolean listType = type.contains("mpegurl") || type.contains("scpls");
                boolean listPath = path.endsWith(".m3u") || path.endsWith(".m3u8") || path.endsWith(".pls");
                InputStream in = c.getInputStream();
                if (listType || (listPath && !type.startsWith("audio/"))) {
                    String text = new String(Io.readAll(in, Playlist.MAX_BYTES), "UTF-8");
                    c.disconnect();
                    if (Playlist.isHls(text)) {
                        playHls(finalUrl, text);
                        return;
                    }
                    List<Playlist.Entry> entries = Playlist.parse(text, finalUrl);
                    if (entries.isEmpty()) throw new Unsupported("playlist has no stream");
                    if (depth >= MAX_PLAYLIST_DEPTH) throw new Unsupported("playlists nested too deep");
                    playOnce(entries.get(0).url, depth + 1);
                    return;
                }
                if (type.contains("ogg") || type.contains("opus") || type.contains("flac")
                        || type.startsWith("video/") || type.startsWith("text/html")) {
                    throw new Unsupported("unsupported format " + type);
                }
                icyKbps = c.getHeaderFieldInt("icy-br", 0);
                state(BUFFERING, null);
                pump(new BufferedInputStream(in, 16384), c.getHeaderFieldInt("icy-metaint", 0));
            } finally {
                conn = null;
                c.disconnect();
            }
        }

        /** Reads the stream; with ICY metadata, every metaInt audio bytes are followed by a title block. */
        private void pump(InputStream in, int metaInt) throws IOException {
            parser = new FrameParser(this);
            byte[] b = new byte[4096];
            int untilMeta = metaInt;
            while (!cancelled) {
                int want = metaInt > 0 ? Math.min(b.length, untilMeta) : b.length;
                int n = in.read(b, 0, want);
                if (n < 0) return;
                parser.push(b, 0, n);
                check();
                if (metaInt > 0) {
                    untilMeta -= n;
                    if (untilMeta == 0) {
                        int len = in.read() * 16;
                        if (len < 0) return;
                        if (len > 0) {
                            byte[] meta = new byte[len];
                            int got = 0;
                            while (got < len) {
                                int r = in.read(meta, got, len - got);
                                if (r < 0) return;
                                got += r;
                            }
                            String title = icyTitle(meta);
                            if (title != null && !cancelled) listener.onStreamTitle(title);
                        }
                        untilMeta = metaInt;
                    }
                }
            }
        }

        private void check() throws IOException {
            if (fatal != null) throw new Unsupported(fatal);
            if (parser.frames() == 0 && parser.skippedBytes() > 128 * 1024) {
                throw new Unsupported("no MP3 or AAC audio found");
            }
            if (decoder != null && parser.frames() > 400 && decoder.pcmFrames() == 0) {
                throw new Unsupported("decoder produced no audio");
            }
        }

        private void playHls(String manifestUrl, String text) throws IOException {
            String mediaUrl = manifestUrl;
            if (text.contains("#EXT-X-STREAM-INF")) {
                // Master playlist: radio needs no video, take the cheapest variant.
                String best = null;
                long bestBandwidth = Long.MAX_VALUE;
                String[] lines = text.split("\r\n|\r|\n");
                for (int i = 0; i < lines.length; i++) {
                    if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                    long bw = attribute(lines[i], "BANDWIDTH");
                    for (int j = i + 1; j < lines.length; j++) {
                        String l = lines[j].trim();
                        if (l.isEmpty() || l.startsWith("#")) continue;
                        if (bw < bestBandwidth) {
                            bestBandwidth = bw;
                            best = l;
                        }
                        break;
                    }
                }
                mediaUrl = best == null ? null : Playlist.resolve(manifestUrl, best);
                if (mediaUrl == null) throw new Unsupported("HLS playlist without variants");
                text = new String(Net.get(mediaUrl, Playlist.MAX_BYTES), "UTF-8");
            }
            state(BUFFERING, null);
            parser = new FrameParser(this);
            long lastSeq = -1;
            long idleSince = System.currentTimeMillis();
            while (!cancelled) {
                if (text.contains("#EXT-X-KEY") && !text.contains("METHOD=NONE")) throw new Unsupported("encrypted HLS is not supported");
                if (text.contains("#EXT-X-MAP")) throw new Unsupported("fMP4 HLS is not supported");
                long seq = 0;
                int target = 6;
                ArrayList<String> segments = new ArrayList<String>();
                for (String raw : text.split("\r\n|\r|\n")) {
                    String l = raw.trim();
                    if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) seq = number(l.substring(22));
                    else if (l.startsWith("#EXT-X-TARGETDURATION:")) target = (int) Math.max(1, number(l.substring(22)));
                    else if (!l.isEmpty() && !l.startsWith("#")) segments.add(l);
                }
                boolean ended = text.contains("#EXT-X-ENDLIST");
                if (lastSeq < 0 && !ended) lastSeq = seq + Math.max(0, segments.size() - 3) - 1; // join near the live edge
                boolean progressed = false;
                for (int i = 0; i < segments.size() && !cancelled; i++) {
                    if (seq + i <= lastSeq) continue;
                    segment(Playlist.resolve(mediaUrl, segments.get(i)));
                    lastSeq = seq + i;
                    progressed = true;
                }
                if (ended) throw new Unsupported("stream ended");
                long now = System.currentTimeMillis();
                if (progressed) idleSince = now;
                else if (now - idleSince > target * 3000L + 10000) throw new IOException("HLS playlist stalled");
                try {
                    Thread.sleep(Math.max(1000, target * 500L));
                } catch (InterruptedException e) {
                    return;
                }
                text = new String(Net.get(mediaUrl, Playlist.MAX_BYTES), "UTF-8");
            }
        }

        private void segment(String url) throws IOException {
            if (url == null) return;
            HttpURLConnection c = Net.open(url, null, 15000);
            conn = c;
            try {
                InputStream in = c.getInputStream();
                byte[] b = new byte[188 * 64];
                TsDemux ts = null;
                boolean first = true;
                int total = 0;
                while (!cancelled) {
                    int n = in.read(b);
                    if (n < 0) break;
                    if (n == 0) continue;
                    total += n;
                    if (total > MAX_SEGMENT_BYTES) throw new IOException("HLS segment too large");
                    if (first) {
                        first = false;
                        if (b[0] == 0x47) {
                            ts = new TsDemux(new TsDemux.Sink() {
                                @Override
                                public void onAudio(byte[] buf, int off, int len) {
                                    parser.push(buf, off, len);
                                }
                            });
                        }
                    }
                    if (ts != null) ts.push(b, 0, n);
                    else parser.push(b, 0, n);
                    check();
                }
            } finally {
                conn = null;
                c.disconnect();
            }
        }

        // ---- FrameParser.Sink ---------------------------------------------------------------

        @Override
        public void onFormat(int codec, int sampleRate, int channels, int aacObjectType, int rateIndex, int kbps) {
            resetDecoder();
            try {
                if (codec == FrameParser.AAC) {
                    decoder = new CodecDecoder(CodecDecoder.AAC, sampleRate, channels, Asc.adts(aacObjectType, rateIndex, channels), engine, audioSession);
                } else {
                    decoder = new CodecDecoder(codec == FrameParser.MP3 ? CodecDecoder.MP3 : CodecDecoder.MP2, sampleRate, channels, null, engine, audioSession);
                }
            } catch (IOException e) {
                fatal = "no working " + (codec == FrameParser.AAC ? "AAC" : codec == FrameParser.MP3 ? "MP3" : "MP2")
                        + " decoder on this device (" + e.getMessage() + ")";
                return;
            }
            if (!cancelled) {
                listener.onStreamFormat(codec == FrameParser.AAC ? "AAC" : codec == FrameParser.MP3 ? "MP3" : "MP2", kbps > 0 ? kbps : icyKbps);
            }
        }

        @Override
        public void onFrame(byte[] buf, int off, int len) {
            if (decoder == null || cancelled) return;
            decoder.feed(buf, off, len);
            if (playing) return;
            if (decoder.pcmFrames() > 0) {
                playing = true;
                state(PLAYING, null);
            } else if (++unanswered > 400) {
                // About ten seconds of audio went in and nothing came out: a vendor decoder that
                // plays through its own hardware instead of returning PCM.
                fatal = "the decoder takes the stream but returns no audio";
            }
        }
    }

    private static long attribute(String line, String name) {
        int i = line.indexOf(name + "=");
        return i < 0 ? Long.MAX_VALUE - 1 : number(line.substring(i + name.length() + 1));
    }

    /** Leading decimal digits of s, 0 if there are none. */
    static long number(String s) {
        long v = 0;
        for (int i = 0; i < s.length() && Character.isDigit(s.charAt(i)) && i < 18; i++) v = v * 10 + (s.charAt(i) - '0');
        return v;
    }

    /** Extracts StreamTitle from an ICY metadata block; null if absent or empty. */
    public static String icyTitle(byte[] meta) {
        String s;
        try {
            s = new String(meta, "UTF-8");
            if (s.indexOf(0xFFFD) >= 0) s = new String(meta, "ISO-8859-1");
        } catch (Exception e) {
            return null;
        }
        int a = s.indexOf("StreamTitle='");
        if (a < 0) return null;
        a += 13;
        int b = s.indexOf("';", a);
        if (b < 0) b = s.lastIndexOf('\'');
        if (b <= a) return null;
        String t = s.substring(a, b).trim();
        return t.isEmpty() ? null : t;
    }
}
