package de.vw.mib.bap.mqbab2.navsd.functions;

/**
 * Builds the navsd ManeuverDescriptor "sidestreets" string for a multi-spoke junction schematic
 * (several side-street glyphs drawn around the maneuver arrow), instead of the single roundabout
 * spoke the mono-cluster path (buildRingSideStreets in AANavReader) has always sent.
 *
 * This class itself has no idea what cluster it is running on -- gating on
 * {@link ClusterCaps#isNavCapable()} is the CALLER's job (AANavReader.publishNavsd()), on purpose:
 * keeping this class a pure function of its inputs is what makes it host-testable (see
 * tests/jar/.../NavMappingTest.java), and it means a stale capability read can never smuggle a
 * schematic onto a cluster that cannot draw one -- the caller decides whether to invoke this class.
 *
 * Reverse-engineered from the stock de.vw.mib.widgets.Guidance / GuidanceModel widget bundled in
 * MIBHMI.jar (decompiled with CFR 0.152), which is what actually parses this field:
 *   - Maneuver_1.sidestreets is a BAPString(17) -- NOT a single glyph. Guidance.updateSideStreets()
 *     turns each character of it into an independent IntSet entry, and GuidanceUI.drawSideStreets()
 *     draws every one of them, positioned around a fixed "you are here" base icon.
 *   - The character values are NOT an arbitrary glyph table: they are angles on the exact same
 *     0..255 CCW scale AANavReader already uses for NavState.direction (0 = straight ahead,
 *     128 = straight behind/where you entered from, 64 = left, 192 = right). 128 itself is never
 *     sent: the receiver always draws the entry ("base") spoke unconditionally, whatever the string
 *     contains.
 *   - Only SIX mainElement values are ever decoded into side-street spokes at all (everything else
 *     silently ignores the field): ROUNDABOUT_TRS_LEFT/RIGHT (22/21), SQUARE_TRS_LEFT/RIGHT (24/23),
 *     FOLLOW_STREET (11), TURN/TURN_ON_MAINROAD (13/14), EXIT_LEFT(_RAMP) (16/33),
 *     EXIT_RIGHT(_RAMP) (15/32). Each has its OWN legal subset of angle positions.
 *
 *   SQUARE_TRS_LEFT/RIGHT is VW's own glyph for a large plaza/square-shaped junction, NOT a stand-in
 *   for "any ordinary crossing with more than one road" -- overriding mainElement to it for a plain
 *   turn was this class's original (wrong) design. This class no longer touches mainElement AT ALL:
 *   buildOrdinary() attaches spokes to whichever of FOLLOW_STREET/TURN/TURN_ON_MAINROAD
 *   AANavReader.mapMainElement() already picked, using that specific mainElement's own legal angle
 *   set. SQUARE_TRS_* is not produced by this mod at all right now (nothing in the AA feed says
 *   "this is a large plaza" as opposed to an ordinary crossing) -- a real MAIN_ELEMENT_DOUBLE_TURN(35)
 *   or a plaza heuristic would be a separate, future feature, not a side effect of lane data existing.
 *
 * Java 1.4 (cf48): no generics, plain arrays, manual de-duplication (mirrors AANavReader.selectLanes
 * / packLanes rather than pulling in java.util.Set).
 */
final class JunctionSchematic {

    // Every multiple of 16 in 0..255 except 128 (the entry spoke, always drawn implicitly by the
    // receiver and therefore never sent). ROUNDABOUT_TRS_* and SQUARE_TRS_* both decode this exact
    // 15-position set; only the former is used here (see class comment on SQUARE_TRS_*).
    private static final int[] ROUNDABOUT_ANGLES = {
            0, 16, 32, 48, 64, 80, 96, 112, /* 128 omitted */ 144, 160, 176, 192, 208, 224, 240
    };

    // TURN/TURN_ON_MAINROAD's own legal set -- an 8-point/45-deg grid, confirmed directly from the
    // decompile (not a resolution choice: this is simply the only set that mainElement supports).
    private static final int[] TURN_ANGLES = { 0, 32, 64, 96, /* 128 omitted */ 160, 192, 224 };

    // FOLLOW_STREET's own legal set: obliques only, no 0/128/64/192 (see class comment on
    // Guidance.doSideStreetsFollow).
    private static final int[] FOLLOW_ANGLES = { 48, 64, 80, 176, 192, 208 };

