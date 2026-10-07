package me.ri3d.welle.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import me.ri3d.welle.core.Station;

public class WebTest {

    // ---- playlists -----------------------------------------------------------------------------

    @Test
    public void extendedM3uWithNamesAndRelativeEntries() {
        String text = (char) 0xFEFF + "#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-name=\"x\" group-title=\"News, Talk\",Deutschlandfunk\r\n"
                + "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3\r\n"
                + "\r\n"
                + "#EXTINF:-1,Relative One\r\n"
                + "streams/one.mp3\r\n"
                + "# a comment\r\n"
                + "/root/two.aac\r\n"
                + "ftp://example.org/not-http\r\n"
                + "mms://old.example.org/x\r\n";
        List<Playlist.Entry> e = Playlist.parse(text, "http://example.org/lists/all.m3u");
        assertEquals(3, e.size());
        assertEquals("Deutschlandfunk", e.get(0).name);
        assertEquals("https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3", e.get(0).url);
        assertEquals("Relative One", e.get(1).name);
        assertEquals("http://example.org/lists/streams/one.mp3", e.get(1).url);
        assertEquals("http://example.org/root/two.aac", e.get(2).url);
        assertEquals("unnamed entries get a name from the URL", "example.org two", e.get(2).name);
    }

    @Test
    public void localFileSkipsRelativeEntries() {
        List<Playlist.Entry> e = Playlist.parse("#EXTM3U\nstream.mp3\nhttp://example.org/a.mp3\n", null);
        assertEquals(1, e.size());
        assertEquals("http://example.org/a.mp3", e.get(0).url);
    }

    @Test
    public void plsPlaylist() {
        String text = "[playlist]\nNumberOfEntries=2\nFile1=http://a.example/1\nTitle1=First\nLength1=-1\n"
                + "Title2=Second\nFile2=http://a.example/2\nVersion=2\n";
        List<Playlist.Entry> e = Playlist.parse(text, null);
        assertEquals(2, e.size());
        assertEquals("First", e.get(0).name);
        assertEquals("http://a.example/2", e.get(1).url);
        assertEquals("Second", e.get(1).name);
    }

    @Test
    public void hlsManifestIsNotAStationList() {
        String media = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:120\n#EXTINF:6.0,\nseg120.ts\n";
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=96000,CODECS=\"mp4a.40.2\"\nlow/index.m3u8\n";
        String stations = "#EXTM3U\n#EXTINF:-1,Station\nhttp://example.org/live.mp3\n";
        assertTrue(Playlist.isHls(media));
        assertTrue(Playlist.isHls(master));
        assertFalse(Playlist.isHls(stations));
    }

