package com.ghosthands.app;

import java.util.List;

/**
 * v3.18 (Everything Train R9 — the morning briefing): when a
 * briefing is due, and what it says.
 *
 * DUE: exactly once per day, on the day's first open, gated
 * by the setting. "Day" is a year/day-of-year stamp computed
 * in the phone's zone — monotonic across DST (the fall-back
 * day is one day, stamped once). The stamp advances when the
 * launch pass runs with the setting ON, whether or not there
 * was anything to say (a verdict-free new day still advances,
 * so a phone opened at noon is not "briefed" for a check that
 * lands at three — it was opened; the day is underway). With
 * the setting OFF the pass does nothing at all — no stamp —
 * so switching it on later briefs the backlog that built up,
 * bounded by BriefingLog's cap.
 *
 * SAY: the plain-language digest over the log's unbriefed
 * entries, in an order that leads with what matters:
 *   1. CHANGED — the label and its first diff line (the same
 *      human line the answer card shows), with a quiet-hours
 *      suffix when the verdict's notification was held.
 *   2. NOT_DONE — checks that could not complete.
 *   3. BASELINE — watches that just started watching.
 * NO_CHANGE entries are never itemised (a briefing that
 * recites "still the same" ten times trains the listener to
 * stop listening) but are counted in a closing line. At most
 * five lines are itemised; the overflow folds into the count
 * line. An all-quiet digest is the count line alone — and IS
 * delivered: silence from the briefing must mean the setting
 * or the day, never an ambiguity.
 *
 * Pure: no Android, no I/O.
 */
public final class BriefingDigest {

    private BriefingDigest() { }

    /** Itemised lines' cap; the rest folds into the count line. */
    public static final int MAX_ITEMISED = 5;

    /** Due when the setting is on and today is a later day
     *  than the last briefed one. */
    public static boolean due(boolean settingOn, long lastDay,
                              long todayDay) {
        return settingOn && todayDay > lastDay;
    }

    /**
     * The digest text for the log's unbriefed entries, or null
     * when there is nothing to deliver (no unbriefed entries
     * at all — the launch pass advances the stamp and moves
     * on; see due()).
     */
    public static String compose(BriefingLog log) {
        if (log == null) return null;
        List<BriefingLog.Entry> pending = log.unbriefed();
        if (pending.isEmpty()) return null;

        StringBuilder lines = new StringBuilder();
        int itemised = 0;
        int folded = 0;
        int unchanged = 0;

        // The order that matters: changes, then checks that
        // could not complete, then new watches.
        String[] order = {
                WatchCompare.CHANGED, WatchCompare.NOT_DONE,
                WatchCompare.BASELINE,
        };
        for (int o = 0; o < order.length; o++) {
            for (int i = 0; i < pending.size(); i++) {
                BriefingLog.Entry e = pending.get(i);
                if (!order[o].equals(e.state)) continue;
                if (itemised >= MAX_ITEMISED) {
                    folded++;
                    continue;
                }
                if (lines.length() > 0) lines.append("\n");
                lines.append(lineFor(e));
                itemised++;
            }
        }
        for (int i = 0; i < pending.size(); i++) {
            if (WatchCompare.NO_CHANGE.equals(pending.get(i).state)) {
                unchanged++;
            }
        }

        StringBuilder counts = new StringBuilder();
        if (folded > 0) {
            counts.append("and ").append(folded)
                    .append(" more change")
                    .append(folded == 1 ? "" : "s")
                    .append("/check")
                    .append(folded == 1 ? "" : "s").append(".");
        }
        if (unchanged > 0) {
            if (counts.length() > 0) counts.append(" ");
            counts.append(unchanged).append(" check")
                    .append(unchanged == 1 ? "" : "s")
                    .append(" unchanged.");
        }

        if (lines.length() == 0) {
            // Nothing itemised: the quiet digest — the count
            // line alone (or, if every entry carried a state
            // outside today's vocabulary, the plain line).
            return counts.length() > 0 ? counts.toString()
                    : "No changes to report.";
        }
        if (counts.length() > 0) {
            lines.append("\n").append(counts);
        }
        return lines.toString();
    }

    /** One entry's line: the label, then the state's words —
     *  the first diff line for a change (the card's own human
     *  line), the honest words for the rest — plus the
     *  quiet-hours suffix when the verdict was held. */
    static String lineFor(BriefingLog.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.label);
        if (WatchCompare.CHANGED.equals(e.state)) {
            sb.append(" changed");
            if (e.firstDiff != null && e.firstDiff.length() > 0) {
                sb.append(": ").append(e.firstDiff);
            } else {
                sb.append(".");
            }
        } else if (WatchCompare.NOT_DONE.equals(e.state)) {
            sb.append(" couldn't check");
            if (e.firstDiff != null && e.firstDiff.length() > 0) {
                sb.append(": ").append(e.firstDiff);
            } else {
                sb.append(".");
            }
        } else if (WatchCompare.BASELINE.equals(e.state)) {
            sb.append(": now watching.");
        } else {
            sb.append(": ").append(e.state).append(".");
        }
        if (e.quietDeferred) {
            sb.append(" (during quiet hours)");
        }
        return sb.toString();
    }
}
