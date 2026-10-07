package org.omri.radio.impl;

import org.omri.radioservice.RadioServiceDabComponent;
import org.omri.radioservice.RadioServiceDabUserApplication;
import org.omri.radioservice.metadata.TermId;
import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusItem;
import org.omri.radioservice.metadata.VisualDabSlideShow;

/**
 * Objects libirtdab constructs through JNI. Only the fields Welle uses are kept; the other
 * setters must still exist because the native side resolves every method id up front.
 */
public final class RadioServiceDabComponentImpl implements RadioServiceDabComponent {
    public int bitrate;
    public int subchannelId;
    public boolean primary;
    /** Transport mode: 0 = MSC stream audio. */
    public int tmId = -1;
    /** ASCTy for audio components: 0 = DAB (MP2), 63 = DAB+ (AAC). */
    public int type = -1;
    public int scIdS;

    public RadioServiceDabComponentImpl() { }

    void setScBitrate(int v) { bitrate = v; }
    void setIsScCaFlagSet(boolean v) { }
    void setServiceId(int v) { }
    void setSubchannelId(int v) { subchannelId = v; }
    void setScLabel(String v) { }
    void setPacketAddress(int v) { }
    void setIsScPrimary(boolean v) { primary = v; }
    void setServiceComponentIdWithinService(int v) { scIdS = v; }
    void setTmId(int v) { tmId = v; }
    void setServiceComponentType(int v) { type = v; }
    void setDatagroupTransportUsed(boolean v) { }
    void setMscStartAddress(int v) { }
    void setSubchannelSize(int v) { }
    void setProtectionLevel(int v) { }
    void setProtectionType(int v) { }
    void setUepTableIndex(int v) { }
    void setIsFecSchemeApplied(boolean v) { }
    void addScUserApplication(RadioServiceDabUserApplication v) { }
}

final class RadioServiceDabUserApplicationImpl implements RadioServiceDabUserApplication {
    RadioServiceDabUserApplicationImpl() { }

    void setUserApplicationType(int v) { }
    void setIsCaProtected(boolean v) { }
    void setCaOrganization(int v) { }
    void setIsXpadApptype(boolean v) { }
    void setXpadApptype(int v) { }
    void setIsDatagroupsUsed(boolean v) { }
    void setDSCTy(int v) { }
    void setUappdata(byte[] v) { }
}

final class TermIdImpl implements TermId {
    TermIdImpl() { }

    void setGenreHref(String v) { }
    void setTermId(String v) { }
    void setGenreText(String v) { }
}

final class TextualDabDynamicLabelImpl implements Textual {
    String text = "";

    TextualDabDynamicLabelImpl() { }

    void setText(String v) { text = v == null ? "" : v.trim(); }
    void setTextBytes(byte[] v, int charset) { }
    void setItemRunning(boolean v) { }
    void setItemToggled(boolean v) { }
    void addDlPlusItem(TextualDabDynamicLabelPlusItem v) { }
}

final class TextualDabDynamicLabelPlusItemImpl implements TextualDabDynamicLabelPlusItem {
    TextualDabDynamicLabelPlusItemImpl() { }

    void setDlPlusContentType(int v) { }
    void setDlPlusContentText(byte[] v, int charset) { }
}

final class VisualDabSlideShowImpl implements VisualDabSlideShow {
    byte[] data;

    VisualDabSlideShowImpl() { }

    void setContentName(String v) { }
    void setVisualData(byte[] v) { data = v; }
    void setVisualMimeType(int v) { }
    void setContentType(int v) { }
    void setContentSubType(int v) { }
    void setAlternativeLocationURL(String v) { }
    void setSlideId(int v) { }
    void setCategoryText(String v) { }
    void setCategoryId(int v) { }
    void setCategoryClickThroughLink(String v) { }
}
