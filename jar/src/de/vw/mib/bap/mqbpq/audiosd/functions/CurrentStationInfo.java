/*
 * PQ (mqbpq) audiosd media shadow. Stock class + one delta: while AA is connected
 * (getSmarphoneIntegrationConnectionType()==3) setBapCurrentStationInfoInfoTextsForMirrorLink()
 * shows live AA nav / now-playing in the 3 info lines instead of the "Android Auto" label.
 * Data comes from the MQB shadow's static holders (the shared readers write them; the MQB
 * shadow's pokeNav() fans out to poke() here). Fully guarded → stock label on any error.
 */
package de.vw.mib.bap.mqbpq.audiosd.functions;

import de.aatokombi.Config;
import de.adi961.miblogger.MIBLogger;
import de.vw.mib.asl.internal.androidauto.target.NavigationHandler;
import de.vw.mib.asl.api.bap.timer.Timer;
import de.vw.mib.asl.api.bap.timer.TimerNotifier;
import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunction;
import de.vw.mib.bap.functions.BAPFunctionController;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbpq.audiosd.api.stages.CurrentStationInfoStage;
import de.vw.mib.bap.mqbpq.common.api.adapter.LanguageUtil;
import de.vw.mib.bap.mqbpq.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbpq.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbpq.common.arrays.BAPArrayList;
import de.vw.mib.bap.mqbpq.generated.audiosd.serializer.CurrentStationInfo_Status;
import de.vw.mib.collections.ObjectObjectOptHashMap;
import java.util.ArrayList;

