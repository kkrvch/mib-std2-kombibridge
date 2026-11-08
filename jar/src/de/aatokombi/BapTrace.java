package de.aatokombi;

/**
 * Diagnostic: one log line per request the cluster sends to a shadowed BAP function (navsd
 * MapViewAndOrientation, MapPresentation, MapColorAndType, RepeatLastNavAnnouncement), with the
 * request's public fields. Written to find out whether holding OK on the steering wheel sends the
 * head unit anything (car, 2026-10-30: no ActiveRgType / MapScale request on OK). Only at
 * Config.LOG_LEVEL DEBUG or TRACE. Java 1.3 source; never throws.
 */
public final class BapTrace {
    private BapTrace() {}

    /** Primitives and strings as they are; other objects (nested BAP structs) as {field=value ...}. */
    private static void value(StringBuffer sb, Object v, int depth) {
        if (v == null || depth > 2 || v instanceof String || v instanceof Number || v instanceof Boolean
                || v instanceof Character || v.getClass().isArray()) {
            sb.append(v);
            return;
        }
        java.lang.reflect.Field[] f = v.getClass().getFields();
        sb.append('{');
        for (int i = 0; i < f.length; i++) {
            if (java.lang.reflect.Modifier.isStatic(f[i].getModifiers())) continue;
            sb.append(f[i].getName()).append('=');
            try { value(sb, f[i].get(v), depth + 1); } catch (Throwable t) { sb.append('?'); }
            sb.append(' ');
        }
        sb.append('}');
    }

    public static void log(String function, String request, Object entity) {
        if (Config.LOG_LEVEL > de.adi961.miblogger.MIBLogger.DEBUG) {
            return;
        }
        try {
            StringBuffer sb = new StringBuffer("BAP ");
            sb.append(function).append('.').append(request);
            if (entity != null) {
                java.lang.reflect.Field[] f = entity.getClass().getFields();
                for (int i = 0; i < f.length; i++) {
                    if (java.lang.reflect.Modifier.isStatic(f[i].getModifiers())) continue;
                    sb.append(' ').append(f[i].getName()).append('=');
                    try { value(sb, f[i].get(entity), 0); } catch (Throwable t) { sb.append('?'); }
                }
            }
            sb.append(" aaSession=").append(ClusterVideo.session());
            ClusterVideo.log(sb.toString());
        } catch (Throwable t) {
            // never disturb navsd
        }
    }
}
