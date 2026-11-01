package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.18 (Everything Train R9 — the morning briefing): the
 * log of watched-check verdicts the briefing reads from.
 *
 * An entry is appended at the moment a verdict is recorded —
 * in the Activity, beside the "watch" trail event, from the
 * values already in hand: the check's label, goal or page,
 * the verdict's state (WatchCompare's vocabulary), its first
 * diff line (the human line the card itself shows), quiet-
 * deferred or not, and when. The briefing itself reads and
 * marks; it never re-derives a verdict — the trail stays the
 * record, this log is the briefing's cache of it.
 *
 * Exactly-once delivery is two stamps, both persisted: the
 * last briefed DAY (owned by the Activity's launch pass) and
 * each entry's own briefed flag. A day with no unbriefed
 * entries still advances the day stamp (set by the caller),
 * so a phone opened at noon is not "briefed" for checks that
 * land at three.
 *
 * The cap is 30 entries, oldest dropped: a backlog is a
 * summary, not an archive (the trails hold the full record).
 *
 * Pure: no Android, no I/O.
 */
public final class BriefingLog {

    /** The preferences key the briefing log lives under. */
    public static final String PREF_BRIEFING_LOG = "briefing_log";

    /** The log's cap: beyond a month of checks, the oldest
     *  verdicts are history the trails already keep. */
    public static final int MAX_ENTRIES = 30;

    /** The two kinds of watched check (WatchCompare states ride
     *  the same vocabulary for both). */
    public static final String KIND_GOAL = "goal";
    public static final String KIND_PAGE = "page";

    /** One verdict the briefing may read out. */
    public static final class Entry {
        public String label = "";
        public String kind = KIND_GOAL;
        public String state = "";
        public String firstDiff = "";
        public long tsMs = 0;
        public boolean quietDeferred = false;
        public boolean briefed = false;

        public Entry() { }

        public Entry(String label, String kind, String state,
                     String firstDiff, long tsMs,
                     boolean quietDeferred) {
            this.label = (label == null) ? "" : label;
            this.kind = (kind == null) ? KIND_GOAL : kind;
            this.state = (state == null) ? "" : state;
            this.firstDiff = (firstDiff == null) ? "" : firstDiff;
            this.tsMs = tsMs;
            this.quietDeferred = quietDeferred;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m =
                    new LinkedHashMap<String, Object>();
            m.put("label", label);
            m.put("kind", kind);
            m.put("state", state);
            m.put("diff", firstDiff);
            m.put("ts", Long.valueOf(tsMs));
            m.put("quiet", Boolean.valueOf(quietDeferred));
            m.put("briefed", Boolean.valueOf(briefed));
            return m;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();

    public BriefingLog() { }

    public int size() { return entries.size(); }

    public List<Entry> all() {
        return new ArrayList<Entry>(entries);
    }

    /** Append a verdict; the cap drops the oldest. */
    public void append(Entry e) {
        if (e == null) return;
        entries.add(e);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    /** The entries not yet read out, oldest first. */
    public List<Entry> unbriefed() {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).briefed) out.add(entries.get(i));
        }
        return out;
    }

    /** Mark the currently unbriefed entries read (the delivery
     *  itself — banner shown — is the read; see the class doc). */
    public void markBriefed() {
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).briefed = true;
        }
    }

    public String serialize() {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < entries.size(); i++) {
            out.add(entries.get(i).toMap());
        }
        return Json.write(out);
    }

    /** Tolerant parse: a corrupt value parses to an empty log;
     *  a malformed entry costs the entry. Unknown kinds ride
     *  as goal (the digest labels by kind alone). */
    public static BriefingLog parse(String json) {
        BriefingLog log = new BriefingLog();
        if (json == null || json.trim().length() == 0) {
            return log;
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Exception e) {
            return log;
        }
        if (!(parsed instanceof List)) return log;
        List<?> arr = (List<?>) parsed;
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            Entry e = new Entry();
            e.label = strVal(m.get("label"));
            e.kind = KIND_PAGE.equals(strVal(m.get("kind")))
                    ? KIND_PAGE : KIND_GOAL;
            e.state = strVal(m.get("state"));
            e.firstDiff = strVal(m.get("diff"));
            Object ts = m.get("ts");
            e.tsMs = (ts instanceof Number)
                    ? ((Number) ts).longValue() : 0;
            e.quietDeferred = Boolean.TRUE.equals(m.get("quiet"));
            e.briefed = Boolean.TRUE.equals(m.get("briefed"));
            log.append(e);
        }
        return log;
    }

    private static String strVal(Object o) {
        return (o instanceof String) ? (String) o : "";
    }
}
