package me.ri3d.welle.logos;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

import me.ri3d.welle.core.Io;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.web.Net;

/**
 * Station logos through RadioDNS (ETSI TS 103 270) and its Service and Programme
 * Information (ETSI TS 102 818):
 *
 *   1. build the service's FQDN from its DAB identifiers,
 *      {scids}.{sid}.{eid}.{gcc}.dab.radiodns.org
 *   2. its CNAME is the broadcaster's authoritative domain
 *   3. the SRV record _radioepg._tcp.{domain} names the SPI server
 *   4. http://{host}:{port}/radiodns/spi/3.1/SI.xml lists services with their logos
 *
 * A broadcaster that does not take part simply has no CNAME; that is a normal result.
 */
public final class RadioDns {
    private RadioDns() { }

    private static final int MAX_SI_BYTES = 6 * 1024 * 1024;
    private static final int MAX_LOGO_BYTES = 1024 * 1024;

    /**
     * Global country code: the country nibble of the SId followed by the ECC, e.g. "de0".
     * Null when the scan did not deliver an ECC.
     */
    public static String gcc(int sid, int ecc) {
        boolean longSid = (sid & 0xffff0000) != 0;
        int e = longSid ? (sid >> 24) & 0xff : ecc;
        int country = longSid ? (sid >> 20) & 0xf : (sid >> 12) & 0xf;
        if (e == 0) return null;
        return String.format(Locale.US, "%x%02x", country, e);
    }

    /** "dab:de0.10bc.d220.0", the bearer id used in SI.xml; null without ECC. */
    public static String bearer(Station s, int eid) {
        String gcc = gcc(s.sid, s.ecc);
        if (gcc == null) return null;
        String sid = (s.sid & 0xffff0000) != 0 ? String.format(Locale.US, "%08x", s.sid) : String.format(Locale.US, "%04x", s.sid);
        return String.format(Locale.US, "dab:%s.%04x.%s.%x", gcc, eid, sid, s.scids);
    }

    /** RadioDNS lookup name for a bearer id. */
    public static String fqdn(String bearer) {
        String[] p = bearer.substring(4).split("\\.");
        return p[3] + "." + p[2] + "." + p[1] + "." + p[0] + ".dab.radiodns.org";
    }

    /**
     * @param siCache parsed SI documents by authoritative domain, shared across one run;
     *                broadcasters publish all their services in one file
     * @return image bytes, or null if no logo is published
     */
    public static byte[] fetchLogo(Station s, List<InetAddress> dns, Map<String, Map<String, String>> siCache) throws IOException {
        for (Station.Loc loc : s.locs) {
            String bearer = bearer(s, loc.eid);
            if (bearer == null) return null;
            String authority = Dns.cname(fqdn(bearer), dns);
            if (authority == null || authority.isEmpty()) continue;
            Map<String, String> logos = siCache.get(authority);
            if (logos == null) {
                logos = new HashMap<String, String>();
                Dns.Srv srv = Dns.srv("_radioepg._tcp." + authority, dns);
                if (srv != null) {
                    String url = (srv.port == 443 ? "https://" : "http://") + srv.host
                            + (srv.port == 80 || srv.port == 443 ? "" : ":" + srv.port) + "/radiodns/spi/3.1/SI.xml";
                    HttpURLConnection c = Net.open(url, null, 20000);
                    try {
                        logos = parseSi(new Limited(c.getInputStream(), MAX_SI_BYTES));
                    } finally {
                        c.disconnect();
                    }
                }
                siCache.put(authority, logos);
            }
            String logoUrl = logos.get(bearer);
            if (logoUrl != null) return Net.get(logoUrl, MAX_LOGO_BYTES);
        }
        return null;
    }

    /** @return lower-case bearer id -> URL of the best logo of that service */
    public static Map<String, String> parseSi(InputStream xml) throws IOException {
        final HashMap<String, String> out = new HashMap<String, String>();
        try {
            SAXParserFactory.newInstance().newSAXParser().parse(xml, new DefaultHandler() {
                final ArrayList<String> bearers = new ArrayList<String>();
                String best;
                int bestScore;
                boolean inService;

                @Override
                public void startElement(String uri, String local, String qName, Attributes a) {
                    String n = name(local, qName);
                    if ("service".equals(n)) {
                        inService = true;
                        bearers.clear();
                        best = null;
                        bestScore = Integer.MAX_VALUE;
                    } else if (inService && "bearer".equals(n)) {
                        String id = a.getValue("id");
                        if (id != null && id.toLowerCase(Locale.US).startsWith("dab:")) bearers.add(id.toLowerCase(Locale.US));
                    } else if (inService && "multimedia".equals(n)) {
                        String url = a.getValue("url");
                        if (url == null || !url.startsWith("http")) return;
                        int score = logoScore(a.getValue("width"), a.getValue("height"), a.getValue("type"));
                        if (score < bestScore) {
                            bestScore = score;
                            best = url;
                        }
                    }
                }

                @Override
                public void endElement(String uri, String local, String qName) {
                    if ("service".equals(name(local, qName))) {
                        inService = false;
                        if (best != null) for (String b : bearers) out.put(b, best);
                    }
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SI.xml could not be parsed");
        }
        return out;
    }

    /**
     * Element name without prefix. Android's parser reports it as the local name and leaves
     * qName empty; the desktop JDK does the opposite.
     */
    private static String name(String local, String qName) {
        if (local != null && local.length() > 0) return local;
        return qName == null ? "" : qName.substring(qName.indexOf(':') + 1);
    }

    /** Lower is better: square logos near 256 px first, then other squares, then the rest. */
    static int logoScore(String w, String h, String type) {
        int width = number(w), height = number(h);
        if (width == 0 && "logo_colour_square".equals(type)) width = height = 32;
        if (width == 0 && "logo_colour_rectangle".equals(type)) {
            width = 112;
            height = 32;
        }
        if (width == 0) return 50000;
        int score = Math.abs(width - 256);
        if (width < 128) score += 5000;
        if (width != height) score += 10000;
        return score;
    }

    private static int number(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Stops a download that grows past the limit. */
    private static final class Limited extends InputStream {
        private final InputStream in;
        private int left;

        Limited(InputStream in, int max) {
            this.in = in;
            left = max;
        }

        @Override
        public int read() throws IOException {
            if (--left < 0) throw new IOException("document too large");
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0 && (left -= n) < 0) throw new IOException("document too large");
            return n;
        }

        @Override
        public void close() {
            Io.close(in);
        }
    }
}
