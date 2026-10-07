package me.ri3d.welle.logos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/**
 * Just enough DNS for RadioDNS: CNAME and SRV lookups over UDP. Android's Java API can only
 * resolve addresses, and RadioDNS discovery is defined in terms of these two record types.
 */
public final class Dns {
    private Dns() { }

    static final int TYPE_CNAME = 5;
    static final int TYPE_SRV = 33;

    public static final class Srv {
        public String host;
        public int port;
        int priority;
    }

    /** Canonical name behind an alias, or null if the name has no CNAME. */
    public static String cname(String name, List<InetAddress> servers) throws IOException {
        byte[] r = exchange(query(name, TYPE_CNAME, 0x5745), servers);
        return r == null ? null : parseCname(r, r.length);
    }

    /** Best (lowest priority) SRV target, or null. */
    public static Srv srv(String name, List<InetAddress> servers) throws IOException {
        byte[] r = exchange(query(name, TYPE_SRV, 0x5746), servers);
        return r == null ? null : parseSrv(r, r.length);
    }

    static byte[] query(String name, int type, int id) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8);
        o.write(id);
        o.write(0x01); // recursion desired
        o.write(0x00);
        o.write(0);
        o.write(1);    // one question
        for (int i = 0; i < 6; i++) o.write(0);
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] b = label.getBytes();
            o.write(Math.min(b.length, 63));
            o.write(b, 0, Math.min(b.length, 63));
        }
        o.write(0);
        o.write(type >> 8);
        o.write(type);
        o.write(0);
        o.write(1);    // class IN
        return o.toByteArray();
    }

    private static byte[] exchange(byte[] query, List<InetAddress> servers) throws IOException {
        IOException last = null;
        for (InetAddress server : servers) {
            DatagramSocket socket = new DatagramSocket();
            try {
                socket.setSoTimeout(3000);
                socket.send(new DatagramPacket(query, query.length, server, 53));
                byte[] buf = new byte[1500];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                if (p.getLength() < 12 || buf[0] != query[0] || buf[1] != query[1]) continue;
                byte[] out = new byte[p.getLength()];
                System.arraycopy(buf, 0, out, 0, out.length);
                return out;
            } catch (IOException e) {
                last = e;
            } finally {
                socket.close();
            }
        }
        if (last != null) throw last;
        return null;
    }

    static String parseCname(byte[] m, int len) {
        int[] pos = new int[1];
        int answers = skipToAnswers(m, len, pos);
        for (int i = 0; i < answers && pos[0] < len; i++) {
            readName(m, len, pos);
            if (pos[0] + 10 > len) return null;
            int type = u16(m, pos[0]);
            int rdLen = u16(m, pos[0] + 8);
            pos[0] += 10;
            if (type == TYPE_CNAME) return readName(m, len, new int[]{pos[0]});
            pos[0] += rdLen;
        }
        return null;
    }

    static Srv parseSrv(byte[] m, int len) {
        int[] pos = new int[1];
        int answers = skipToAnswers(m, len, pos);
        Srv best = null;
        for (int i = 0; i < answers && pos[0] < len; i++) {
            readName(m, len, pos);
            if (pos[0] + 10 > len) break;
            int type = u16(m, pos[0]);
            int rdLen = u16(m, pos[0] + 8);
            pos[0] += 10;
            if (type == TYPE_SRV && pos[0] + 6 < len) {
                Srv s = new Srv();
                s.priority = u16(m, pos[0]);
                s.port = u16(m, pos[0] + 4);
                s.host = readName(m, len, new int[]{pos[0] + 6});
                if (!s.host.isEmpty() && (best == null || s.priority < best.priority)) best = s;
            }
            pos[0] += rdLen;
        }
        return best;
    }

    /** @return answer count, 0 on an error response; leaves pos at the first answer */
    private static int skipToAnswers(byte[] m, int len, int[] pos) {
        if (len < 12 || (m[3] & 0x0f) != 0) return 0;
        int questions = u16(m, 4);
        pos[0] = 12;
        for (int i = 0; i < questions && pos[0] < len; i++) {
            readName(m, len, pos);
            pos[0] += 4;
        }
        return u16(m, 6);
    }

    /** Reads a possibly compressed name; advances pos past it in the original position. */
    static String readName(byte[] m, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        int end = -1;
        for (int jumps = 0; p < len && jumps < 16; ) {
            int n = m[p] & 0xff;
            if (n == 0) {
                p++;
                break;
            }
            if ((n & 0xc0) == 0xc0) {
                if (p + 1 >= len) break;
                if (end < 0) end = p + 2;
                p = ((n & 0x3f) << 8) | (m[p + 1] & 0xff);
                jumps++;
                continue;
            }
            if (p + 1 + n > len) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(m, p + 1, n));
            p += 1 + n;
        }
        pos[0] = end >= 0 ? end : p;
        return sb.toString();
    }

    private static int u16(byte[] m, int p) {
        return ((m[p] & 0xff) << 8) | (m[p + 1] & 0xff);
    }
}
