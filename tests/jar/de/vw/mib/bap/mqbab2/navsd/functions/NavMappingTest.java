/*
 * Host test for the pure lookup tables in AANavReader — the AA enum -> VW cluster mappings. They are
 * plain static int math with no framework dependency, and they are exactly where a transcription
 * slip is invisible in review and expensive on the car.
 *
 * Same package as the class under test: the methods are package-private on purpose.
 * Run: tests/jar/run.sh   (not part of the shipped jar — the build compiles jar/src only)
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

public final class NavMappingTest {

    private static int failed = 0;

    private static void eq(String what, int got, int want) {
        if (got != want) {
            failed++;
            System.out.println("  FAIL " + what + ": got " + got + ", want " + want);
        }
    }

    private static void eq(String what, String got, String want) {
        if (!got.equals(want)) {
            failed++;
            System.out.println("  FAIL " + what + ": got '" + got + "', want '" + want + "'");
        }
    }

    private static void eqNull(String what, Object got) {
        if (got != null) {
            failed++;
            System.out.println("  FAIL " + what + ": got '" + got + "', want null");
        }
    }

    private static void notNull(String what, Object got) {
        if (got == null) {
            failed++;
            System.out.println("  FAIL " + what + ": got null, want non-null");
        }
    }

    // ---- lane shape -> VW arrow angle ---------------------------------------------------------
    private static void laneShapes() {
        eq("shape UNKNOWN",      AANavReader.laneShapeToDirection(0), 0);
        eq("shape STRAIGHT",     AANavReader.laneShapeToDirection(1), 0);
        eq("shape SLIGHT_LEFT",  AANavReader.laneShapeToDirection(2), 32);
        eq("shape SLIGHT_RIGHT", AANavReader.laneShapeToDirection(3), 224);
        eq("shape NORMAL_LEFT",  AANavReader.laneShapeToDirection(4), 64);
        eq("shape NORMAL_RIGHT", AANavReader.laneShapeToDirection(5), 192);
        eq("shape SHARP_LEFT",   AANavReader.laneShapeToDirection(6), 96);
        eq("shape SHARP_RIGHT",  AANavReader.laneShapeToDirection(7), 160);
        eq("shape U_TURN_LEFT",  AANavReader.laneShapeToDirection(8), 114);
        eq("shape U_TURN_RIGHT", AANavReader.laneShapeToDirection(9), 146);
        eq("shape out of range", AANavReader.laneShapeToDirection(42), 0);

        // Left and right of the same sharpness mirror around 0/256, or the row looks lopsided.
        // U-turns (8/9) are deliberately excluded: 114/146 come from the VW table and are NOT a
        // mirrored pair, which this invariant found the hard way.
        for (int s = 2; s <= 6; s += 2) {
            int left = AANavReader.laneShapeToDirection(s);
            int right = AANavReader.laneShapeToDirection(s + 1);
            eq("mirror of shape " + s, (left + right) & 0xFF, 0);
        }
        for (int s = 0; s <= 9; s++) {
            int d = AANavReader.laneShapeToDirection(s);
            if (d < 0 || d > 255) { failed++; System.out.println("  FAIL shape " + s + " out of byte range"); }
        }
    }

    // ---- shim lane line -> packed NavState.lanes -----------------------------------------------
    // The argument is what taggedLine() hands over: the line MINUS its "2 " marker. Feeding the
    // marker back in used to silently clear every lane row, which is invisible until you are driving.
    private static void lanePacking() {
        // a real frame's numbers, road name replaced: "2 3 8 3 3" -> NORMAL_LEFT plain, STRAIGHT best x2
        int[] p = AANavReader.packLanes("3 8 3 3");
        eq("lane count",       p[0], 3);
        eq("lane 0 direction", p[1], 64);   // shape 4 = NORMAL_LEFT
        eq("lane 0 guidance",  p[2], 0);
        eq("lane 1 direction", p[3], 0);    // shape 1 = STRAIGHT
        eq("lane 1 guidance",  p[4], 2);    // highlighted -> BEST_RECOMMENDATION
        eq("lane 2 guidance",  p[6], 2);

        eq("no payload",       AANavReader.packLanes(null)[0], 0);
        eq("empty payload",    AANavReader.packLanes("")[0], 0);
        eq("count 0",          AANavReader.packLanes("0")[0], 0);
        eq("garbage",          AANavReader.packLanes("x y z")[0], 0);
        eq("count over lanes present", AANavReader.packLanes("3 8")[0], 1);   // keep what arrived
        eq("over MAX_LANES",   AANavReader.packLanes("9 3 3 3 3 3 3 3 3 3")[0], NavState.MAX_LANES);

        // The pair as processOnce() wires it: whole file in, lane count out. This is the join that
        // was broken — each half was right, the marker was consumed twice.
        String[] file = {
            "89 1 12 2 0 0 92 13 1 1 2 Example Road / Second Ring",
            "2 3 8 3 3",
            "3 90 m"
        };
        eq("lanes off a whole file",
                AANavReader.packLanes(AANavReader.taggedLine(file, '2'))[0], 3);
        eq("distance off the same file", AANavReader.taggedLine(file, '3'), "90 m");
        eq("no lane line -> row cleared",
                AANavReader.packLanes(AANavReader.taggedLine(
                        new String[]{ file[0], "3 90 m", null }, '2'))[0], 0);

        // Current shim encoding: each physical lane carries every direction the phone supplied.
        // The primary remains the highlighted one so the established media cell stays one glyph.
        NavState.LaneState combined = AANavReader.packLaneState("v 2 2 2 9 1 7");
        eq("v2 lane count", combined.lanes[0], 2);
        eq("v2 combined primary", combined.lanes[1], 64);       // NORMAL_LEFT, highlighted
        eq("v2 combined guidance", combined.lanes[2], 2);
        eq("v2 combined directions", combined.directions[0].length, 2);
        eq("v2 combined second direction", combined.directions[0][1], 64); // NORMAL_LEFT
        eq("v2 plain primary", combined.lanes[3], 224);          // SLIGHT_RIGHT
        eq("v2 primary compatibility", AANavReader.packLanes("v 2 2 2 9 1 7")[0], 2);

        String[] v2file = { file[0], "2v 2 2 2 9 1 7", null };
        eq("v2 lane payload off a whole file", AANavReader.packLaneState(
                AANavReader.taggedLine(v2file, '2')).directions[0].length, 2);
    }

    // ---- packed lanes -> the media widget's text lane row ---------------------------------------
    // Built from code points rather than written out, so the expectations cannot turn on how this
    // file happens to be decoded at compile time. Same four glyphs AANavReader draws.
    private static final String L = String.valueOf((char) 0x25C4);   // left pointer
    private static final String R = String.valueOf((char) 0x25BA);   // right pointer
    private static final String U = String.valueOf((char) 0x25B2);   // up triangle (straight)
    private static final String D = String.valueOf((char) 0x25BC);   // down triangle (u-turn)

    private static void laneRow() {
        // packed = { count, dir0, guidance0, dir1, guidance1, ... }
        eq("exit right out of three",
                AANavReader.laneRow(new int[]{ 3, 0,0, 0,0, 192,2 }), "| x | x | " + R + " |");
        eq("two left lanes carry the route",
                AANavReader.laneRow(new int[]{ 4, 64,2, 64,2, 0,0, 192,0 }), "| " + L + " | " + L + " | x | x |");
        eq("straight",
                AANavReader.laneRow(new int[]{ 2, 0,2, 192,0 }), "| " + U + " | x |");
        eq("u-turn lane",
                AANavReader.laneRow(new int[]{ 2, 114,2, 0,0 }), "| " + D + " | x |");
        // Sharpness collapses: slight/normal/sharp left all draw the same cell.
        eq("sharpness collapses",
                AANavReader.laneRow(new int[]{ 3, 32,2, 64,2, 96,2 }), "| " + L + " | " + L + " | " + L + " |");
        eq("nothing recommended",
                AANavReader.laneRow(new int[]{ 2, 0,0, 192,0 }), "| x | x |");

        // No lanes must be "" and not "||", so the caller can tell "nothing to show" and leave the
        // street name in the row.
        eq("no lanes",        AANavReader.laneRow(new int[]{ 0 }), "");
        eq("null array",      AANavReader.laneRow(null), "");
        eq("count exceeds the array", AANavReader.laneRow(new int[]{ 5, 0,2 }), "");

        // Over six lanes: NOT_RECOMMENDED ones come off the head first, then the tail is cut. Same
        // rule the BAP array uses (selectLanes is shared) — six cells either way.
        String eight = AANavReader.laneRow(new int[]{ 8, 0,0, 0,0, 0,2, 0,2, 192,0, 192,0, 192,0, 192,0 });
        eq("eight lanes, head dropped", eight,
                "| " + U + " | " + U + " | x | x | x | x |");
        // Nothing to drop off the head (lane 0 carries the route), so the two extra go off the tail.
        String headKept = AANavReader.laneRow(new int[]{ 8, 0,2, 192,0, 192,0, 192,0, 192,0, 192,0, 192,2, 192,2 });
        eq("eight lanes, head recommended -> tail cut", headKept,
                "| " + U + " | x | x | x | x | x |");
        eq("six is the cap", AANavReader.selectLanes(
                new int[]{ 8, 0,0, 0,0, 0,2, 0,2, 192,0, 192,0, 192,0, 192,0 }).length, 6);

        // Width is the whole reason this row lives in the street line: the unpadded version smeared
        // on the car, and the padded worst case does not fit the ~24-character fourth slot. If this
        // ever grows again, it has to be re-measured on the cluster, not assumed.
        eq("six padded lanes", eight.length(), 25);

        // The join, exactly as processOnce() wires it — the half that broke last time.
        String[] file = {
            "89 1 12 2 0 0 92 13 1 1 2 Example Road / Second Ring",
            "2 3 8 3 3",
            "3 90 m"
        };
        eq("lane row off a whole file",
                AANavReader.laneRow(AANavReader.packLanes(AANavReader.taggedLine(file, '2'))),
                "| x | " + U + " | " + U + " |");
        eq("no lane line -> empty row",
                AANavReader.laneRow(AANavReader.packLanes(AANavReader.taggedLine(
                        new String[]{ file[0], "3 90 m", null }, '2'))), "");
    }

    // ---- destination -> the row that fills the slot when there are no lanes ---------------------
    private static void destinationRow() {
        eq("the DHU capture",  AANavReader.destinationRow(11460, 2170), "36 min / 11 km");
        eq("under 10 km keeps the decimal", AANavReader.destinationRow(9430, 600), "10 min / 9,4 km");
        eq("nearly there",     AANavReader.destinationRow(850, 90), "2 min / 850 m");
        eq("over an hour",     AANavReader.destinationRow(32000, 5100), "1 h 25 min / 32 km");

        // Either half may be missing; the row then carries the other one alone rather than a
        // dangling separator.
        eq("time only",        AANavReader.destinationRow(0, 2170), "36 min");
        eq("distance only",    AANavReader.destinationRow(11460, 0), "11 km");
        eq("phone said neither", AANavReader.destinationRow(0, 0), "");
        eq("negatives",        AANavReader.destinationRow(-1, -1), "");

        eq("trip distance rounds to whole km", AANavReader.formatTripDistance(32499), "32 km");
        eq("trip distance rounds up",          AANavReader.formatTripDistance(32500), "33 km");
        eq("10 km is the switch",              AANavReader.formatTripDistance(10000), "10 km");
        eq("just under it",                    AANavReader.formatTripDistance(9999), "9,9 km");
    }

    // ---- destination line -> distance / remaining travel time ----------------------------------
    private static void destination() {
        int[] d = AANavReader.packDestination("11460 2170");   // the DHU capture, "11 km / 36 min"
        eq("dest metres",  d[0], 11460);
        eq("dest seconds", d[1], 2170);

        // Absent means "the phone has not said" — and must read as 0, because 0 is what makes both
        // cluster functions report themselves invalid rather than showing a stale trip.
        eq("no line, metres",  AANavReader.packDestination(null)[0], 0);
        eq("no line, seconds", AANavReader.packDestination(null)[1], 0);
        eq("half a line",      AANavReader.packDestination("11460")[0], 0);
        eq("garbage",          AANavReader.packDestination("x y")[0], 0);
        eq("negative rejected", AANavReader.packDestination("-1 -1")[0], 0);

        int[] hm = AANavReader.remainingHoursMinutes(2170);
        eq("2170 s -> hours",   hm[0], 0);
        eq("2170 s -> minutes", hm[1], 36);      // 36.2 min, rounded like a nav engine counts down
        eq("unknown -> 0 h",    AANavReader.remainingHoursMinutes(0)[0], 0);
        eq("unknown -> 0 min",  AANavReader.remainingHoursMinutes(0)[1], 0);
        eq("negative -> 0 min", AANavReader.remainingHoursMinutes(-5)[1], 0);
        eq("29 s -> 0 min",     AANavReader.remainingHoursMinutes(29)[1], 0);
        eq("31 s -> 1 min",     AANavReader.remainingHoursMinutes(31)[1], 1);
        eq("3600 s -> hours",   AANavReader.remainingHoursMinutes(3600)[0], 1);
        eq("3600 s -> minutes", AANavReader.remainingHoursMinutes(3600)[1], 0);
        // 3599 s must not become 1 h 60 min: the rounding carries into the hour.
        eq("3599 s -> hours",   AANavReader.remainingHoursMinutes(3599)[0], 1);
        eq("3599 s -> minutes", AANavReader.remainingHoursMinutes(3599)[1], 0);

        // Both fields are single BAP bytes, so nothing may leave the 0..99 / 0..59 boxes.
        int[] secs = { 1, 59, 60, 61, 3599, 86399, 999999, 1000000 };
        for (int i = 0; i < secs.length; i++) {
            int[] x = AANavReader.remainingHoursMinutes(secs[i]);
            if (x[0] < 0 || x[0] > 99 || x[1] < 0 || x[1] > 59) {
                failed++;
                System.out.println("  FAIL " + secs[i] + " s -> " + x[0] + " h " + x[1] + " min out of range");
            }
        }
    }

    // ---- the street line while the route is being recalculated ---------------------------------
    private static void rerouting() {
        eq("normal frame keeps the road", AANavReader.streetFor(false, "Example Road"), "Example Road");
        eq("rerouting takes the line",    AANavReader.streetFor(true,  "Example Road"), "Rerouting...");
        // The road may be empty or absent mid-recalculation — the notice must still appear, since
        // that is precisely the moment it is worth showing.
        eq("rerouting with no road",      AANavReader.streetFor(true,  ""), "Rerouting...");
        if (AANavReader.streetFor(true, null) == null) {
            failed++;
            System.out.println("  FAIL rerouting must not fall through to a null street");
        }
    }

    // ---- (event, side) -> MAIN_ELEMENT glyph ---------------------------------------------------
    private static void mainElements() {
        eq("DEPART",        AANavReader.mapMainElement(1, 3), 11);   // FOLLOW_STREET
        eq("NAME_CHANGE",   AANavReader.mapMainElement(2, 3), 11);
        eq("SLIGHT_TURN",   AANavReader.mapMainElement(3, 1), 13);   // TURN
        eq("TURN",          AANavReader.mapMainElement(4, 2), 13);
        eq("SHARP_TURN",    AANavReader.mapMainElement(5, 1), 13);
        eq("U_TURN",        AANavReader.mapMainElement(6, 1), 25);   // UTURN
        eq("ON_RAMP left",  AANavReader.mapMainElement(7, 1), 33);
        eq("ON_RAMP right", AANavReader.mapMainElement(7, 2), 32);
        eq("OFF_RAMP left", AANavReader.mapMainElement(8, 1), 16);
        eq("OFF_RAMP right",AANavReader.mapMainElement(8, 2), 15);
        eq("FORK",          AANavReader.mapMainElement(9, 1), 19);
        eq("MERGE",         AANavReader.mapMainElement(10, 2), 14);
        eq("RNDBT left",    AANavReader.mapMainElement(13, 1), 22);  // TRS_LEFT
        eq("RNDBT right",   AANavReader.mapMainElement(13, 2), 21);  // TRS_RIGHT
        eq("STRAIGHT",      AANavReader.mapMainElement(14, 3), 11);
        eq("DESTINATION",   AANavReader.mapMainElement(17, 3), 3);   // ARRIVED
        eq("unknown event", AANavReader.mapMainElement(99, 3), 13);  // falls back to TURN

        // the shim collapses every destination variant onto 17; if that ever changes, 18/19 must
        // still arrive at ARRIVED rather than the TURN fallback
        eq("DESTINATION_LEFT",  AANavReader.mapMainElement(18, 1), 3);
        eq("DESTINATION_RIGHT", AANavReader.mapMainElement(19, 2), 3);
    }

    // ---- (event, side, angle) -> arrow rotation ------------------------------------------------
    private static void directions() {
        eq("u-turn left",     AANavReader.mapDirection(6, 1, 0), 64);
        eq("u-turn right",    AANavReader.mapDirection(6, 2, 0), 192);
        eq("depart",          AANavReader.mapDirection(1, 3, 0), 0);
        eq("straight",        AANavReader.mapDirection(14, 3, 0), 0);
        eq("arrival",         AANavReader.mapDirection(17, 3, 0), 0);
        eq("arrival on the left",  AANavReader.mapDirection(17, 1, 0), 64);
        eq("arrival on the right", AANavReader.mapDirection(17, 2, 0), 192);

        eq("slight left",     AANavReader.mapDirection(3, 1, 0), 32);
        eq("slight right",    AANavReader.mapDirection(3, 2, 0), 224);
        eq("normal left",     AANavReader.mapDirection(4, 1, 0), 64);
        eq("normal right",    AANavReader.mapDirection(4, 2, 0), 192);
        eq("sharp left",      AANavReader.mapDirection(5, 1, 0), 96);
        eq("sharp right",     AANavReader.mapDirection(5, 2, 0), 160);
        eq("turn, no side",   AANavReader.mapDirection(4, 3, 0), 0);

        // roundabout: 180 deg means straight across, so it must render as "up"
        eq("rndbt 180",       AANavReader.mapDirection(13, 2, 180), 0);
        eq("rndbt 270",       AANavReader.mapDirection(13, 2, 270), 64);
        eq("rndbt 90",        AANavReader.mapDirection(13, 2, 90), (-64) & 0xFF);
        eq("rndbt no angle",  AANavReader.mapDirection(13, 2, 0), 0);

        for (int event = 0; event <= 20; event++) {
            for (int side = 1; side <= 3; side++) {
                int d = AANavReader.mapDirection(event, side, 0);
                if (d < 0 || d > 255) {
                    failed++;
                    System.out.println("  FAIL direction out of byte range for event " + event);
                }
            }
        }
    }

    // ---- roundabout exit bearing ---------------------------------------------------------------
    private static void angles() {
        eq("real angle passes through", AANavReader.effectiveAngle(13, 270, 2), 270);
        eq("non-roundabout untouched",  AANavReader.effectiveAngle(4, 0, 3), 0);
        eq("exit 1 synthesised",        AANavReader.effectiveAngle(13, 0, 1), 90);
        eq("exit 2 synthesised",        AANavReader.effectiveAngle(13, 0, 2), 180);
        eq("exit 3 synthesised",        AANavReader.effectiveAngle(13, 0, 3), 270);
        eq("exit 5 clamped",            AANavReader.effectiveAngle(13, 0, 5), 315);
        eq("no angle, no number",       AANavReader.effectiveAngle(13, 0, 0), 0);

        eq("side streets only on a roundabout", AANavReader.buildRingSideStreets(4, 270), "");
        eq("side streets need a bearing",       AANavReader.buildRingSideStreets(13, 0), "");
        if (AANavReader.buildRingSideStreets(13, 270).length() != 1) {
            failed++;
            System.out.println("  FAIL roundabout side streets should be one glyph byte");
        }
    }

    // ---- when the manoeuvre replaces "follow the street" ---------------------------------------
    // 0 means "not said" on both fields; read as "zero away" it would show the manoeuvre all route.
    private static void imminence() {
        int m = de.aatokombi.Config.MANEUVER_NEAR_METERS;
        int s = de.aatokombi.Config.MANEUVER_NEAR_SECONDS;

        yes("at the metre gate",        AANavReader.maneuverImminent(m, 0));
        yes("inside the metre gate",    AANavReader.maneuverImminent(m - 1, 0));
        no ("beyond the metre gate",    AANavReader.maneuverImminent(m + 1, 0));
        yes("at the time gate",         AANavReader.maneuverImminent(50000, s));
        yes("inside the time gate",     AANavReader.maneuverImminent(50000, s - 1));
        no ("beyond both gates",        AANavReader.maneuverImminent(5000, 300));
        yes("time gate saves the exit", AANavReader.maneuverImminent(800, 26));   // 110 km/h

        no ("far, no countdown",        AANavReader.maneuverImminent(5000, 0));
        yes("nothing known at all",     AANavReader.maneuverImminent(0, 0));
        yes("negative = not known",     AANavReader.maneuverImminent(-1, -1));
	}
	
    // ---- multi-spoke junction schematic (this class itself has no idea what cluster it runs on --
    // the NavCapable gate lives entirely in AANavReader.publishNavsd(), which is exactly why these
    // are plain, host-testable functions of their inputs) ---------------------------------------
    //
    // JunctionSchematic.snapToNearest()/circularDistance() take an explicit table argument and are
    // tested here directly against hand-picked tables. buildRoundabout() and buildOrdinary() are
    // tested against the tables JunctionSchematic actually uses: the fine 22.5-deg roundabout grid
    // (ROUNDABOUT_ANGLES, every multiple of 16 except 128), the 45-deg TURN grid (TURN_ANGLES) and
    // the six oblique FOLLOW_STREET positions (FOLLOW_ANGLES: 48, 64, 80, 176, 192, 208).
    private static void junctionSchematic() {
        // -- snapToNearest / circularDistance: the shared angle-quantization primitive ------------
        int[] a45 = { 0, 32, 64, 96, 160, 192, 224 };
        int[] a22 = { 0, 16, 32, 48, 64, 80, 96, 112, 144, 160, 176, 192, 208, 224, 240 };

        eq("snap exact 0",   JunctionSchematic.snapToNearest(0, a45), 0);
        eq("snap exact 64",  JunctionSchematic.snapToNearest(64, a45), 64);
        eq("snap near 100 -> 96 (not 160)", JunctionSchematic.snapToNearest(100, a45), 96);
        // A tie between two equidistant table entries must resolve deterministically (lower one),
        // not flap between builds/JIT states.
        eq("snap tie 16 -> lower neighbour (0)",  JunctionSchematic.snapToNearest(16, a45), 0);
        eq("snap 128 on the coarse grid",         JunctionSchematic.snapToNearest(128, a45), 96);
        eq("snap 128 on the fine grid",           JunctionSchematic.snapToNearest(128, a22), 112);
        // Wraparound across the 0/256 seam.
        eq("snap wraparound 250 -> 0",            JunctionSchematic.snapToNearest(250, a45), 0);
        // The result is always one of the table's own entries -- the primitive must not invent a
        // value that isn't in the table it was given.
        for (int v = 0; v < 256; v++) {
            int s = JunctionSchematic.snapToNearest(v, a45);
            boolean inTable = false;
            for (int i = 0; i < a45.length; i++) {
                if (a45[i] == s) { inTable = true; break; }
            }
            if (!inTable) {
                failed++;
                System.out.println("  FAIL snapToNearest(" + v + ", a45) = " + s + " is not one of a45's own entries");
            }
        }
        eq("circularDistance same",     JunctionSchematic.circularDistance(0, 0), 0);
        eq("circularDistance opposite", JunctionSchematic.circularDistance(0, 128), 128);
        eq("circularDistance wraps",    JunctionSchematic.circularDistance(10, 250), 16);

        // -- buildRoundabout (fine 22.5-deg grid, ROUNDABOUT_ANGLES) ---------------------------------
        // First exit taken (nothing passed): exactly one spoke, at the taken exit's own bearing.
        String r1 = JunctionSchematic.buildRoundabout(180, 1);
        eq("roundabout exit1 spoke count", r1.length(), 1);
        eq("roundabout exit1 spoke value", (int) r1.charAt(0), 0);

        // Third exit taken (the 2026-09 field-test case: Google Maps roundabout, effAngle=270 exit=3):
        // the two passed exits are spread along the shorter arc from the entry (raw 107, 86) and,
        // on the fine grid, each keeps its own slot -> three distinct spokes {0x70,0x50,0x40}.
        // The taken exit's own snapped bearing is always last.
        String r3 = JunctionSchematic.buildRoundabout(270, 3);
        eq("roundabout exit3 (fine grid)", r3, charStr(112, 80, 64));
        eq("roundabout exit3 taken spoke is last", (int) r3.charAt(r3.length() - 1), 64);

        // The OTHER 2026-09 field-test case (Yandex roundabout, effAngle synthesised from exit=2):
        // both spokes sit exactly on grid positions, so this is byte-for-byte what was actually
        // logged on the car ({0xc0,0x00}).
        String r2 = JunctionSchematic.buildRoundabout(180, 2);
        eq("roundabout exit2 (matches the field log)", r2, charStr(192, 0));

        // No usable bearing at all (angle out of AA's 1..360 range, e.g. AND no exit number to
        // synthesize one from) -> base-only schematic, i.e. an empty spoke string, never a throw.
        eq("roundabout no angle (0)",   JunctionSchematic.buildRoundabout(0, 3), "");
        eq("roundabout no angle (361)", JunctionSchematic.buildRoundabout(361, 3), "");

        // A huge / garbled exit number must still terminate (MAX_PASSED_EXITS clamp) and produce a
        // string within the BAPString(17) capacity, never an exception or a runaway loop.
        String huge = JunctionSchematic.buildRoundabout(270, 999);
        if (huge.length() > 17) {
            failed++;
            System.out.println("  FAIL roundabout huge exit number overflowed BAPString(17): len=" + huge.length());
        }
        eqNoThrowRoundabout(-5, -3);   // negative inputs must not throw either

        // -- buildOrdinary --------------------------------------------------------------------------
        // Fewer than two known roads -> not worth a schematic -> null, and mainElement must be left
        // completely alone by the caller (buildOrdinary never reports one back -- there is nothing
        // to report; it only ever attaches spokes to the mainElement it was given).
        eqNull("ordinary: no lanes at all",
                JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_TURN, null));
        eqNull("ordinary: one lane only",
                JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_TURN, new int[]{ 1, 64, 2 }));
        eqNull("ordinary: malformed count",
                JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_TURN, new int[]{ 5, 64, 0 }));

        // A mainElement this class does not attach spokes to (e.g. SQUARE_TRS_LEFT, which this mod
        // does not produce, or an arbitrary other value) -> null regardless of how much lane data
        // there is. This is the point-2 fix: no mainElement is ever chosen or overridden in here.
        eqNull("ordinary: unsupported mainElement (23, SQUARE_TRS_RIGHT)",
                JunctionSchematic.buildOrdinary(23, new int[]{ 2, 64, 0, 192, 2 }));
        eqNull("ordinary: unsupported mainElement (21, ROUNDABOUT_TRS_RIGHT)",
                JunctionSchematic.buildOrdinary(21, new int[]{ 2, 64, 0, 192, 2 }));

        // Two lanes so close together they snap onto the SAME spoke collapse to one char, which is
        // just as "not worth it" as only ever having seen one direction.
        eqNull("ordinary/TURN: lanes collapse to one spoke",
                JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_TURN, new int[]{ 2, 0, 2, 16, 0 }));

        // TURN: two genuinely distinct roads -> a 2-spoke schematic, mainElement untouched (there is
        // no output parameter to touch any more).
        String turn2 = JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_TURN, new int[]{ 2, 64, 0, 192, 2 });
        eq("ordinary/TURN: two distinct lanes", turn2, charStr(64, 192));

        // TURN: three distinct roads, including one that continues straight across (0 is a legal
        // TURN position too).
        String turn3 = JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_TURN, new int[]{ 3, 0, 2, 32, 0, 96, 0 });
        eq("ordinary/TURN: three distinct lanes", turn3, charStr(0, 32, 96));

        // One physical lane may legally carry several directions. The selected/highlighted direction
        // remains its BAP primary arrow, but the complete list must reach the junction geometry.
        NavState.LaneState combined = AANavReader.packLaneState("v 1 2 2 9");
        eq("combined lane primary", combined.lanes[1], 64);
        eq("combined lane BAP alternative", AANavReader.laneSideStreets(combined, 0), charStr(0));
        eq("ordinary/TURN: one combined lane supplies two roads",
                JunctionSchematic.buildOrdinaryFromLaneState(AANavReader.MAIN_ELEMENT_TURN, combined),
                charStr(0, 64));

        // TURN_ON_MAINROAD shares TURN's exact table.
        String mainroad = JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_TURN_ON_MAINROAD, new int[]{ 2, 64, 0, 192, 2 });
        eq("ordinary/TURN_ON_MAINROAD: same table as TURN", mainroad, charStr(64, 192));

        // FOLLOW_STREET has six legal oblique positions (48, 64, 80 on the left, 176, 192, 208 on the
        // right), so two lanes 16 apart on the same side stay two distinct spokes...
        String followSameSide = JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_FOLLOW_STREET, new int[]{ 2, 48, 0, 64, 2 });
        eq("ordinary/FOLLOW: two lanes, same side", followSameSide, charStr(48, 64));
        // ...a lane on each side of the road keeps its own exact oblique...
        String follow2 = JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_FOLLOW_STREET, new int[]{ 2, 80, 0, 176, 2 });
        eq("ordinary/FOLLOW: obliques on both sides", follow2, charStr(80, 176));
        // ...and lanes that snap onto one FOLLOW position (straight ahead 0 and 32 both land on 48)
        // are still "not worth a schematic".
        eqNull("ordinary/FOLLOW: lanes collapse to one spoke",
                JunctionSchematic.buildOrdinary(AANavReader.MAIN_ELEMENT_FOLLOW_STREET, new int[]{ 2, 0, 0, 32, 2 }));
    }

    private static void yes(String what, boolean got) {
        if (!got) { failed++; System.out.println("  FAIL " + what + ": expected the manoeuvre"); }
    }

    private static void no(String what, boolean got) {
        if (got) { failed++; System.out.println("  FAIL " + what + ": expected follow-street"); }
    }

    // char values as a String, matching what JunctionSchematic itself builds (a plain StringBuffer
    // of raw char codes -- no printable text, so this beats writing hand-escaped literals at every
    // call site above).
    private static String charStr(int a) {
        return String.valueOf((char) a);
    }
    private static String charStr(int a, int b) {
        return charStr(a) + charStr(b);
    }
    private static String charStr(int a, int b, int c) {
        return charStr(a) + charStr(b) + charStr(c);
    }

    private static void nextManeuver() {
        // IPC line 5 payload
        int[] n = AANavReader.packNextManeuver("4 1 0 0");
        eq("line 5 event", n == null ? -1 : n[0], 4);
        eq("line 5 side",  n == null ? -1 : n[1], 1);
        eqNull("line 5 absent",    AANavReader.packNextManeuver(null));
        eqNull("line 5 empty",     AANavReader.packNextManeuver(""));
        eqNull("line 5 malformed", AANavReader.packNextManeuver("4 x"));
        eqNull("line 5 truncated", AANavReader.packNextManeuver("4 1 0"));
        eqNull("line 5 no event",  AANavReader.packNextManeuver("0 3 0 0"));

        int follow = AANavReader.MAIN_ELEMENT_FOLLOW_STREET;
        // far: maneuver_2 previews the current manoeuvre itself, whatever steps[1] says
        NavState.NextManeuver f = AANavReader.nextManeuverFor(false, 13, 192, "", new int[]{ 4, 1, 0, 0 });
        eq("far: previews the current main element", f == null ? -1 : f.mainElement, 13);
        eq("far: previews the current direction",    f == null ? -1 : f.direction, 192);
        eqNull("far: no preview of a follow-street",
                AANavReader.nextManeuverFor(false, follow, 0, "", new int[]{ 4, 1, 0, 0 }));
        // near: maneuver_2 = steps[1]
        NavState.NextManeuver x = AANavReader.nextManeuverFor(true, 13, 192, "", new int[]{ 4, 1, 0, 0 });
        eq("near: steps[1] main element", x == null ? -1 : x.mainElement, 13);
        eq("near: steps[1] direction (left)", x == null ? -1 : x.direction, AANavReader.mapDirection(4, 1, 0));
        eqNull("near: no steps[1]", AANavReader.nextManeuverFor(true, 13, 192, "", null));
        eqNull("near: steps[1] is just straight on",
                AANavReader.nextManeuverFor(true, 13, 192, "", new int[]{ 14, 3, 0, 0 }));
        NavState.NextManeuver r = AANavReader.nextManeuverFor(true, 13, 192, "", new int[]{ 13, 2, 270, 2 });
        eq("near: roundabout as steps[1]", r == null ? -1 : r.mainElement, AANavReader.mapMainElement(13, 2));
        eq("near: roundabout keeps its exit spoke", r == null ? -1 : r.sideStreets.length(), 1);
    }

    private static void currentRoad() {
        eq("far: the current road captions FOLLOW_STREET",
                AANavReader.turnToStreet("Next St", "Current St", false), "Current St");
        eq("near: the road we turn into",
                AANavReader.turnToStreet("Next St", "Current St", true), "Next St");
        eq("far, current unknown: next road as before",
                AANavReader.turnToStreet("Next St", "", false), "Next St");
        eq("far, current null: next road as before",
                AANavReader.turnToStreet("Next St", null, false), "Next St");
        eq("rerouting notice wins even while far",
                AANavReader.turnToStreet(AANavReader.streetFor(true, "Next St"), "Current St", false),
                AANavReader.streetFor(true, "Next St"));
        eq("no roads at all", AANavReader.turnToStreet(null, null, false), "");

    private static void playbackMode() {
        // bit0 shuffle, bit1 repeat, bit2 repeat-one -> {stateInfo, stateInfo_Scope}
        int[][] cases = {
            { 0, 0, 0 },   // nothing
            { 1, 2, 4 },   // shuffle            -> mix, the list
            { 2, 3, 4 },   // repeat             -> repeat, the list
            { 4, 3, 1 },   // repeat one         -> repeat, the track
            { 3, 4, 4 },   // shuffle + repeat   -> repeat + mix, the list
            { 5, 4, 1 },   // shuffle + one      -> repeat + mix, the track
            { 6, 3, 1 },   // repeat + one       -> one wins (the narrower scope)
        };
        for (int i = 0; i < cases.length; i++) {
            int[] r = de.vw.mib.bap.mqbab2.audiosd.functions.AAPlaybackMode.stateInfo(cases[i][0]);
            eq("mode " + cases[i][0] + " stateInfo", r[0], cases[i][1]);
            eq("mode " + cases[i][0] + " scope",     r[1], cases[i][2]);
        }
    }

    private static void eqNoThrowRoundabout(int effAngle, int exitNumber) {
        try {
            JunctionSchematic.buildRoundabout(effAngle, exitNumber);
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL buildRoundabout(" + effAngle + "," + exitNumber + ") threw: " + t);
        }
    }

    public static void main(String[] args) {
        String[] names = { "lane shapes", "lane packing", "lane row", "destination",
                           "destination row", "rerouting", "main elements",
                           "directions", "roundabout angles", "imminence",
						   "next maneuver", "current road", 
						   "playback mode", "junction schematic" };
        for (int i = 0; i < names.length; i++) {
            int before = failed;
            switch (i) {
                case 0: laneShapes(); break;
                case 1: lanePacking(); break;
                case 2: laneRow(); break;
                case 3: destination(); break;
                case 4: destinationRow(); break;
                case 5: rerouting(); break;
                case 6: mainElements(); break;
                case 7: directions(); break;
                case 8: angles(); break;
                case 9: imminence(); break;
                case 10: nextManeuver(); break;
                case 11: currentRoad(); break;
                case 12: playbackMode(); break;
                default: junctionSchematic(); break;
            }
            System.out.println(pad(names[i]) + (failed == before ? "ok" : "FAILED"));
        }
        System.out.println(failed == 0 ? "all tests passed" : "TESTS FAILED");
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static String pad(String s) {
        StringBuffer b = new StringBuffer(s);
        while (b.length() < 24) {
            b.append(' ');
        }
        return b.toString();
    }
}
