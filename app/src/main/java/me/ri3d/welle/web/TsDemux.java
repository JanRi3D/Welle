package me.ri3d.welle.web;

/**
 * Minimal MPEG transport stream demultiplexer for HLS radio: finds the first audio
 * elementary stream (ADTS AAC or MPEG audio) and hands its payload on. Video and all
 * other streams are ignored. Pure Java.
 */
public final class TsDemux {
    public interface Sink {
        void onAudio(byte[] buf, int off, int len);
    }

    private static final int PACKET = 188;

    private final Sink sink;
    private final byte[] carry = new byte[PACKET];
    private int carryLen;
    private int pmtPid = -1;
    private int audioPid = -1;

    public TsDemux(Sink sink) {
        this.sink = sink;
    }

    public boolean foundAudio() {
        return audioPid >= 0;
    }

    public void push(byte[] data, int off, int n) {
        if (carryLen > 0) {
            int need = Math.min(PACKET - carryLen, n);
            System.arraycopy(data, off, carry, carryLen, need);
            carryLen += need;
            off += need;
            n -= need;
            if (carryLen < PACKET) return;
            packet(carry, 0);
            carryLen = 0;
        }
        while (n >= PACKET) {
            if (data[off] != 0x47) { // lost alignment: slide to the next sync byte
                off++;
                n--;
                continue;
            }
            packet(data, off);
            off += PACKET;
            n -= PACKET;
        }
        if (n > 0) {
            System.arraycopy(data, off, carry, 0, n);
            carryLen = n;
        }
    }

    private void packet(byte[] p, int o) {
        if (p[o] != 0x47) return;
        boolean start = (p[o + 1] & 0x40) != 0;
        int pid = ((p[o + 1] & 0x1f) << 8) | (p[o + 2] & 0xff);
        int adaptation = (p[o + 3] >> 4) & 3;
        if ((adaptation & 1) == 0) return; // no payload
        int pos = o + 4;
        if ((adaptation & 2) != 0) pos += 1 + (p[pos] & 0xff);
        int end = o + PACKET;
        if (pos >= end) return;

        if (pid == 0 && start) {
            int s = pos + 1 + (p[pos] & 0xff); // pointer field
            if (s + 12 > end || p[s] != 0) return;
            int sectionEnd = Math.min(end, s + 3 + (((p[s + 1] & 0x0f) << 8) | (p[s + 2] & 0xff)) - 4);
            for (int e = s + 8; e + 4 <= sectionEnd; e += 4) {
                int program = ((p[e] & 0xff) << 8) | (p[e + 1] & 0xff);
                if (program != 0) {
                    pmtPid = ((p[e + 2] & 0x1f) << 8) | (p[e + 3] & 0xff);
                    break;
                }
            }
        } else if (pid == pmtPid && start && audioPid < 0) {
            int s = pos + 1 + (p[pos] & 0xff);
            if (s + 12 > end || p[s] != 2) return;
            int sectionEnd = Math.min(end, s + 3 + (((p[s + 1] & 0x0f) << 8) | (p[s + 2] & 0xff)) - 4);
            int e = s + 12 + (((p[s + 10] & 0x0f) << 8) | (p[s + 11] & 0xff));
            while (e + 5 <= sectionEnd) {
                int type = p[e] & 0xff;
                int esPid = ((p[e + 1] & 0x1f) << 8) | (p[e + 2] & 0xff);
                if (type == 0x0f || type == 0x03 || type == 0x04) { // ADTS AAC, MPEG-1/2 audio
                    audioPid = esPid;
                    break;
                }
                e += 5 + (((p[e + 3] & 0x0f) << 8) | (p[e + 4] & 0xff));
            }
        } else if (pid == audioPid) {
            if (start) {
                // PES header: 00 00 01, stream id, length (2), flags (2), header data length
                if (pos + 9 > end || p[pos] != 0 || p[pos + 1] != 0 || p[pos + 2] != 1) return;
                pos += 9 + (p[pos + 8] & 0xff);
            }
            if (pos < end) sink.onAudio(p, pos, end - pos);
        }
    }
}