    // Sane bound on how many "passed" roundabout exits to synthesize spokes for. The table length
    // and the 17-char BAPString cap it anyway, but a roundabout with an implausible exit count
    // (garbled AA frame) should not build a string that is mostly noise. Mirrors NavState.MAX_LANES.
    private static final int MAX_PASSED_EXITS = 8;

    private JunctionSchematic() {}

    /**
     * Roundabout schematic: one spoke for the exit actually taken (at its real bearing when Google
     * Maps sends one, or the synthesized bearing effectiveAngle() already computes for Yandex),
     * plus one synthetic spoke per exit passed before it, evenly spread between the entry and the
     * taken exit. There is no way to tell a "passed" spoke from the "taken" one apart in the string
     * -- exactly like the stock decoder (Guidance.doSideStreetsRoundabout), which does not either;
     * the arrow (direction) is what shows which one is being taken.
     *
     * @param effAngle   compass bearing of the taken exit, 1..360, 180 = straight across (i.e. the
     *                   same value AANavReader.effectiveAngle() already produces for mapDirection());
     *                   any other value (angle truly unknown) yields a base-only schematic ("").
     * @param exitNumber 1-based ordinal of the exit taken (AA's roundabout_exit_number); 0 or
     *                   negative is treated as "unknown" (0 passed exits, taken spoke only).
     * @param counterClockwise true for a right-hand-traffic ring (ROUNDABOUT_TRS_RIGHT, entered
     *                   turning right): the exits passed lie on the arc the car actually drives,
     *                   entry -&gt; right -&gt; ahead -&gt; left, i.e. increasing on the 0..255 CCW
     *                   scale; false (TRS_LEFT) the other way round. Until 2026-10 the shorter arc
     *                   was used, which put the passed exits of a 3rd-exit-left ring on the LEFT,
     *                   where no exit is passed -- and the AID drew none of them.
     * @return a string of distinct spoke characters (up to ROUNDABOUT_ANGLES.length, 15), or "" if
     *         effAngle is not usable. Never throws.
     */
    static String buildRoundabout(int effAngle, int exitNumber, boolean counterClockwise) {
        try {
            if (effAngle < 1 || effAngle > 360) {
                return "";                                  // no bearing at all -> base-only schematic
            }
            // Same 0..255 CCW transform mapDirection() uses for the arrow, so the spokes and the
            // arrow agree on where "the exit you take" is. [HW-TUNE], see class comment.
            int takenD = ((effAngle - 180) * 256 / 360) & 0xFF;

            int passed = exitNumber - 1;
            if (passed < 0) {
                passed = 0;
            }
            if (passed > MAX_PASSED_EXITS) {
                passed = MAX_PASSED_EXITS;
            }

            int[] raw = new int[passed + 1];
            if (passed > 0) {
                // The arc from the entry (128) to the taken exit in the direction of travel; passed
                // exits spread evenly along it. [HW-TUNE: the AID's acceptance is being verified]
                int fwd = ((takenD - 128) % 256 + 256) % 256;      // going CCW (increasing)
                if (fwd == 0) {
                    fwd = 256;                                     // taken exit = back the way you came
                }
                int arc = counterClockwise ? fwd : -(256 - fwd);
                for (int i = 0; i < passed; i++) {
                    raw[i] = (128 + arc * (i + 1) / (passed + 1)) & 0xFF;
                }
            }
            raw[passed] = takenD;

            return snapAndDedupe(raw, ROUNDABOUT_ANGLES);
        } catch (Throwable t) {
            return "";                                       // never let a bad frame break navsd
        }
    }