    @Test
    public void playlistSizeIsBounded() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < Playlist.MAX_ENTRIES + 500; i++) sb.append("http://example.org/").append(i).append('\n');
        assertEquals(Playlist.MAX_ENTRIES, Playlist.parse(sb.toString(), null).size());
    }

    @Test
    public void resolveAcceptsOnlyHttp() {
        assertEquals("https://example.org/a", Playlist.resolve(null, "https://example.org/a"));
        assertNull(Playlist.resolve(null, "example.org/a"));
        assertNull(Playlist.resolve(null, "file:///sdcard/a.mp3"));
        assertNull(Playlist.resolve("http://example.org/", "javascript:alert(1)"));
    }

    // ---- frame parsing -------------------------------------------------------------------------

    private static final class Collect implements FrameParser.Sink {
        final ArrayList<byte[]> frames = new ArrayList<byte[]>();
        int codec = -1, rate, channels, objectType, rateIndex, kbps, formats;

        @Override
        public void onFormat(int codec, int sampleRate, int channels, int aacObjectType, int rateIndex, int kbps) {
            this.codec = codec;
            this.rate = sampleRate;
            this.channels = channels;
            this.objectType = aacObjectType;
            this.rateIndex = rateIndex;
            this.kbps = kbps;
            formats++;
        }

        @Override
        public void onFrame(byte[] buf, int off, int len) {
            byte[] f = new byte[len];
            System.arraycopy(buf, off, f, 0, len);
            frames.add(f);
        }
    }

    /** One ADTS frame: AAC-LC, 44.1 kHz, stereo, payload filled with {@code fill}. */
    static byte[] adts(int payload, int fill) {
        int len = payload + 7;
        byte[] f = new byte[len];
        f[0] = (byte) 0xFF;
        f[1] = (byte) 0xF1;
        f[2] = (byte) ((1 << 6) | (4 << 2));        // object type 2, rate index 4, channel msb 0
        f[3] = (byte) ((2 << 6) | ((len >> 11) & 3)); // channels 2
        f[4] = (byte) (len >> 3);
        f[5] = (byte) (((len & 7) << 5) | 0x1F);
        f[6] = (byte) 0xFC;
        for (int i = 7; i < len; i++) f[i] = (byte) fill;
        return f;
    }

    /** One MPEG-1 Layer III frame, 128 kbit/s, 44.1 kHz: 417 bytes. */
    static byte[] mp3() {
        byte[] f = new byte[417];
        f[0] = (byte) 0xFF;
        f[1] = (byte) 0xFB;
        f[2] = (byte) 0x90;
        f[3] = 0x00;
        return f;
    }

    @Test
    public void adtsStreamInArbitraryChunks() throws Exception {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        for (int i = 0; i < 5; i++) s.write(adts(100 + i, i + 1));
        byte[] data = s.toByteArray();
        Collect c = new Collect();
        FrameParser p = new FrameParser(c);
        for (int i = 0; i < data.length; i += 37) p.push(data, i, Math.min(37, data.length - i));
        // The last frame waits for the header after it, which never comes here.
        assertEquals(4, c.frames.size());
        assertEquals(FrameParser.AAC, c.codec);
        assertEquals(44100, c.rate);
        assertEquals(2, c.channels);
        assertEquals(2, c.objectType);
        assertEquals(4, c.rateIndex);
        assertEquals(1, c.formats);
        assertEquals("ADTS header is stripped", 100, c.frames.get(0).length);
        assertEquals(3, c.frames.get(2)[0]);
        assertEquals(0, p.skippedBytes());
    }

    @Test
    public void mp3WithId3TagAndGarbageInBetween() throws Exception {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, 20});
        s.write(new byte[20]);
        s.write(mp3());
        s.write(mp3());
        s.write(new byte[]{1, 2, 3, (byte) 0xFF, 5, 6}); // junk, including a lone 0xFF
        s.write(mp3());
        s.write(mp3());
        s.write(mp3());
        byte[] data = s.toByteArray();
        Collect c = new Collect();
        FrameParser p = new FrameParser(c);
        p.push(data, 0, data.length);
        assertEquals(FrameParser.MP3, c.codec);
        assertEquals(44100, c.rate);
        assertEquals(128, c.kbps);
        assertEquals(2, c.channels);
        assertEquals(4, c.frames.size());
        assertEquals(417, c.frames.get(0).length);
        assertEquals(6, p.skippedBytes());
    }

    @Test
    public void noiseIsNotMistakenForAudio() {
        byte[] noise = new byte[50000];
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) (i * 31 + 7);
        Collect c = new Collect();
        FrameParser p = new FrameParser(c);
        p.push(noise, 0, noise.length);
        assertEquals(0, c.frames.size());
        assertTrue(p.skippedBytes() > 40000);
    }

    // ---- transport stream ----------------------------------------------------------------------

    private static byte[] tsPacket(int pid, boolean start, byte[] payload) {
        byte[] p = new byte[188];
        p[0] = 0x47;
        p[1] = (byte) ((start ? 0x40 : 0) | (pid >> 8));
        p[2] = (byte) pid;
        int stuffing = 184 - payload.length;
        if (stuffing == 0) {
            p[3] = 0x10;
            System.arraycopy(payload, 0, p, 4, payload.length);
        } else {
            p[3] = 0x30; // adaptation field + payload
            p[4] = (byte) (stuffing - 1);
            if (stuffing > 1) p[5] = 0;
            for (int i = 6; i < 4 + stuffing; i++) p[i] = (byte) 0xFF;
            System.arraycopy(payload, 0, p, 4 + stuffing, payload.length);
        }
        return p;
    }

    @Test
    public void hlsTransportStreamYieldsTheAacFrames() throws Exception {
        byte[] pat = {0, 0x00, (byte) 0xB0, 0x0D, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00, 0x00, 0x01, (byte) 0xE1, 0x00, 0, 0, 0, 0};
        byte[] pmt = {0, 0x02, (byte) 0xB0, 0x17, 0x00, 0x01, (byte) 0xC1, 0x00, 0x00, (byte) 0xE1, 0x01, (byte) 0xF0, 0x00,
                0x1B, (byte) 0xE1, 0x02, (byte) 0xF0, 0x00, // H.264 video, must be skipped
                0x0F, (byte) 0xE1, 0x01, (byte) 0xF0, 0x00, // ADTS AAC on pid 0x101
                0, 0, 0, 0};
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        for (int i = 0; i < 3; i++) audio.write(adts(170, 0x40 + i));
        byte[] es = audio.toByteArray();
        byte[] pesHeader = {0, 0, 1, (byte) 0xC0, 0, 0, (byte) 0x80, (byte) 0x80, 5, 0x21, 0, 1, 0, 1};

        ByteArrayOutputStream ts = new ByteArrayOutputStream();
        ts.write(tsPacket(0, true, pat));
        ts.write(tsPacket(0x100, true, pmt));
        ts.write(tsPacket(0x102, true, new byte[]{0, 0, 1, (byte) 0xE0, 0, 0, (byte) 0x80, 0, 0, 9, 9, 9})); // video PES
        int first = 184 - pesHeader.length;
        ByteArrayOutputStream p1 = new ByteArrayOutputStream();
        p1.write(pesHeader);
        p1.write(es, 0, first);
        ts.write(tsPacket(0x101, true, p1.toByteArray()));
        for (int off = first; off < es.length; off += 184) {
            int n = Math.min(184, es.length - off);
            byte[] chunk = new byte[n];
            System.arraycopy(es, off, chunk, 0, n);
            ts.write(tsPacket(0x101, false, chunk));
        }
        byte[] stream = ts.toByteArray();

        final Collect c = new Collect();
        final FrameParser parser = new FrameParser(c);
        TsDemux demux = new TsDemux(new TsDemux.Sink() {
            @Override
            public void onAudio(byte[] buf, int off, int len) {
                parser.push(buf, off, len);
            }
        });
        for (int i = 0; i < stream.length; i += 500) demux.push(stream, i, Math.min(500, stream.length - i));

        assertTrue(demux.foundAudio());
        assertEquals(FrameParser.AAC, c.codec);
        assertEquals(2, c.frames.size());
        assertEquals(170, c.frames.get(0).length);
        byte[] expect = new byte[170];
        java.util.Arrays.fill(expect, (byte) 0x41);
        assertArrayEquals(expect, c.frames.get(1));
    }

    // ---- ICY and search API --------------------------------------------------------------------

    @Test
    public void icyTitle() throws Exception {
        assertEquals("Artist - Song's Title", StreamPlayer.icyTitle("StreamTitle='Artist - Song's Title';StreamUrl='';\0\0\0".getBytes("UTF-8")));
        assertEquals("Für Elise", StreamPlayer.icyTitle("StreamTitle='Für Elise';".getBytes("ISO-8859-1")));
        assertEquals("Grüße", StreamPlayer.icyTitle("StreamTitle='Grüße';".getBytes("UTF-8")));
        assertNull(StreamPlayer.icyTitle("StreamTitle='';".getBytes("UTF-8")));
        assertNull(StreamPlayer.icyTitle(new byte[16]));
        assertEquals(120L, StreamPlayer.number("120\r"));
        assertEquals(0L, StreamPlayer.number("x"));
    }

    @Test
    public void radioBrowserAnswer() throws Exception {
        String json = "[{\"stationuuid\":\"a\",\"name\":\" Deutschlandfunk \",\"url\":\"http://x/dlf.m3u\","
                + "\"url_resolved\":\"https://st01.example/dlf.mp3\",\"favicon\":\"https://example/dlf.png\",\"codec\":\"MP3\"},"
                + "{\"name\":\"No resolved\",\"url\":\"http://example/b.aac\",\"url_resolved\":\"\",\"favicon\":\"\"},"
                + "{\"name\":\"Duplicate\",\"url_resolved\":\"https://st01.example/dlf.mp3\"},"
                + "{\"name\":\"Bad scheme\",\"url_resolved\":\"mms://example/c\"}]";
        List<Station> s = RadioBrowser.parse(json);
        assertEquals(2, s.size());
        assertEquals("Deutschlandfunk", s.get(0).name);
        assertEquals("https://st01.example/dlf.mp3", s.get(0).url);
        assertEquals("https://example/dlf.png", s.get(0).logoUrl);
        assertEquals("http://example/b.aac", s.get(1).url);
        assertNull(s.get(1).logoUrl);
    }

    @Test
    public void radioBrowserQuery() throws Exception {
        String u = RadioBrowser.searchUrl("https://de1.api.radio-browser.info/", "radio eins & co", "DE", "");
        assertTrue(u.startsWith("https://de1.api.radio-browser.info/json/stations/search?"));
        assertTrue(u.contains("&name=radio+eins+%26+co"));
        assertTrue(u.contains("&countrycode=DE"));
        assertFalse(u.contains("&tag="));
        assertTrue(u.contains("hidebroken=true"));
    }

    @Test
    public void redactDropsCredentialsAndQuery() {
        assertEquals("http://host/stream", Net.redact("http://user:secret@host/stream?token=abc"));
        assertEquals("https://host/a", Net.redact("https://host/a"));
    }
}
