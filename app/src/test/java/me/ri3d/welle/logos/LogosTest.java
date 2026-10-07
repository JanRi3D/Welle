package me.ri3d.welle.logos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

import me.ri3d.welle.core.Station;

public class LogosTest {

    private static void name(ByteArrayOutputStream o, String n) {
        for (String label : n.split("\\.")) {
            o.write(label.length());
            o.write(label.getBytes(), 0, label.length());
        }
        o.write(0);
    }

    /** Header + echoed question + one answer whose owner name is a pointer to the question. */
    private static ByteArrayOutputStream response(byte[] query, int type, int rcode) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(query[0]);
        o.write(query[1]);
        o.write(0x81);
        o.write(0x80 | rcode);
        o.write(0);
        o.write(1);
        o.write(0);
        o.write(rcode == 0 ? 1 : 0);
        for (int i = 0; i < 4; i++) o.write(0);
        o.write(query, 12, query.length - 12);
        if (rcode != 0) return o;
        o.write(0xC0);
        o.write(0x0C);
        o.write(0);
        o.write(type);
        o.write(0);
        o.write(1);
        for (int i = 0; i < 4; i++) o.write(0); // TTL
        return o;
    }

    @Test
    public void queryIsWellFormed() {
        byte[] q = Dns.query("0.d220.10bc.de0.dab.radiodns.org", Dns.TYPE_CNAME, 0x1234);
        assertEquals(0x12, q[0]);
        assertEquals(0x34, q[1]);
        assertEquals(1, q[2]);       // recursion desired
        assertEquals(1, q[5]);       // one question
        assertEquals(1, q[12]);      // first label "0"
        assertEquals('0', q[13]);
        assertEquals(4, q[14]);      // "d220"
        assertEquals(Dns.TYPE_CNAME, q[q.length - 3]);
        assertEquals(0, q[q.length - 5]); // root label
        int[] pos = {12};
        assertEquals("0.d220.10bc.de0.dab.radiodns.org", Dns.readName(q, q.length, pos));
        assertEquals(q.length - 4, pos[0]);
    }

    @Test
    public void cnameAnswerWithCompression() {
        byte[] q = Dns.query("0.d220.10bc.de0.dab.radiodns.org", Dns.TYPE_CNAME, 7);
        ByteArrayOutputStream o = response(q, Dns.TYPE_CNAME, 0);
        // rdata: "rdns" + pointer to "radiodns.org" inside the question
        int radiodnsAt = 12 + 2 + 5 + 5 + 4 + 4;
        o.write(0);
        o.write(7);
        o.write(4);
        o.write("rdns".getBytes(), 0, 4);
        o.write(0xC0);
        o.write(radiodnsAt);
        byte[] r = o.toByteArray();
        assertEquals("rdns.radiodns.org", Dns.parseCname(r, r.length));
    }

    @Test
    public void noCnameMeansNoRadioDns() {
        byte[] q = Dns.query("0.ffff.ffff.de0.dab.radiodns.org", Dns.TYPE_CNAME, 7);
        byte[] nx = response(q, Dns.TYPE_CNAME, 3).toByteArray();
        assertNull(Dns.parseCname(nx, nx.length));
        assertNull(Dns.parseCname(new byte[5], 5));
    }

    @Test
    public void srvAnswer() {
        byte[] q = Dns.query("_radioepg._tcp.rdns.example.org", Dns.TYPE_SRV, 9);
        ByteArrayOutputStream o = response(q, Dns.TYPE_SRV, 0);
        ByteArrayOutputStream rd = new ByteArrayOutputStream();
        rd.write(0);
        rd.write(10);   // priority
        rd.write(0);
        rd.write(5);    // weight
        rd.write(0x1F);
        rd.write(0x90); // port 8080
        name(rd, "epg.example.org");
        o.write(0);
        o.write(rd.size());
        o.write(rd.toByteArray(), 0, rd.size());
        byte[] r = o.toByteArray();
        Dns.Srv s = Dns.parseSrv(r, r.length);
        assertEquals("epg.example.org", s.host);
        assertEquals(8080, s.port);
    }

    @Test
    public void truncatedOrHostileAnswersDoNotLoopOrThrow() {
        byte[] loop = new byte[40];
        loop[5] = 1;
        loop[7] = 1;
        loop[12] = (byte) 0xC0;
        loop[13] = 12; // a name that points at itself
        Dns.parseCname(loop, loop.length);
        Dns.parseSrv(loop, loop.length);
        byte[] q = Dns.query("a.example.org", Dns.TYPE_CNAME, 1);
        byte[] cut = response(q, Dns.TYPE_CNAME, 0).toByteArray();
        assertNull(Dns.parseCname(cut, cut.length - 3));
    }

    // ---- RadioDNS names ------------------------------------------------------------------------

    private static Station station(int sid, int ecc, int scids) {
        Station s = new Station();
        s.sid = sid;
        s.ecc = ecc;
        s.scids = scids;
        return s;
    }

    @Test
    public void bearerAndLookupName() {
        Station r1 = station(0xD220, 0xE0, 0);
        assertEquals("de0", RadioDns.gcc(0xD220, 0xE0));
        assertEquals("dab:de0.10bc.d220.0", RadioDns.bearer(r1, 0x10BC));
        assertEquals("0.d220.10bc.de0.dab.radiodns.org", RadioDns.fqdn("dab:de0.10bc.d220.0"));
        // UK example from the RadioDNS documentation: ECC E1, EId C181, SId C36B
        assertEquals("0.c36b.c181.ce1.dab.radiodns.org", RadioDns.fqdn(RadioDns.bearer(station(0xC36B, 0xE1, 0), 0xC181)));
    }

    @Test
    public void withoutEccThereIsNoLookup() {
        assertNull(RadioDns.gcc(0xD220, 0));
        assertNull(RadioDns.bearer(station(0xD220, 0, 0), 0x10BC));
    }

    @Test
    public void longServiceIdCarriesItsOwnCountryCode() {
        assertEquals("de0", RadioDns.gcc(0xE0D12345, 0));
        assertEquals("dab:de0.10bc.e0d12345.1", RadioDns.bearer(station(0xE0D12345, 0, 1), 0x10BC));
    }

    // ---- SI document ---------------------------------------------------------------------------

    @Test
    public void siDocumentGivesTheBestLogoPerBearer() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<serviceInformation xmlns=\"http://www.worlddab.org/schemas/spi/31\"><services>"
                + "<service><shortName>r1</shortName>"
                + "<mediaDescription><multimedia url=\"http://x/32.png\" type=\"logo_colour_square\"/></mediaDescription>"
                + "<mediaDescription><multimedia url=\"http://x/600.png\" type=\"logo_unrestricted\" width=\"600\" height=\"600\"/></mediaDescription>"
                + "<mediaDescription><multimedia url=\"http://x/320x240.png\" type=\"logo_unrestricted\" width=\"320\" height=\"240\"/></mediaDescription>"
                + "<mediaDescription><multimedia url=\"http://x/128.png\" type=\"logo_unrestricted\" width=\"128\" height=\"128\"/></mediaDescription>"
                + "<bearer id=\"dab:de0.10BC.D220.0\" mimeValue=\"audio/aacp\" cost=\"20\"/>"
                + "<bearer id=\"fm:de0.d220.09580\" cost=\"30\"/>"
                + "<bearer id=\"dab:de0.2222.d220.0\"/>"
                + "</service>"
                + "<service><shortName>nologo</shortName><bearer id=\"dab:de0.10bc.d221.0\"/></service>"
                + "<service><bearer id=\"dab:de0.10bc.d222.0\"/>"
                + "<mediaDescription><multimedia url=\"ftp://x/bad.png\" width=\"128\" height=\"128\"/></mediaDescription>"
                + "<mediaDescription><multimedia url=\"https://x/wide.png\" type=\"logo_colour_rectangle\"/></mediaDescription></service>"
                + "</services></serviceInformation>";
        Map<String, String> logos = RadioDns.parseSi(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        assertEquals("http://x/128.png", logos.get("dab:de0.10bc.d220.0"));
        assertEquals("http://x/128.png", logos.get("dab:de0.2222.d220.0"));
        assertNull("no logo published", logos.get("dab:de0.10bc.d221.0"));
        assertEquals("https://x/wide.png", logos.get("dab:de0.10bc.d222.0"));
        assertEquals(3, logos.size());
    }

    @Test
    public void squareLogosNearThePanelSizeWin() {
        assertTrue(RadioDns.logoScore("128", "128", "") < RadioDns.logoScore("600", "600", ""));
        assertTrue(RadioDns.logoScore("600", "600", "") < RadioDns.logoScore("320", "240", ""));
        assertTrue(RadioDns.logoScore("256", "256", "") < RadioDns.logoScore("128", "128", ""));
        assertTrue(RadioDns.logoScore(null, null, "logo_colour_square") < RadioDns.logoScore(null, null, "logo_colour_rectangle"));
        assertTrue(RadioDns.logoScore("320", "240", "") < RadioDns.logoScore(null, null, null));
    }

    @Test
    public void logoFileNamesAreSafe() {
        assertEquals("dab_d220.png", LogoStore.fileName("dab:d220"));
        assertEquals("web____etc_passwd.png", LogoStore.fileName("web:../etc/passwd"));
    }
}