    /**
     * Ordinary (non-roundabout) junction schematic, built from Android Auto's per-lane directions
     * (NavState.lanes, already on the same 0..255 scale via AANavReader.laneShapeToDirection) --
     * the only AA data that ever describes more than one road at a non-roundabout turn. Every
     * distinct known lane direction becomes a spoke, whether or not it is the recommended one (the
     * recommended one is shown by the arrow, exactly as on a roundabout).
     *
     * Deliberately does NOT choose or change mainElement: the caller (AANavReader.publishNavsd())
     * passes in whatever AANavReader.mapMainElement() already decided (FOLLOW_STREET / TURN /
     * TURN_ON_MAINROAD), and this method only ever attaches spokes to that SAME mainElement, using
     * its own legal angle set. Any other mainElement (including SQUARE_TRS_LEFT/RIGHT, which this
     * mod does not produce -- see class comment) returns null immediately.
     *
     * Also conservative about WHEN to bother: with fewer than two distinct directions there is
     * nothing a schematic would show beyond what the plain arrow already shows, so this returns null
     * ("not worth it, keep the caller's existing sidestreets behaviour") rather than a degenerate
     * one-spoke string.
     *
     * @param mainElement AANavReader.MAIN_ELEMENT_FOLLOW_STREET / MAIN_ELEMENT_TURN /
     *                    MAIN_ELEMENT_TURN_ON_MAINROAD (mapMainElement()'s output for this frame);
     *                    anything else returns null.
     * @param laneState   primary BAP arrows plus every direction of every physical lane. The latter
     *                    matters for a combined "straight or left" lane: both roads belong in the
     *                    junction, even though BAP laneDirection can draw only one primary arrow.
     * @return a string of 2..N distinct spoke characters, or null if the mainElement is not one this
     *         method knows, or there is not enough distinct lane data to justify a schematic. Never
     *         throws.
     */
    static String buildOrdinary(int mainElement, int[] lanes) {
        return buildOrdinaryFromLaneState(mainElement, new NavState.LaneState(lanes, null));
    }

    static String buildOrdinaryFromLaneState(int mainElement, NavState.LaneState laneState) {
        try {
            int[] table;
            if (mainElement == AANavReader.MAIN_ELEMENT_TURN
                    || mainElement == AANavReader.MAIN_ELEMENT_TURN_ON_MAINROAD) {
                table = TURN_ANGLES;                   // TURN's own legal set; no finer variant exists
            } else if (mainElement == AANavReader.MAIN_ELEMENT_FOLLOW_STREET) {
                table = FOLLOW_ANGLES;
            } else {
                return null;                          // not a mainElement this class attaches spokes to
            }

            int[] lanes = laneState != null ? laneState.lanes : null;
            int total = (lanes != null && lanes.length > 0) ? lanes[0] : 0;
            if (lanes == null || total > (lanes.length - 1) / 2) {
                total = 0;                            // torn/malformed read -> no lanes
            }
            int[] raw = new int[total * NavState.MAX_LANE_DIRECTIONS];
            int rawCount = 0;
            for (int i = 0; i < total; i++) {
                int[] directions = AANavReader.laneDirections(laneState, i);
                for (int d = 0; d < directions.length && rawCount < raw.length; d++) {
                    raw[rawCount++] = directions[d];
                }
            }
            if (rawCount < 2) {
                return null;
            }
            if (rawCount != raw.length) {
                int[] trimmed = new int[rawCount];
                System.arraycopy(raw, 0, trimmed, 0, rawCount);
                raw = trimmed;
            }
            String spokes = snapAndDedupe(raw, table);
            if (spokes.length() < 2) {
                return null;                            // every lane snapped onto one spoke
            }
            return spokes;
        } catch (Throwable t) {
            return null;                                // never let a bad frame break navsd
        }
    }

    // Snap each raw 0..255 value to the nearest legal spoke position in `table` and drop duplicates
    // (two raw values landing on the same slot must draw one spoke, not two overlapping ones). Order
    // of first appearance is preserved; output length is naturally capped by table.length, well
    // inside the BAPString(17) capacity the stock field advertises.
    private static String snapAndDedupe(int[] raw, int[] table) {
        StringBuffer sb = new StringBuffer(raw.length);
        for (int i = 0; i < raw.length; i++) {
            char c = (char) snapToNearest(raw[i], table);
            if (sb.toString().indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static int snapToNearest(int value, int[] table) {
        value &= 0xFF;
        int best = table[0];
        int bestDist = circularDistance(table[0], value);
        for (int i = 1; i < table.length; i++) {
            int d = circularDistance(table[i], value);
            if (d < bestDist) {
                bestDist = d;
                best = table[i];
            }
        }
        return best;
    }

    static int circularDistance(int a, int b) {
        int d = Math.abs(a - b) & 0xFF;
        return (d > 128) ? 256 - d : d;
    }
}
