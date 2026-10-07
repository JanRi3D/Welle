package me.ri3d.welle.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.TimeZone;

public class CoreTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Station dab(int sid, String name, int eid, int khz) {
        Station s = new Station();
        s.sid = sid;
        s.id = Station.dabId(sid);
        s.name = name;
        s.ecc = 0xE0;
        Station.Loc l = new Station.Loc();
        l.eid = eid;
        l.khz = khz;
        l.ensemble = "Ensemble " + Integer.toHexString(eid);
        s.locs.add(l);
        return s;
    }

    // ---- identity and persistence --------------------------------------------------------------

    @Test
    public void mediaKeyActsOncePerStrokeEvenIfOnlyTheReleaseIsReported() {
        final int down = 0, up = 1, next = 87, prev = 88, volumeUp = 24;
        assertEquals(me.ri3d.welle.RadioService.ACTION_NEXT, me.ri3d.welle.MediaButtonReceiver.decide(down, 0, next));
        assertNull("auto-repeat", me.ri3d.welle.MediaButtonReceiver.decide(down, 1, next));
        assertNull("release of a handled press", me.ri3d.welle.MediaButtonReceiver.decide(up, 0, next));
        assertEquals("release only", me.ri3d.welle.RadioService.ACTION_PREV, me.ri3d.welle.MediaButtonReceiver.decide(up, 0, prev));
        assertNull("not a media key", me.ri3d.welle.MediaButtonReceiver.decide(down, 0, volumeUp));
    }

    @Test
    public void eventLogKeepsTheNewestLinesWithinItsCap() throws Exception {
        Diag.init(tmp.getRoot());
        for (int i = 0; i < 3000; i++) Diag.note("event " + i);
        File f = new File(tmp.getRoot(), "events.log");
        assertTrue("" + f.length(), f.length() > Diag.MAX_BYTES / 3 && f.length() < Diag.MAX_BYTES + 200);
        String text = new String(Io.readAll(new java.io.FileInputStream(f), 1 << 20), "UTF-8");
        assertTrue(text.endsWith("event 2999\n"));
        assertTrue("cut at a line break", text.matches("(?s)\\d\\d-\\d\\d \\d\\d:.*"));
        Diag.init(null);
        Diag.note("switched off: nothing is written, nothing is thrown");
        assertTrue(text.length() == f.length());
    }

    @Test
    public void dabIdentityIsTheServiceIdNotTheName() {
        Station a = dab(0xD220, "radioeins", 0x10BC, 194064);
        Station renamed = dab(0xD220, "radioeins vom rbb", 0x1234, 178352);
        assertEquals(a.id, renamed.id);
        assertEquals("dab:d220", a.id);
    }

    @Test
    public void webIdentityIsStablePerUrl() {
        Station a = Station.web("One", "https://example.org/stream.mp3", null);
        Station b = Station.web("Other name", " https://example.org/stream.mp3 ", null);
        assertEquals(a.id, b.id);
        assertTrue(a.id.startsWith("web:"));
        assertFalse(a.id.equals(Station.web("x", "https://example.org/other.mp3", null).id));
        assertFalse(a.isDab());
    }

    @Test
    public void stationsAndPresetsSurviveARestart() throws Exception {
        File f = new File(tmp.getRoot(), "stations.json");
        StationStore store = new StationStore(f);
        ArrayList<Station> found = new ArrayList<Station>();
        found.add(dab(0xD220, "radioeins", 0x10BC, 194064));
        found.add(dab(0xD210, "Deutschlandfunk", 0x10BC, 178352));
        store.applyScan(found, true);
        Station web = Station.web("Web One", "http://example.org/a", "http://example.org/a.png");
        assertTrue(store.addWeb(web));
        assertFalse("same URL twice", store.addWeb(Station.web("dup", "http://example.org/a", null)));
        store.setPreset(0, "dab:d220");
        store.setPreset(59, web.id);

        StationStore again = new StationStore(f);
        assertEquals(2, again.dab.size());
        assertEquals(1, again.web.size());
        assertEquals("dab:d220", again.presets[0]);
        assertEquals(web.id, again.presets[59]);
        assertNull(again.presets[1]);
        Station r1 = again.find("dab:d220");
        assertNotNull(r1);
        assertEquals("radioeins", r1.name);
        assertEquals(194064, r1.loc().khz);
        assertEquals(0x10BC, r1.loc().eid);
        assertEquals(0xE0, r1.ecc);
        assertEquals("http://example.org/a.png", again.find(web.id).logoUrl);
        assertEquals(0, again.presetOf("dab:d220"));
        assertEquals(-1, again.presetOf("dab:ffff"));
    }

    @Test
    public void presetFollowsTheServiceAcrossARescanOnAnotherChannel() {
        StationStore store = new StationStore(new File(tmp.getRoot(), "s.json"));
        ArrayList<Station> first = new ArrayList<Station>();
        first.add(dab(0xD220, "radioeins", 0x10BC, 194064));
        store.applyScan(first, true);
        store.setPreset(3, "dab:d220");
        // Same service, renamed and found on a different ensemble and channel.
        ArrayList<Station> second = new ArrayList<Station>();
        second.add(dab(0xD220, "radioeins NEU", 0x2222, 178352));
        store.applyScan(second, true);
        Station s = store.find(store.presets[3]);
        assertEquals("radioeins NEU", s.name);
        assertEquals(178352, s.loc().khz);
        assertFalse(s.missing);
    }

    @Test
    public void damagedFileStartsEmptyInsteadOfCrashing() throws Exception {
        File f = new File(tmp.getRoot(), "bad.json");
        FileOutputStream out = new FileOutputStream(f);
        out.write("{ this is not json".getBytes("UTF-8"));
        out.close();
        StationStore store = new StationStore(f);
        assertTrue(store.dab.isEmpty());
        assertTrue(store.web.isEmpty());
    }

    // ---- scan modes ----------------------------------------------------------------------------

    @Test
    public void sameServiceOnTwoEnsemblesBecomesOneStationWithTwoLocations() {
        ArrayList<Station> found = new ArrayList<Station>();
        StationStore.mergeFound(found, dab(0xD220, "radioeins", 0x10BC, 194064));
        StationStore.mergeFound(found, dab(0xD220, "radioeins", 0x2222, 178352));
        StationStore.mergeFound(found, dab(0xD220, "radioeins", 0x2222, 178352)); // reported twice
        StationStore.mergeFound(found, dab(0xD210, "Dlf", 0x10BC, 194064));
        assertEquals(2, found.size());
        assertEquals(2, found.get(0).locs.size());
        assertTrue(found.get(0).hasEnsemble(0x2222));
    }

    @Test
    public void mode1KeepsFavouritesThatWereNotFoundAgain() {
        StationStore store = new StationStore(new File(tmp.getRoot(), "m1.json"));
        ArrayList<Station> first = new ArrayList<Station>();
        first.add(dab(1, "Favourite", 0x10, 174928));
        first.add(dab(2, "Not a favourite", 0x10, 174928));
        store.applyScan(first, true);
        store.setPreset(0, Station.dabId(1));

        ArrayList<Station> second = new ArrayList<Station>();
        second.add(dab(3, "New one", 0x20, 176640));
        StationStore.ScanOutcome out = store.applyScan(second, true);

        assertEquals(1, out.found);
        assertEquals(1, out.keptMissing);
        assertEquals(2, store.dab.size());
        assertTrue(store.find(Station.dabId(1)).missing);
        assertEquals(174928, store.find(Station.dabId(1)).loc().khz);
        assertNull(store.find(Station.dabId(2)));
        assertEquals(Station.dabId(1), store.presets[0]);
    }

    @Test
    public void mode1ClearsTheMissingFlagWhenTheFavouriteReturns() {
        StationStore store = new StationStore(new File(tmp.getRoot(), "m1b.json"));
        ArrayList<Station> list = new ArrayList<Station>();
        list.add(dab(1, "Favourite", 0x10, 174928));
        store.applyScan(list, true);
        store.setPreset(0, Station.dabId(1));
        store.applyScan(new ArrayList<Station>(), true);
        assertTrue(store.find(Station.dabId(1)).missing);
        ArrayList<Station> back = new ArrayList<Station>();
        back.add(dab(1, "Favourite", 0x10, 174928));
        store.applyScan(back, true);
        assertFalse(store.find(Station.dabId(1)).missing);
        assertEquals(1, store.dab.size());
    }

    @Test
    public void mode2ReplacesEverythingAndClearsDeadDabPresetsOnly() {
        StationStore store = new StationStore(new File(tmp.getRoot(), "m2.json"));
        ArrayList<Station> first = new ArrayList<Station>();
        first.add(dab(1, "Gone", 0x10, 174928));
        first.add(dab(2, "Stays", 0x10, 174928));
        store.applyScan(first, false);
        Station web = Station.web("Web", "http://example.org/w", null);
        store.addWeb(web);
        store.setPreset(0, Station.dabId(1));
        store.setPreset(1, Station.dabId(2));
        store.setPreset(2, web.id);

        ArrayList<Station> second = new ArrayList<Station>();
        second.add(dab(2, "Stays", 0x10, 174928));
        StationStore.ScanOutcome out = store.applyScan(second, false);

        assertEquals(1, out.clearedPresets);
        assertEquals(0, out.keptMissing);
        assertEquals(1, store.dab.size());
        assertNull(store.presets[0]);
        assertEquals(Station.dabId(2), store.presets[1]);
        assertEquals("web presets are not touched by a DAB scan", web.id, store.presets[2]);
        assertEquals(1, store.web.size());
    }

    @Test
    public void scanFindingNothingGivesAGenuinelyEmptyList() {
        StationStore store = new StationStore(new File(tmp.getRoot(), "e.json"));
        ArrayList<Station> first = new ArrayList<Station>();
        first.add(dab(1, "A", 0x10, 174928));
        store.applyScan(first, false);
        StationStore.ScanOutcome out = store.applyScan(new ArrayList<Station>(), false);
        assertEquals(0, out.found);
        assertTrue(store.dab.isEmpty());
        assertTrue(store.ensembles().isEmpty());
    }

    @Test
    public void ensemblesAreCountedAndOrderedByChannel() {
        StationStore store = new StationStore(new File(tmp.getRoot(), "ens.json"));
        ArrayList<Station> list = new ArrayList<Station>();
        list.add(dab(1, "A", 0x10BC, 194064));
        list.add(dab(2, "B", 0x10BC, 194064));
        list.add(dab(3, "C", 0x1001, 178352));
        store.applyScan(list, false);
        assertEquals(2, store.ensembles().size());
        assertEquals(178352, store.ensembles().get(0).khz);
        assertEquals(2, store.ensembles().get(1).count);
    }

    @Test
    public void monogramsLikeTheReference() {
        assertEquals("88", dab(1, "rbb 88.8", 1, 1).mono());
        assertEquals("24", dab(1, "rbb24 Inforadio", 1, 1).mono());
        assertEquals("AB", dab(1, "Antenne Brandenburg", 1, 1).mono());
        assertEquals("DK", dab(1, "Dlf Kultur", 1, 1).mono());
        assertEquals("Fr", dab(1, "Fritz", 1, 1).mono());
        assertEquals("?", dab(1, "", 1, 1).mono());
    }

    // ---- USB lifecycle -------------------------------------------------------------------------

    private static int run(int state, int... events) {
        for (int e : events) state = UsbLink.next(state, e);
        return state;
    }

    @Test
    public void usbHappyPath() {
        assertEquals(UsbLink.READY, run(UsbLink.NO_DEVICE, UsbLink.EV_SEARCH_FOUND, UsbLink.EV_GRANTED, UsbLink.EV_TUNER_READY));
    }

    @Test
    public void usbFailureStatesAreDistinct() {
        assertEquals(UsbLink.NO_DEVICE, run(UsbLink.NO_DEVICE, UsbLink.EV_SEARCH_NONE));
        assertEquals(UsbLink.DENIED, run(UsbLink.NO_DEVICE, UsbLink.EV_SEARCH_FOUND, UsbLink.EV_DENIED));
        assertEquals(UsbLink.UNSUPPORTED, run(UsbLink.NO_DEVICE, UsbLink.EV_BAD_DESCRIPTORS));
        assertEquals(UsbLink.UNSUPPORTED, run(UsbLink.NO_DEVICE, UsbLink.EV_SEARCH_FOUND, UsbLink.EV_OPEN_FAILED));
        assertEquals(UsbLink.UNSUPPORTED, run(UsbLink.NO_DEVICE, UsbLink.EV_SEARCH_FOUND, UsbLink.EV_GRANTED, UsbLink.EV_TUNER_FAILED));
        assertEquals(UsbLink.OFF, run(UsbLink.READY, UsbLink.EV_DISABLED));
    }

    @Test
    public void unplugAndReplugDuringUse() {
        int s = run(UsbLink.NO_DEVICE, UsbLink.EV_SEARCH_FOUND, UsbLink.EV_GRANTED, UsbLink.EV_TUNER_READY, UsbLink.EV_DETACHED);
        assertEquals(UsbLink.DISCONNECTED, s);
        assertFalse(UsbLink.attached(s));
        // A callback still in flight from the unplugged tuner must not resurrect it.
        assertEquals(UsbLink.DISCONNECTED, run(s, UsbLink.EV_TUNER_READY));
        assertEquals(UsbLink.READY, run(s, UsbLink.EV_SEARCH_FOUND, UsbLink.EV_GRANTED, UsbLink.EV_TUNER_READY));
        // Repeated cycles end in the same place.
        for (int i = 0; i < 5; i++) {
            s = run(s, UsbLink.EV_SEARCH_FOUND, UsbLink.EV_GRANTED, UsbLink.EV_TUNER_READY, UsbLink.EV_DETACHED);
        }
        assertEquals(UsbLink.DISCONNECTED, s);
    }

    @Test
    public void usbIgnoresEventsThatDoNotApply() {
        assertEquals("no second attach while attached", UsbLink.READY, run(UsbLink.READY, UsbLink.EV_SEARCH_FOUND));
        assertEquals(UsbLink.READY, run(UsbLink.READY, UsbLink.EV_SEARCH_NONE));
        assertEquals("scan end reports ready again", UsbLink.READY, run(UsbLink.READY, UsbLink.EV_TUNER_READY));
        assertEquals(UsbLink.NO_DEVICE, run(UsbLink.NO_DEVICE, UsbLink.EV_GRANTED));
        assertEquals(UsbLink.NO_DEVICE, run(UsbLink.DENIED, UsbLink.EV_DETACHED));
        assertEquals("unplug while waiting for the dialog", UsbLink.DISCONNECTED, run(UsbLink.PERMISSION, UsbLink.EV_DETACHED));
        assertEquals(UsbLink.OFF, run(UsbLink.OFF, UsbLink.EV_DETACHED));
        assertEquals("manual search works when automatic search is off", UsbLink.PERMISSION, run(UsbLink.OFF, UsbLink.EV_SEARCH_FOUND));
    }

    // ---- audio focus ---------------------------------------------------------------------------

    @Test
    public void navigationPromptDucksAndRestoresOnlyOnGain() {
        FocusPolicy p = new FocusPolicy();
        assertEquals(FocusPolicy.NONE, p.onChange(FocusPolicy.LOSS_CAN_DUCK, true, false, 0.5f));
        assertEquals(0.5f, p.gain, 0f);
        assertEquals(FocusPolicy.NONE, p.onChange(FocusPolicy.GAIN, true, false, 0.5f));
        assertEquals(1f, p.gain, 0f);
    }

    @Test
    public void muteSettingSilencesInsteadOfDucking() {
        FocusPolicy p = new FocusPolicy();
        p.onChange(FocusPolicy.LOSS_CAN_DUCK, false, true, 0.75f);
        assertEquals(0f, p.gain, 0f);
    }

    @Test
    public void audioNeverComesBackDuringAPhoneCall() {
        FocusPolicy p = new FocusPolicy();
        p.onChange(FocusPolicy.LOSS_TRANSIENT, true, false, 0.5f);
        assertEquals(0f, p.gain, 0f);
        // A navigation prompt during the call must not raise the level to the duck volume.
        p.onChange(FocusPolicy.LOSS_CAN_DUCK, true, false, 0.5f);
        assertEquals(0f, p.gain, 0f);
        p.onChange(FocusPolicy.GAIN, true, false, 0.5f);
        assertEquals(1f, p.gain, 0f);
    }

    @Test
    public void permanentLossFollowsTheSettings() {
        FocusPolicy exit = new FocusPolicy();
        assertEquals(FocusPolicy.EXIT, exit.onChange(FocusPolicy.LOSS, true, true, 0.5f));
        assertEquals(0f, exit.gain, 0f);

        FocusPolicy mute = new FocusPolicy();
        assertEquals(FocusPolicy.NONE, mute.onChange(FocusPolicy.LOSS, false, true, 0.5f));
        assertTrue(mute.mutedByLoss);
        assertEquals(0f, mute.gain, 0f);
        mute.reset();
        assertFalse(mute.mutedByLoss);
        assertEquals(1f, mute.gain, 0f);

        FocusPolicy pause = new FocusPolicy();
        assertEquals(FocusPolicy.PAUSE, pause.onChange(FocusPolicy.LOSS, false, false, 0.5f));
    }

    // ---- service following ---------------------------------------------------------------------

    @Test
    public void followerWaitsThenSwitchesAndThenCoolsDown() {
        ServiceFollower f = new ServiceFollower();
        assertEquals(-1, f.tick(0, true, true, 2, 0));
        assertEquals(-1, f.tick(1000, true, false, 2, 0));
        assertEquals("short dropout: stay", -1, f.tick(4000, true, false, 2, 0));
        assertEquals(1, f.tick(1000 + ServiceFollower.LOST_MS, true, false, 2, 0));
        long t = 1000 + ServiceFollower.LOST_MS;
        // The alternative is bad as well: no flapping before the cooldown is over.
        assertEquals(-1, f.tick(t + ServiceFollower.LOST_MS + 100, true, false, 2, 1));
        assertEquals(0, f.tick(t + ServiceFollower.COOLDOWN_MS, true, false, 2, 1));
    }

    @Test
    public void followerNeedsAnAlternativeAndTheSetting() {
        ServiceFollower f = new ServiceFollower();
        f.tick(0, true, false, 1, 0);
        assertEquals("only one location", -1, f.tick(60000, true, false, 1, 0));
        ServiceFollower off = new ServiceFollower();
        off.tick(0, false, false, 2, 0);
        assertEquals("switched off", -1, off.tick(60000, false, false, 2, 0));
    }

    @Test
    public void followerResetsWhenReceptionReturns() {
        ServiceFollower f = new ServiceFollower();
        f.tick(0, true, false, 2, 0);
        f.tick(5000, true, true, 2, 0);
        assertEquals(-1, f.tick(7000, true, false, 2, 0));
        assertEquals(-1, f.tick(12000, true, false, 2, 0));
        assertEquals(1, f.tick(13000, true, false, 2, 0));
    }

    // ---- sunrise/sunset ------------------------------------------------------------------------

    private static long utc(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d, h, min);
        return c.getTimeInMillis();
    }

    @Test
    public void berlinSunsetInSummerAndWinter() {
        double lat = 52.52, lon = 13.40;
        // 21 June: sunset about 19:33 UTC, sunrise about 02:43 UTC
        assertFalse(SunClock.isNight(utc(2026, 6, 21, 19, 0), lat, lon));
        assertTrue(SunClock.isNight(utc(2026, 6, 21, 20, 15), lat, lon));
        assertTrue(SunClock.isNight(utc(2026, 6, 21, 2, 0), lat, lon));
        assertFalse(SunClock.isNight(utc(2026, 6, 21, 3, 30), lat, lon));
        // 21 December: sunset about 14:54 UTC, sunrise about 07:15 UTC
        assertFalse(SunClock.isNight(utc(2026, 12, 21, 14, 20), lat, lon));
        assertTrue(SunClock.isNight(utc(2026, 12, 21, 15, 30), lat, lon));
        assertTrue(SunClock.isNight(utc(2026, 12, 21, 6, 30), lat, lon));
        assertFalse(SunClock.isNight(utc(2026, 12, 21, 12, 0), lat, lon));
    }

    @Test
    public void fallbackPositionComesFromTheTimeZone() {
        assertEquals(15.0, SunClock.fallbackLon(TimeZone.getTimeZone("Europe/Berlin")), 0.001);
        assertEquals(0.0, SunClock.fallbackLon(TimeZone.getTimeZone("UTC")), 0.001);
    }

    // ---- channel table -------------------------------------------------------------------------

    @Test
    public void channelTableMatchesTheTunerLibrary() {
        assertEquals(41, DabChannels.COUNT);
        assertEquals(DabChannels.COUNT, DabChannels.LABEL.length);
        assertEquals("5A", DabChannels.label(174928));
        assertEquals("7D", DabChannels.label(194064));
        assertEquals("5C", DabChannels.label(178352));
        assertEquals("13F", DabChannels.label(239200));
        assertEquals("10N", DabChannels.LABEL[21]);
        assertEquals("194.064", DabChannels.mhz(194064));
        for (int i = 1; i < DabChannels.COUNT; i++) assertTrue(DabChannels.KHZ[i] > DabChannels.KHZ[i - 1]);
    }

    @Test
    public void rulerSkipsTheNChannels() {
        assertEquals(0.5f, DabChannels.rulerPos(0), 0f);
        assertEquals(11.5f, DabChannels.rulerPos(DabChannels.indexOf(194064)), 0f);
        assertEquals(37.5f, DabChannels.rulerPos(40), 0f);
        assertEquals(-1, DabChannels.rulerTick(21));
        assertEquals(21, DabChannels.rulerTick(22));
        assertEquals("10B", DabChannels.rulerLabel(21));
        assertEquals("13F", DabChannels.rulerLabel(37));
        int ticks = 0;
        for (int i = 0; i < DabChannels.COUNT; i++) {
            int t = DabChannels.rulerTick(i);
            if (t >= 0) {
                assertEquals(DabChannels.LABEL[i], DabChannels.rulerLabel(t));
                ticks++;
            }
        }
        assertEquals(DabChannels.RULER_TICKS, ticks);
    }

    @Test
    public void settingsDefaultsMatchTheReference() {
        assertTrue(Settings.defBool(Settings.FINISH_FOCUS));
        assertTrue(Settings.defBool(Settings.SERVICE_FOLLOWING));
        assertTrue(Settings.defBool(Settings.MENU_TOP));
        assertTrue(Settings.defBool(Settings.SLIDESHOW));
        assertFalse(Settings.defBool(Settings.START_USB));
        assertFalse(Settings.defBool(Settings.AGC));
        assertEquals(12, Settings.PER_PAGE_OPTS[Settings.defInt(Settings.PER_PAGE)]);
        assertEquals(3, Settings.defInt(Settings.PAGES) + 1);
        assertEquals(50, Settings.PERCENT_25_50_75[Settings.defInt(Settings.DUCK)]);
        assertEquals(100, Settings.VOLUME_OPTS[Settings.defInt(Settings.VOLUME)]);
        assertEquals(0, Settings.defInt(Settings.THEME));
    }
}
