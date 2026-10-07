package org.omri.radio.impl;

import org.omri.radioservice.RadioServiceDab;
import org.omri.radioservice.RadioServiceDabComponent;
import org.omri.radioservice.metadata.TermId;
import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.VisualDabSlideShow;

import java.util.ArrayList;

/**
 * One DAB service as libirtdab reports it during a scan, and the handle it needs to start
 * playback. Setter/getter/callback names and signatures are fixed by jtunerusbdevice.cpp
 * and jdabservice.cpp.
 */
public final class RadioServiceDabImpl implements RadioServiceDab {

    /** ASCTy of DAB+ (HE-AAC) audio; 0 is DAB MPEG-1/2 Layer II. */
    public static final int ASCTY_DAB_PLUS = 63;

    /** Receives the running service's payload on native threads. */
    public interface Sink {
        void onAudioFormat(int ascty, int channels, int sampleRate, boolean sbr, boolean ps);
        void onAudioData(byte[] data);
        void onDynamicLabel(String text);
        void onSlide(byte[] image);
    }

    public volatile Sink sink;

    public int ecc;
    public int ensembleId;
    public int serviceId;
    /** Hz, as the native tuner expects it back. */
    public int ensembleFrequency;
    public String ensembleLabel = "";
    public String serviceLabel = "";
    public String shortLabel = "";
    public boolean caProtected;
    public boolean programmeService;
    public final ArrayList<RadioServiceDabComponentImpl> components = new ArrayList<RadioServiceDabComponentImpl>();

    public RadioServiceDabImpl() {
    }

    /** The primary audio component, or null for data-only services. */
    public RadioServiceDabComponentImpl primaryAudio() {
        for (RadioServiceDabComponentImpl c : components) {
            if (c.primary && c.tmId == 0) return c;
        }
        return null;
    }

    // ---- called from native: scan results -------------------------------------------------

    void setEnsembleEcc(int v) { ecc = v; }
    void setEnsembleId(int v) { ensembleId = v; }
    void setEnsembleLabel(String v) { ensembleLabel = v == null ? "" : v.trim(); }
    void setEnsembleShortLabel(String v) { }
    void setIsCaProtected(boolean v) { caProtected = v; }
    void setCaId(int v) { }
    void setEnsembleFrequency(int v) { ensembleFrequency = v; }
    void setServiceLabel(String v) { serviceLabel = v == null ? "" : v.trim(); }
    void setShortLabel(String v) { shortLabel = v == null ? "" : v.trim(); }
    void setServiceId(int v) { serviceId = v; }
    void setIsProgrammeService(boolean v) { programmeService = v; }
    void addServiceComponent(RadioServiceDabComponent c) { components.add((RadioServiceDabComponentImpl) c); }
    void addGenre(TermId t) { }

    // ---- called from native: service start ------------------------------------------------

    int getEnsembleFrequency() { return ensembleFrequency; }
    int getEnsembleEcc() { return ecc; }
    int getEnsembleId() { return ensembleId; }
    int getServiceId() { return serviceId; }

    // ---- called from native: running service ----------------------------------------------

    void audioFormatChanged(int ascty, int channels, int sampleRate, boolean sbr, boolean ps) {
        Sink s = sink;
        if (s != null) s.onAudioFormat(ascty, channels, sampleRate, sbr, ps);
    }

    void audioData(byte[] data, int channels, int sampleRate) {
        Sink s = sink;
        if (s != null) s.onAudioData(data);
    }

    /** The misspelling is upstream's; jdabservice.cpp looks the method up under this name. */
    void labeReceived(Textual label) {
        Sink s = sink;
        if (s != null) s.onDynamicLabel(((TextualDabDynamicLabelImpl) label).text);
    }

    void slideshowReceived(VisualDabSlideShow slide) {
        Sink s = sink;
        byte[] data = ((VisualDabSlideShowImpl) slide).data;
        if (s != null && data != null) s.onSlide(data);
    }
}
