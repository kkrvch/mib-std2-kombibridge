package de.aatokombi;

/**
 * The few words the mod writes onto the cluster itself (the media widget's manoeuvre line, the
 * rerouting notice, units), in the language the head unit last set on the instrument cluster.
 *
 * Adding a language = adding ONE row to {@link #TABLE}: the language code first ("de", "fr", ...;
 * matched against the first two letters of the unit's "ru_RU"-style code), then the texts in the
 * order of the key constants below. A row that is too short, or a language with no row, falls back
 * to English for whatever is missing. Keep the texts short: the widget line is narrow.
 *
 * Java 1.3: no generics, no varargs. Must never throw into the HMI.
 */
public final class Strings {

    // ---- keys (indices into a row, after the language code) ----------------------------------
    public static final int START       = 0;
    public static final int CONTINUE    = 1;
    public static final int BEAR_LEFT   = 2;
    public static final int BEAR_RIGHT  = 3;
    public static final int TURN_LEFT   = 4;
    public static final int TURN_RIGHT  = 5;
    public static final int TURN        = 6;
    public static final int SHARP_LEFT  = 7;
    public static final int SHARP_RIGHT = 8;
    public static final int U_TURN      = 9;
    public static final int ON_RAMP     = 10;
    public static final int EXIT        = 11;
    public static final int FORK        = 12;
    public static final int MERGE       = 13;
    public static final int ROUNDABOUT  = 14;
    public static final int STRAIGHT    = 15;
    public static final int FERRY       = 16;
    public static final int ARRIVE      = 17;
    public static final int REROUTING   = 18;
    public static final int RING_EXIT   = 19;   // "exit 3" on a roundabout: the word before the number
    public static final int UNIT_M      = 20;
    public static final int UNIT_KM     = 21;
    public static final int UNIT_H      = 22;
    public static final int UNIT_MIN    = 23;
    public static final int DECIMAL     = 24;   // the decimal separator of "1.2 km"
    static final int COUNT = 25;

    // ---- the table: { language, START, CONTINUE, ... } in key order --------------------------
    private static final String[][] TABLE = {
        { "en",
          "Start", "Continue", "Bear left", "Bear right", "Turn left", "Turn right", "Turn",
          "Sharp left", "Sharp right", "U-turn", "On-ramp", "Exit", "Fork", "Merge",
          "Roundabout", "Straight", "Ferry", "Arrive", "Rerouting...", "exit",
          "m", "km", "h", "min", "." },
        { "ru",
          "Старт", "Прямо", "Плавно налево", "Плавно направо", "Налево", "Направо", "Поворот",
          "Резко налево", "Резко направо", "Разворот", "Въезд", "Съезд", "Развилка", "Слияние",
          "Кольцо", "Прямо", "Паром", "Финиш", "Перестроение...", "съезд",
          "м", "км", "ч", "мин", "," },
    };

    /** Test/override hook: a non-null value wins over the unit's language. */
    public static volatile String overrideLanguage = null;

    private static java.lang.reflect.Field langField = null;
    private static boolean langFieldTried = false;

    private Strings() {
    }

    /** The text for a key in the current language, English when there is none. */
    public static String get(int key) {
        String[] row = rowFor(language());
        String s = textIn(row, key);
        return (s != null) ? s : textIn(TABLE[0], key);
    }

    /** The text for {@code key} in {@code row}, or null when the row is too short or the text empty. */
    private static String textIn(String[] row, int key) {
        if (row == null || key < 0 || key + 1 >= row.length) {
            return null;
        }
        String s = row[key + 1];
        return (s != null && s.length() > 0) ? s : null;
    }

    /** The table row for a two-letter language code, or null when there is none. */
    private static String[] rowFor(String lang) {
        if (lang != null) {
            for (int i = 0; i < TABLE.length; i++) {
                if (TABLE[i][0].equals(lang)) {
                    return TABLE[i];
                }
            }
        }
        return TABLE[0];
    }

    /**
     * Two-letter language of the instrument cluster, e.g. "ru"; "en" when it cannot be told. Read
     * from the stock language/skin controller (LanguageHelper.lastRequestedLanguageForInstrumentCluster,
     * an ISO code like "ru_RU"), by reflection so that a firmware without it, or a host test, simply
     * gets English; then the JVM default locale as a second guess.
     */
    public static String language() {
        String o = overrideLanguage;
        if (o != null) {
            return o;
        }
        String code = null;
        try {
            if (!langFieldTried) {
                langFieldTried = true;
                Class c = Class.forName("de.vw.mib.asl.framework.internal.lsc.LanguageHelper");
                langField = c.getField("lastRequestedLanguageForInstrumentCluster");
            }
            if (langField != null) {
                Object v = langField.get(null);
                if (v instanceof String) {
                    code = (String) v;
                }
            }
        } catch (Throwable t) {
            langField = null;
        }
        if (code == null || code.length() < 2) {
            try {
                code = java.util.Locale.getDefault().getLanguage();
            } catch (Throwable t) {
                code = null;
            }
        }
        if (code == null || code.length() < 2) {
            return "en";
        }
        return code.substring(0, 2).toLowerCase();
    }
}
