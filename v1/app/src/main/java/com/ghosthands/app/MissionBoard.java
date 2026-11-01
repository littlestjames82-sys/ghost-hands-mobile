package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * v3.20 (R10): the MISSION BOARD's derivation — pure, and a
 * DERIVED VIEW, never a store.
 *
 * Everything on the board — latest verdict, quiet streak, last
 * check — is recomputed from the two watch definitions (the
 * SavedGoals and PageWatches stores) and the RETAINED trails'
 * watch events at the moment the screen opens. Nothing is
 * persisted for the board; a check the retention pruned cannot
 * be shown, and the numbers say so (the streak's N+ marker)
 * instead of pretending the record is complete.
 *
 * The one discrimination that matters: a trail's watch events
 * carry verdicts for BOTH roster kinds. Goal verdicts and
 * page-watch verdicts are told apart by the page_watch flag on
 * the event — a page watch's label can coincide with a goal's
 * name, and neither roster ever reads the other's verdicts.
 *
 * Verdict words are the answer card's own (WatchCompare's
 * states, AnswerCard.watchBanner's lines) so the board, the
 * cards and the spoken briefing never disagree about what a
 * check found.
 */
public final class MissionBoard {

    public static final String KIND_GOAL = "goal";
    public static final String KIND_PAGE = "page";

    /** One retained trail, as the board consumes it: its file
     *  name and its parsed events, oldest event first. */
    public static final class TrailEvents {
        public final String name;
        public final List<Map<String, Object>> events;

        public TrailEvents(String name,
                List<Map<String, Object>> events) {
            this.name = name;
            this.events = events;
        }
    }

    /** One roster row, fully derived. verdictState is one of
     *  WatchCompare's states for the newest COMPLETED check
     *  (baseline / no_change / changed) — or, on the page
     *  store-fallback path only, the record's own lastVerdict,
     *  which may be NOT_DONE. It stays "" when no completed
     *  verdict stands anywhere: the row then renders from the
     *  store baseline record, a couldn't-run note, or an
     *  honest "no checks yet". */
    public static final class Row {
        public String kind = KIND_GOAL;
        public String label = "";
        /** Valid on goal rows: the SavedGoal's name (tap
         *  matching reads watch events by this name). */
        public String goalName = "";
        /** Valid on page rows: the watched URL. */
        public String watchUrl = "";
        /** A COMPLETED verdict (baseline / no_change /
         *  changed) stands in the retained record — as opposed
         *  to only failed checks, or only the store baseline. */
        public boolean hasCompletedVerdict = false;
        public String verdictState = "";
        /** The baseline time the verdict compared against (the
         *  watch event's since_ms). */
        public long verdictSinceMs = 0;
        /** When the verdict itself was recorded (event time). */
        public long verdictTsMs = 0;
        /** The first change line of a changed verdict. */
        public String firstDiff = "";
        /** The verdict arrived during quiet hours. */
        public boolean verdictQuiet = false;
        /** Consecutive no_change checks, newest first, with
         *  not_done checks skipped and a changed or baseline
         *  verdict ending the run of them. */
        public int streak = 0;
        /** The streak reached the oldest supplied trail still
         *  counting — older checks may have been pruned, so the
         *  streak renders "N+", never a false exact figure. */
        public boolean streakOpen = false;
        /** The newest check attempt of any kind (ms). */
        public long lastCheckTsMs = 0;
        /** > 0 when the newest verdict event overall is a
         *  not_done standing in front of older evidence: "last
         *  check couldn't run <date>". */
        public long couldntRunTsMs = 0;
        /** The row renders from the store's baseline record
         *  because no retained trail carries a verdict event. */
        public boolean baselineFromRecord = false;
        public String baselineSummary = "";
        public long baselineTsMs = 0;
        /** No verdict event and no baseline anywhere. */
        public boolean noChecksYet = false;
        /** The newest trail (file name) carrying this item's
         *  watch event — the row's tap target. "" when none. */
        public String evidenceTrail = "";
        /** The next-check line, fully rendered: the schedule
         *  math plus the standing condition suffix, or the
         *  manual wording for an unscheduled watch. */
        public String nextCheck = "";
    }

    private MissionBoard() { }

    /** Derive the whole roster. trailsNewestFirst lists every
     *  retained trail, newest trail first; each trail's events
     *  stay in recorded (oldest-first) order — the walk reads
     *  them newest-first itself. Roster order: watched goals in
     *  store order, then page watches in store order. */
    public static List<Row> derive(SavedGoals goals,
            PageWatches watches, List<TrailEvents> trailsNewestFirst,
            long nowMs, TimeZone tz) {
        List<Row> out = new ArrayList<Row>();
        if (goals != null) {
            for (int i = 0; i < goals.all().size(); i++) {
                SavedGoals.SavedGoal g = goals.all().get(i);
                if (!g.watched) continue;
                out.add(goalRow(g, trailsNewestFirst, nowMs, tz));
            }
        }
        if (watches != null) {
            for (int i = 0; i < watches.all().size(); i++) {
                out.add(pageRow(watches.all().get(i),
                        trailsNewestFirst, nowMs, tz));
            }
        }
        return out;
    }

    // ---- goal rows -------------------------------------------

    private static Row goalRow(SavedGoals.SavedGoal g,
            List<TrailEvents> trails, long nowMs, TimeZone tz) {
        Row row = new Row();
        row.kind = KIND_GOAL;
        row.goalName = g.name;
        row.label = g.name;
        List<Verdict> seq = verdictsFor(trails, g.name, false);
        walk(row, seq, trails);
        if (!row.hasCompletedVerdict) {
            if (g.hasBaseline()) {
                row.baselineFromRecord = true;
                row.baselineSummary = g.baselineSummary;
                row.baselineTsMs = g.baselineTsMs;
            } else {
                row.noChecksYet = true;
            }
        }
        if (g.hasSchedule()) {
            long fire = SavedGoals.nextFireMillis(nowMs,
                    g.schedMinutes, tz);
            row.nextCheck = "next: " + dayClockLabel(fire, nowMs, tz)
                    + SavedGoals.conditionSuffix(g.condCharging,
                            g.condWifi);
        } else {
            row.nextCheck = "manual \u2014 checks when you run it";
        }
        return row;
    }

    // ---- page rows -------------------------------------------

    private static Row pageRow(PageWatches.PageWatch w,
            List<TrailEvents> trails, long nowMs, TimeZone tz) {
        Row row = new Row();
        row.kind = KIND_PAGE;
        row.watchUrl = w.url;
        row.label = PageWatches.label(w.url);
        List<Verdict> seq = verdictsFor(trails, row.label, true);
        walk(row, seq, trails);
        if (seq.isEmpty()) {
            // No retained trail carries this page's verdicts:
            // the store record is the surviving evidence (it
            // mirrors the last check by construction).
            if (w.lastVerdict != null
                    && w.lastVerdict.length() > 0) {
                row.verdictState = w.lastVerdict;
                row.hasCompletedVerdict = !WatchCompare.NOT_DONE
                        .equals(w.lastVerdict);
                row.verdictTsMs = w.lastCheckTsMs;
                row.lastCheckTsMs = w.lastCheckTsMs;
                row.verdictSinceMs = w.baselineTsMs;
                row.firstDiff = (w.lastDiff != null
                        && !w.lastDiff.isEmpty()
                        && w.lastDiff.get(0) != null)
                        ? w.lastDiff.get(0) : "";
                row.baselineTsMs = w.baselineTsMs;
            } else if (w.hasBaseline()) {
                row.baselineFromRecord = true;
                row.baselineTsMs = w.baselineTsMs;
            } else {
                row.noChecksYet = true;
            }
        } else if (!row.hasCompletedVerdict && w.hasBaseline()) {
            // Verdict events exist but none completed (every
            // retained check failed): the creation baseline is
            // still the standing record behind them.
            row.baselineFromRecord = true;
            row.baselineTsMs = w.baselineTsMs;
        }
        if (w.hasSchedule()) {
            long fire = SavedGoals.nextFireMillis(nowMs,
                    w.schedMinutes, tz);
            row.nextCheck = "next: " + dayClockLabel(fire, nowMs, tz)
                    + SavedGoals.conditionSuffix(w.condCharging,
                            w.condWifi);
        } else {
            row.nextCheck = "manual \u2014 checks when you run it";
        }
        return row;
    }

    // ---- the verdict walk -------------------------------------

    /** One watch event, distilled for the board: its state,
     *  when it was recorded, what it compared against, and
     *  which retained trail (index into the supplied list) it
     *  rode in on. */
    private static final class Verdict {
        String state = "";
        long tsMs = 0;
        long sinceMs = 0;
        String firstDiff = "";
        boolean quiet = false;
        int trailIndex = -1;
        String trailName = "";
    }

    /** The watch events for one roster item, newest first.
     *  pageWatch selects the discrimination: page rows take
     *  ONLY events flagged page_watch, goal rows ONLY events
     *  without the flag — the flag, never the label, decides. */
    private static List<Verdict> verdictsFor(
            List<TrailEvents> trails, String label,
            boolean pageWatch) {
        List<Verdict> out = new ArrayList<Verdict>();
        if (trails == null || label == null) return out;
        for (int ti = 0; ti < trails.size(); ti++) {
            TrailEvents t = trails.get(ti);
            if (t == null || t.events == null) continue;
            for (int ei = t.events.size() - 1; ei >= 0; ei--) {
                Map<String, Object> e = t.events.get(ei);
                if (e == null) continue;
                if (!"watch".equals(str(e.get("type")))) continue;
                if (!label.equals(str(e.get("goal_name")))) {
                    continue;
                }
                boolean flagged = Boolean.TRUE.equals(
                        e.get("page_watch"));
                if (flagged != pageWatch) continue;
                Verdict v = new Verdict();
                v.state = str(e.get("verdict"));
                Object ts = e.get("ts");
                // Trail times are SECONDS since epoch; the
                // board speaks milliseconds like every store.
                v.tsMs = (ts instanceof Number)
                        ? ((Number) ts).longValue() * 1000L : 0;
                Object since = e.get("since_ms");
                v.sinceMs = (since instanceof Number)
                        ? ((Number) since).longValue() : 0;
                Object diff = e.get("diff");
                if (diff instanceof List) {
                    List<?> dl = (List<?>) diff;
                    if (!dl.isEmpty() && dl.get(0) != null) {
                        v.firstDiff = String.valueOf(dl.get(0));
                    }
                }
                v.quiet = Boolean.TRUE.equals(e.get("quiet"));
                v.trailIndex = ti;
                v.trailName = t.name;
                out.add(v);
            }
        }
        return out;
    }

    /** The shared newest-first walk over one item's verdicts:
     *  the newest COMPLETED verdict (baseline / no_change /
     *  changed) fills the verdict fields; a newest-of-all
     *  not_done surfaces as "couldn't run" WITHOUT hiding the
     *  completed verdict behind it; the quiet streak counts
     *  consecutive no_change checks with not_done checks
     *  skipped, and ends at a changed or baseline verdict —
     *  or stays open ("N+") when it runs off the oldest
     *  supplied trail still counting. */
    private static void walk(Row row, List<Verdict> seq,
            List<TrailEvents> trails) {
        if (seq == null || seq.isEmpty()) return;
        row.evidenceTrail = seq.get(0).trailName;
        row.lastCheckTsMs = seq.get(0).tsMs;
        if (WatchCompare.NOT_DONE.equals(seq.get(0).state)) {
            row.couldntRunTsMs = seq.get(0).tsMs;
        }
        boolean exhausted = true;
        for (int i = 0; i < seq.size(); i++) {
            Verdict v = seq.get(i);
            if (WatchCompare.NOT_DONE.equals(v.state)) {
                continue;   // skipped, never counted, never ends it
            }
            if (!row.hasCompletedVerdict) {
                row.hasCompletedVerdict = true;
                row.verdictState = v.state;
                row.verdictSinceMs = v.sinceMs;
                row.verdictTsMs = v.tsMs;
                row.firstDiff = v.firstDiff;
                row.verdictQuiet = v.quiet;
            }
            if (WatchCompare.NO_CHANGE.equals(v.state)) {
                row.streak++;
                continue;
            }
            // baseline starts no streak; changed ends it at 0.
            exhausted = false;
            break;
        }
        if (exhausted && row.streak > 0 && trails != null
                && !trails.isEmpty()) {
            Verdict oldest = seq.get(seq.size() - 1);
            row.streakOpen = oldest.trailIndex == trails.size() - 1;
        }
    }

    // ---- labels ------------------------------------------------

    /** "today 07:30" / "tomorrow 07:30" / "Mon Oct 27 07:30" —
     *  the next-fire label in the board's clock words. */
    public static String dayClockLabel(long fireMs, long nowMs,
            TimeZone tz) {
        String clock = SavedGoals.timeLabel(
                minutesOfDay(fireMs, tz));
        if (sameDay(fireMs, nowMs, tz)) return "today " + clock;
        if (sameDay(fireMs, nowMs + 86400000L, tz)) {
            return "tomorrow " + clock;
        }
        return AnswerCard.watchTimeLabel(fireMs);
    }

    private static int minutesOfDay(long ms, TimeZone tz) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + c.get(java.util.Calendar.MINUTE);
    }

    private static boolean sameDay(long a, long b, TimeZone tz) {
        java.util.Calendar ca = java.util.Calendar.getInstance(tz);
        ca.setTimeInMillis(a);
        java.util.Calendar cb = java.util.Calendar.getInstance(tz);
        cb.setTimeInMillis(b);
        return ca.get(java.util.Calendar.YEAR)
                == cb.get(java.util.Calendar.YEAR)
                && ca.get(java.util.Calendar.DAY_OF_YEAR)
                == cb.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