public class CurrentStationInfo
extends CurrentStationInfoStage
implements TimerNotifier {
    private BAPStageInitializer _stageInitializer;
    private LanguageUtil _languageUtil;
    private BAPArrayList _receptionList;
    private int _lastHandle;
    private ObjectObjectOptHashMap _languageMap;
    private int lastAudioComponent;
    private static final int TIMER_UPDATE_TIME = 0;
    private Timer _updateTimer;
    private static final int TIMER_ACTION_NOTHING = 0;
    private static final int TIMER_ACTION_UPDATE_DATA = 0;
    private static final int TIMER_ACTION_STOP_IGNORE_ENTERTAINMENT_SUPRESSION = 0;
    private static final int TIMER_UPDATE_SUPPRESSION_TIME = 0;
    private static final int TIMER_UPDATE_SUPPRESSION_INSTANCE_ID = 0;
    private Timer _suppressionTimer;
    private static final int LANG_NAME_TRACK = 0;
    private static final int LANG_NAME_CHAPTER = 0;
    private static final int LANG_NAME_FILE = 0;
    private static final int LANG_NAME_FOLDER = 0;
    private static final int LANG_NAME_BLUETOOTH = 0;
    private static final int LANG_NAME_AUDIO = 0;
    private static final int LANG_NUMBER_OF_ELEMENTS = 0;
    private static final String MIRROR_LINK_NAME = "";
    private static final String APPLE_CAR_PLAY_NAME = "";
    private static final String GOOGLE_GAL_NAME = "";
    private static final String BAIDU_CARLIFE_NAME = "";
    private static final String I18N_FILTERCRITERIA_UNKNOWN_ARTIST_STRING = "";
    private static final String I18N_FILTERCRITERIA_UNKNOWN_ALBUM_STRING = "";
    private static final String I18N_FILTERCRITERIA_NOW_PLAYING_FOLDER_STRING = "";
    private static final String UPDATE_TEXT = "";

    // ===== AAtoKombi injection ==============================================================
    private static final int PQ_LINE_MAX = 48;   // one under the 49-char infotext BAPString capacity
    private static volatile CurrentStationInfo INSTANCE = null;

    /** True once instantiated → this unit runs the mqbpq (PQ) stack. */
    public static boolean isActive() { return INSTANCE != null; }

    /** Refresh the widget. Called by the MQB shadow's pokeNav() fan-out. Never throws. */
    public static void poke() {
        CurrentStationInfo i = INSTANCE;
        if (i != null) {
            try { i.process(-1); } catch (Throwable t) {}
        }
    }

    /** Clamp injected text so an over-long line can't overflow the infotext BAPString. Null -> "". */
    private static String clampLine(String s) {
        if (s == null) return "";
        return s.length() > PQ_LINE_MAX ? s.substring(0, PQ_LINE_MAX) : s;
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        super.init(bAPStageInitializer);
        this.setStageInitializer(bAPStageInitializer);
        INSTANCE = this;
        CurrentStationInfo_Status currentStationInfo_Status = this.dequeueBAPEntity();
        this.setBapCurrentStationInfoData(currentStationInfo_Status);
        return currentStationInfo_Status;
    }

    private BAPStageInitializer getStageInitializer() {
        return this._stageInitializer;
    }

    private void setStageInitializer(BAPStageInitializer bAPStageInitializer) {
        this._stageInitializer = bAPStageInitializer;
    }

    private Timer getUpdateTimer() {
        if (this._updateTimer == null) {
            this._updateTimer = this.getStageInitializer().createTimer((BAPStage)this, (TimerNotifier)this, (long)0, 0);
        }
        return this._updateTimer;
    }

    protected final LanguageUtil getLanguageUtil() {
        if (this._languageUtil == null) {
            this._languageUtil = this.getStageInitializer().createLanguageUtil((BAPStage)this);
        }
        return this._languageUtil;
    }

    private Timer getSuppressionTimer() {
        if (this._suppressionTimer == null) {
            this._suppressionTimer = this.getStageInitializer().createTimer((BAPStage)this, (TimerNotifier)this, (long)0, 1);
        }
        return this._suppressionTimer;
    }

    protected BAPArrayList getReceptionList() {
        return this._receptionList;
    }

    protected void setReceptionList(BAPArrayList bAPArrayList) {
        this._receptionList = bAPArrayList;
        this.process(-1);
    }

    private int getLastHandle() {
        return this._lastHandle;
    }

    private void setLastHandle(int n) {
        this._lastHandle = n;
    }

    private ArrayList getLanguageMap() {
        ArrayList arrayList;
        if (this._languageMap == null) {
            this._languageMap = new ObjectObjectOptHashMap();
            arrayList = new ArrayList(6);
            arrayList.add(0, "Titel");
            arrayList.add(1, "Kapitel");
            arrayList.add(2, "Datei");
            arrayList.add(3, "Ordner");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"de_DE", (Object)arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Track");
            arrayList.add(1, "Chapter");
            arrayList.add(2, "File");
            arrayList.add(3, "Folder");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"en_US", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Track");
            arrayList.add(1, "Chapter");
            arrayList.add(2, "File");
            arrayList.add(3, "Folder");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"en_GB", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Titre");
            arrayList.add(1, "Chapitre");
            arrayList.add(2, "Fichier");
            arrayList.add(3, "Dossier");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"fr_FR", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Brano");
            arrayList.add(1, "Capitolo");
            arrayList.add(2, "File");
            arrayList.add(3, "Cartella");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"it_IT", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "T\u00edtulo");
            arrayList.add(1, "Cap\u00edtulo");
            arrayList.add(2, "Archivo");
            arrayList.add(3, "Carpeta");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"es_ES", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "T\u00edtulo");
            arrayList.add(1, "Cap\u00edtulo");
            arrayList.add(2, "Ficheiro");
            arrayList.add(3, "Diret\u00f3rio");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "\u00c1udio");
            this._languageMap.put((Object)"pt_PT", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Titul");
            arrayList.add(1, "Kapitola");
            arrayList.add(2, "Soubor");
            arrayList.add(3, "Slo\u017eka");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"cs_CZ", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "\u66f2\u76ee");
            arrayList.add(1, "\u7ae0\u8282");
            arrayList.add(2, "\u6587\u4ef6");
            arrayList.add(3, "\u6587\u4ef6\u5939");
            arrayList.add(4, "\u84dd\u7259");
            arrayList.add(5, "\u97f3\u9891");
            this._languageMap.put((Object)"zh_CN", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "\u0422\u0440\u0435\u043a");
            arrayList.add(1, "\u0427\u0430\u0441\u0442\u044c");
            arrayList.add(2, "\u0424\u0430\u0439\u043b");
            arrayList.add(3, "\u041f\u0430\u043f\u043a\u0430");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "\u0417\u0432\u0443\u043a");
            this._languageMap.put((Object)"ru_RU", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Par\u00e7a");
            arrayList.add(1, "B\u00f6l\u00fcm");
            arrayList.add(2, "Dosya");
            arrayList.add(3, "Klas\u00f6r");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Ses");
            this._languageMap.put((Object)"tr_TR", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Titel");
            arrayList.add(1, "Hoofdstuk");
            arrayList.add(2, "Bestand");
            arrayList.add(3, "Map");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"nl_NL", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "L\u00e5t");
            arrayList.add(1, "Kapitel");
            arrayList.add(2, "Fil");
            arrayList.add(3, "Mapp");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Ljud");
            this._languageMap.put((Object)"sv_SE", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Spor");
            arrayList.add(1, "Kapittel");
            arrayList.add(2, "Fil");
            arrayList.add(3, "Mappe");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"no_NO", arrayList);
            arrayList = new ArrayList(6);
            arrayList.add(0, "Utw\u00f3r");
            arrayList.add(1, "Rozdzia\u0142");
            arrayList.add(2, "Plik");
            arrayList.add(3, "Folder");
            arrayList.add(4, "Bluetooth");
            arrayList.add(5, "Audio");
            this._languageMap.put((Object)"pl_PL", arrayList);
        }
        if ((arrayList = (ArrayList)this._languageMap.get((Object)this.getInstrumentClusterIsLanguage())) == null) {
            arrayList = (ArrayList)this._languageMap.get((Object)"en_GB");
        }
        return arrayList;
    }

    private String getLanguageString(int n) {
        String string = (String)this.getLanguageMap().get(n);
        return string != null ? string : "";
    }

    private String getActivePlayingFolderName() {
        String string = this.getActivePlayedFolderName();
        return string.intern() == "filterCriteria.nowPlaying".intern() ? "" : string;
    }

    private String formatFrequency(int n, int n2) {
        String string = this.getFmFrequencyScale() == 1 ? this.getLanguageUtil().formatFrequencyNAR(n, n2, -1) : this.getLanguageUtil().formatFrequency(n, n2);
        return string;
    }

    private void setBapCurrentStationInfoNoInformationAvailable(CurrentStationInfo_Status currentStationInfo_Status) {
        currentStationInfo_Status.infotext_1.setEmptyString();
        currentStationInfo_Status.infotext_2.setEmptyString();
        currentStationInfo_Status.infotext_3.setEmptyString();
    }

    protected void setBapCurrentStationInfoInfoTextsForRadioAM(CurrentStationInfo_Status currentStationInfo_Status) {
        if (this.getAmRadioGetListState() == 1) {
            currentStationInfo_Status.infotext_1.setContent("UPDATING...");
            currentStationInfo_Status.infotext_2.setEmptyString();
            currentStationInfo_Status.infotext_3.setEmptyString();
        } else {
            String string;
            String string2;
            String string3 = this.getCurrentAmStationName();
            if (string3.length() != 0) {
                currentStationInfo_Status.infotext_1.setContent(string3);
            } else {
                currentStationInfo_Status.infotext_1.setContent(this.formatFrequency(this.getCurrentAMFrequency(), 1));
            }
            currentStationInfo_Status.infotext_2.setEmptyString();
            currentStationInfo_Status.infotext_3.setEmptyString();
            if (!this.getManualModeActive() && this.isRadioTextPlusEnabledAMFM()) {
                string2 = this.getCurrentAMRadioTextTitleName();
                string = this.getCurrentAMRadioTextArtistName();
            } else {
                string2 = "";
                string = "";
            }
            currentStationInfo_Status.infotext_2.setContent(string2);
            currentStationInfo_Status.infotext_3.setContent(string);
        }
    }

    protected void setBapCurrentStationInfoInfoTextsForRadioFM(CurrentStationInfo_Status currentStationInfo_Status) {
        String string;
        String string2;
        String string3 = this.getCurrentFmStationName();
        if (string3.length() != 0) {
            currentStationInfo_Status.infotext_1.setContent(string3);
        } else {
            currentStationInfo_Status.infotext_1.setContent(this.formatFrequency(this.getCurrentFmFrequency(), 0));
        }
        if (!this.getManualModeActive() && this.isRadioTextPlusEnabledAMFM()) {
            string2 = this.getCurrentFmRadioTextPlusTitleName();
            string = this.getCurrentFmRadioTextPlusArtistName();
        } else {
            string2 = "";
            string = "";
        }
        currentStationInfo_Status.infotext_2.setContent(string2);
        currentStationInfo_Status.infotext_3.setContent(string);
    }

    protected final boolean isRadioTextPlusEnabledAMFM() {
        return this.getRadioTextSetupState() && this.getRadioTextPlusSetupState() && this.getRatioTextPlusActive() && this.getRdsSetupOptionState();
    }

    protected void setBapCurrentStationInfoInfoTextsForRadioDAB(CurrentStationInfo_Status currentStationInfo_Status) {
        if (this.getDabRadioListState() == 1) {
            currentStationInfo_Status.infotext_1.setContent("UPDATING...");
            currentStationInfo_Status.infotext_2.setEmptyString();
            currentStationInfo_Status.infotext_3.setEmptyString();
        } else {
            String string;
            boolean bl = false;
            int n = this.getDabServiceState();
            int n2 = this.getDabEnsembleState();
            int n3 = this.getDabAdditionalServiceState();
            if (this.getDabEnsembleState() == 1) {
                string = this.getCurrentDABFrequencyLabel();
                bl = true;
            } else if (n2 == 2) {
                string = this.getCurrentDABFrequencyLabel();
                bl = true;
            } else {
                string = n == 1 ? this.getCurrentDabServiceShortName() : (n3 == 2 ? this.getCurrentDabSecondaryServiceShortName() : this.getCurrentDabServiceShortName());
            }
            String string2 = this.getCurrentDABEnsembleName();
            if (string2.length() != 0) {
                if (this.getDabServiceState() == 3) {
                    currentStationInfo_Status.infotext_1.setContent("(FM)");
                } else {
                    currentStationInfo_Status.infotext_1.setContent(string2);
                }
            } else {
                currentStationInfo_Status.infotext_1.setContent(this.getCurrentDABFrequencyLabel());
            }
            if (bl) {
                currentStationInfo_Status.infotext_2.setEmptyString();
            } else {
                currentStationInfo_Status.infotext_2.setContent(string);
            }
            currentStationInfo_Status.infotext_3.setEmptyString();
        }
    }

    private void setBapCurrentStationInfoInfoTextsForRadioSirius(CurrentStationInfo_Status currentStationInfo_Status) {
        currentStationInfo_Status.infotext_1.setContent(this.getCurrentSatChannelShortName());
        currentStationInfo_Status.infotext_2.setContent(this.getCurrentSatTitleName());
        currentStationInfo_Status.infotext_3.setContent(this.getCurrentSatArtistName());
    }

    private void setBapCurrentStationInfoInfoTextsForRadio(CurrentStationInfo_Status currentStationInfo_Status) {
        switch (this.getCurrentStationInfoBand()) {
            case 0: {
                this.setBapCurrentStationInfoInfoTextsForRadioAM(currentStationInfo_Status);
                break;
            }
            case 1: {
                this.setBapCurrentStationInfoInfoTextsForRadioFM(currentStationInfo_Status);
                break;
            }
            case 2: {
                this.setBapCurrentStationInfoInfoTextsForRadioDAB(currentStationInfo_Status);
                break;
            }
            case 3: {
                this.setBapCurrentStationInfoInfoTextsForRadioSirius(currentStationInfo_Status);
                break;
            }
            default: {
                this.setBapCurrentStationInfoNoInformationAvailable(currentStationInfo_Status);
            }
        }
    }

    private void setBapCurrentStationInfoInfoTextsForMirrorLink(CurrentStationInfo_Status currentStationInfo_Status) {
        int connType = this.getSmarphoneIntegrationConnectionType();
        String string;
        switch (connType) {
            case 1: {
                string = "MirrorLink\u2122";
                break;
            }
            case 2: {
                string = "Apple CarPlay";
                break;
            }
            case 3: {
                string = "Android Auto";
                break;
            }
            case 4: {
                string = "Baidu CarLife";
                break;
            }
            default: {
                string = "";
            }
        }
        // Diagnostic probe (Config.PROBE_ENABLED): with AA connected, fill the 3 lines with the
        // markers L1/L2/L3 so the physical top-to-bottom order and relative sizing can be read off
        // a real PQ cluster (the head unit fixes only the wire order infotext_1/2/3; placement is a
        // cluster-side decision). MUST be off for normal builds.
        if (Config.PROBE_ENABLED && connType == 3) {
            currentStationInfo_Status.infotext_1.setContent("L1");
            currentStationInfo_Status.infotext_2.setContent("L2");
            currentStationInfo_Status.infotext_3.setContent("L3");
            return;
        }
        // While AA (connType==3) is connected, show live nav or the now-playing track in the 3
        // lines instead of the "Android Auto" label. Everything routes through the media widget,
        // so nav-capability is not consulted (nav-in-media always allowed).
        try {
            String navPrimary = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.navPrimary;
            String navSecondary = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.navSecondary;
            String navTertiary = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.navTertiary;
            String navQuaternary = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.navQuaternary;
            String mediaTitle = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.mediaTitle;
            String mediaArtist = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.mediaArtist;
            String mediaAlbum = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.mediaAlbum;
            int mediaPosSec = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.mediaPosSec;
            int mediaDurSec = de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo.mediaDurSec;

            if (Config.SHOW_NAV
                    && connType == 3
                    && NavigationHandler.aaRouteGuidanceActive
                    && navPrimary != null && navPrimary.length() > 0) {
                // 3 lines: arrow+distance / street / maneuver word (+roundabout exit #).
                String line3 = navTertiary != null ? navTertiary : "";
                if (navQuaternary != null && navQuaternary.length() > 0) {
                    line3 = (line3.length() > 0) ? (line3 + " " + navQuaternary) : navQuaternary;
                }
                currentStationInfo_Status.infotext_1.setContent(clampLine(navPrimary));
                currentStationInfo_Status.infotext_2.setContent(clampLine(navSecondary));
                currentStationInfo_Status.infotext_3.setContent(clampLine(line3));
                return;
            }
            // otherwise (AA connected, no active route guidance) show the now-playing track, using
            // the stock media field assignment so it reads like a native track: infotext_1=artist,
            // infotext_2=title, infotext_3=album (or a progress bar in place of album when
            // SHOW_MEDIA_PROGRESS is on \u2014 PQ has no 4th line for it).
            if (Config.SHOW_MEDIA && connType == 3 && mediaTitle != null && mediaTitle.length() > 0) {
                String bar = Config.SHOW_MEDIA_PROGRESS ? progressBar(mediaPosSec, mediaDurSec) : "";
                String line3 = (bar.length() > 0) ? bar : (mediaAlbum != null ? mediaAlbum : "");
                currentStationInfo_Status.infotext_1.setContent(clampLine(mediaArtist));
                currentStationInfo_Status.infotext_2.setContent(clampLine(mediaTitle));
                currentStationInfo_Status.infotext_3.setContent(clampLine(line3));
                return;
            }
        } catch (Throwable t) {
        }
        currentStationInfo_Status.infotext_1.setContent(clampLine(string));
        currentStationInfo_Status.infotext_2.setEmptyString();
        currentStationInfo_Status.infotext_3.setEmptyString();
    }

    // ASCII progress for the 3rd line, e.g. "1:23 ----|----- 3:45". "" when duration unknown.
    private static final int BAR_WIDTH = 12;
    private static String progressBar(int posSec, int durSec) {
        if (durSec <= 0) return "";
        if (posSec < 0) posSec = 0;
        if (posSec > durSec) posSec = durSec;
        int pos = (int) ((long) posSec * (BAR_WIDTH - 1) / durSec);
        if (pos < 0) pos = 0; else if (pos > BAR_WIDTH - 1) pos = BAR_WIDTH - 1;
        StringBuffer b = new StringBuffer();
        b.append(fmtMMSS(posSec)).append(' ');
        for (int i = 0; i < BAR_WIDTH; i++) b.append(i == pos ? '|' : '-');
        b.append(' ').append(fmtMMSS(durSec));
        return b.toString();
    }

    // Seconds -> "m:ss".
    private static String fmtMMSS(int sec) {
        int m = sec / 60;
        int s = sec % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private boolean setArtistAndAlbum(CurrentStationInfo_Status currentStationInfo_Status) {
        boolean bl;
        String string = this.getCurrentArtist();
        String string2 = this.getCurrentAlbum();
        if (string2.intern() == "filterCriteria.unknownAlbum".intern()) {
            currentStationInfo_Status.infotext_3.setEmptyString();
            bl = false;
        } else {
            currentStationInfo_Status.infotext_3.setContent(string2);
            bl = true;
        }
        if (string.intern() == "filterCriteria.unknownArtist".intern()) {
            currentStationInfo_Status.infotext_1.setEmptyString();
            bl = false;
        } else {
            currentStationInfo_Status.infotext_1.setContent(string);
            bl = true;
        }
        return bl;
    }

    protected void setSimpleTitleWithoutId3Information(CurrentStationInfo_Status currentStationInfo_Status) {
        int n = this.getCurrentTrackNumber();
        currentStationInfo_Status.infotext_1.setContent(n != 0 ? new StringBuffer().append(this.getLanguageString(0)).append(" ").append(n).toString() : "");
        currentStationInfo_Status.infotext_2.setEmptyString();
        currentStationInfo_Status.infotext_3.setEmptyString();
    }

    protected void setBapCurrentStationInfoInfoTextsForMediaDigitalAudioCD(CurrentStationInfo_Status currentStationInfo_Status) {
        boolean bl;
        String string = this.getCurrentTitle();
        boolean bl2 = bl = string != null && string.trim().length() != 0;
        if (bl) {
            currentStationInfo_Status.infotext_2.setContent(string);
            this.setArtistAndAlbum(currentStationInfo_Status);
        } else {
            this.setSimpleTitleWithoutId3Information(currentStationInfo_Status);
        }
    }

    protected void setBapCurrentStationInfoInfoTextsForExternalDevice(CurrentStationInfo_Status currentStationInfo_Status) {
        String string = this.getCurrentTitle();
        boolean bl = string.trim().length() != 0;
        int n = this.getCurrentTrackNumber();
        if (bl || n == 0) {
            currentStationInfo_Status.infotext_2.setContent(string);
            this.setArtistAndAlbum(currentStationInfo_Status);
        } else {
            this.setSimpleTitleWithoutId3Information(currentStationInfo_Status);
            currentStationInfo_Status.infotext_2.setContent(this.getActivePlayingFolderName());
        }
    }

    private void setBapCurrentStationInfoInfoTextsForMedia(CurrentStationInfo_Status currentStationInfo_Status) {
        block0 : switch (this.getCurrentAudioSource()) {
            case 1: 
            case 4: {
                switch (this.getCDContentType()) {
                    case 3: 
                    case 4: {
                        int n = this.getDvdChapter();
                        int n2 = this.getDvdChapterCount();
                        currentStationInfo_Status.infotext_1.setContent(new StringBuffer().append(this.getLanguageString(1)).append(" ").append(n).append("/").append(n2).toString());
                        currentStationInfo_Status.infotext_2.setEmptyString();
                        currentStationInfo_Status.infotext_3.setEmptyString();
                        break block0;
                    }
                    case 1: {
                        this.setBapCurrentStationInfoInfoTextsForMediaDigitalAudioCD(currentStationInfo_Status);
                        break block0;
                    }
                    case 2: {
                        this.setBapCurrentStationInfoInfoTextsForExternalDevice(currentStationInfo_Status);
                        break block0;
                    }
                }
                this.setBapCurrentStationInfoNoInformationAvailable(currentStationInfo_Status);
                break;
            }
            case 7: 
            case 14: {
                this.setBapCurrentStationInfoInfoTextsForExternalDevice(currentStationInfo_Status);
                break;
            }
            case 6: {
                this.setBapCurrentStationInfoInfoTextsForExternalDevice(currentStationInfo_Status);
                if (this.getBluetoothAvrcpSupported() && (!currentStationInfo_Status.infotext_1.isEmptyString() || !currentStationInfo_Status.infotext_2.isEmptyString() || !currentStationInfo_Status.infotext_3.isEmptyString())) break;
                currentStationInfo_Status.infotext_1.setContent(this.getLanguageString(4));
                currentStationInfo_Status.infotext_2.setContent(this.getLanguageString(5));
                break;
            }
            case 2: 
            case 5: 
            case 8: 
            case 10: 
            case 11: 
            case 13: {
                this.setBapCurrentStationInfoInfoTextsForExternalDevice(currentStationInfo_Status);
                break;
            }
            case 3: 
            case 9: {
                this.setBapCurrentStationInfoNoInformationAvailable(currentStationInfo_Status);
                break;
            }
            default: {
                this.setBapCurrentStationInfoNoInformationAvailable(currentStationInfo_Status);
            }
        }
    }

    private void setBapCurrentStationInfoInfoTextsForTvTuner(CurrentStationInfo_Status currentStationInfo_Status) {
        currentStationInfo_Status.infotext_1.setContent(this.getCurrentTvTunerStationName());
        currentStationInfo_Status.infotext_2.setEmptyString();
        currentStationInfo_Status.infotext_3.setEmptyString();
    }

    private boolean setCurrentAudioComponent(CurrentStationInfo_Status currentStationInfo_Status) {
        boolean bl;
        int n = this.getCurrentAudioComponent();
        if (n != this.lastAudioComponent && n != 0) {
            this.lastAudioComponent = n;
            this.setBapCurrentStationInfoNoInformationAvailable(currentStationInfo_Status);
            bl = true;
        } else {
            if (n == 0) {
                if (!this.getSuppressionTimer().isRunning()) {
                    this.getSuppressionTimer().retrigger(2);
                }
            } else {
                this.lastAudioComponent = n;
            }
            bl = false;
        }
        return bl;
    }

    private void setBapCurrentStationInfoInfoTexts(CurrentStationInfo_Status currentStationInfo_Status) {
        switch (this.lastAudioComponent) {
            case 2: 
            case 3: {
                this.setBapCurrentStationInfoInfoTextsForMedia(currentStationInfo_Status);
                break;
            }
            case 5: {
                this.setBapCurrentStationInfoInfoTextsForTvTuner(currentStationInfo_Status);
                break;
            }
            case 1: {
                this.setBapCurrentStationInfoInfoTextsForRadio(currentStationInfo_Status);
                break;
            }
            case 6: {
                this.setBapCurrentStationInfoInfoTextsForMirrorLink(currentStationInfo_Status);
                break;
            }
            default: {
                this.setBapCurrentStationInfoNoInformationAvailable(currentStationInfo_Status);
            }
        }
    }

    private void setBapCurrentStationInfoSwitches(CurrentStationInfo_Status currentStationInfo_Status) {
        currentStationInfo_Status.stationInfoSwitches.taTpAvailable = this.getTpState() == 0;
        currentStationInfo_Status.stationInfoSwitches.tmcAvailable = this.getRdsSetupOptionState() && this.getTmcSignalAvailable();
    }

    protected int findBapPosIdInReceptionList() {
        return this.getReceptionList() != null ? this.getReceptionList().getBapPosID(this.getStationListActiveID()) : 0;
    }

    private int getBapCurrentStationInfoHandleForRadio() {
        int n;
        switch (this.getCurrentStationInfoBand()) {
            case 0: 
            case 1: 
            case 2: 
            case 3: {
                n = this.findBapPosIdInReceptionList();
                break;
            }
            default: {
                n = 0;
            }
        }
        return n;
    }

    private int getBapCurrentStationInfoHandle() {
        int n;
        switch (this.lastAudioComponent) {
            case 1: {
                n = this.getBapCurrentStationInfoHandleForRadio();
                break;
            }
            case 5: {
                n = this.findBapPosIdInReceptionList();
                break;
            }
            default: {
                n = 0;
            }
        }
        return n;
    }

    private void setBapCurrentStationInfoData(CurrentStationInfo_Status currentStationInfo_Status) {
        this.setBapCurrentStationInfoInfoTexts(currentStationInfo_Status);
        this.setBapCurrentStationInfoSwitches(currentStationInfo_Status);
        currentStationInfo_Status.fsgHandle = this.getBapCurrentStationInfoHandle();
    }

    public void initialize(boolean bl) {
    }

    public void uninitialize() {
        this.getLanguageUtil().uninitialize();
        this.getUpdateTimer().stop();
        this.getSuppressionTimer().stop();
    }

    public void process(int n) {
        if (!this.getUpdateTimer().isRunning()) {
            boolean bl;
            CurrentStationInfo_Status currentStationInfo_Status = this.dequeueBAPEntity();
            if (!this.setCurrentAudioComponent(currentStationInfo_Status)) {
                this.setBapCurrentStationInfoData(currentStationInfo_Status);
                bl = this.getDelegate().getPropertyListener((BAPFunctionController)this).statusProperty((BAPEntity)currentStationInfo_Status, (Property)this);
            } else {
                bl = this.getDelegate().getPropertyListener((BAPFunctionController)this).statusProperty((BAPEntity)currentStationInfo_Status, (Property)this);
            }
            if (currentStationInfo_Status.fsgHandle != this.getLastHandle()) {
                this.setLastHandle(currentStationInfo_Status.fsgHandle);
                if (bl) {
                    this.setCurrentStationHandleRequested(Boolean.TRUE);
                } else {
                    this.requestAcknowledge();
                }
            }
            if (bl) {
                this.getUpdateTimer().retrigger(1);
            }
        } else {
            int n2 = this.getBapCurrentStationInfoHandle();
            if (n2 != this.getLastHandle()) {
                this.setLastHandle(n2);
                this.setCurrentStationHandleRequested(Boolean.TRUE);
            }
            this.getUpdateTimer().setUserInfo(1);
        }
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, (BAPFunction)this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, (BAPFunction)this);
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, (BAPFunction)this);
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void requestAcknowledge() {
        this.setCurrentFsgHandle(new Integer(this.getLastHandle()));
    }

    public void errorAcknowledge() {
    }

    public void timerFired(int n) {
        switch (n) {
            case 1: 
            case 2: {
                this.process(-1);
                break;
            }
        }
    }
}
