package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v3.27 (Everything Train R13 — unattended mode): the
 * firing book — one small record per unattended goal, so
 * what happened at each firing is written down somewhere
 * that survives the firing: when the goal was opted in,
 * when it last fired, what that firing did, and which
 * missed occurrence has already been reported.
 *
 * Why this book exists (spike §7): silence must never
 * impersonate a NO_CHANGE. A firing the receiver judged
 * records its outcome here (started / done / failed /
 * deferred / posted for a missing grant / posted at the
 * give-up). A firing that never reached the receiver —
 * the alarm chain broken by a force-stop or a dead
 * reboot window — records nothing, and the launch pass
 * judges exactly that absence (Unattended.missedFiring
 * against enabledMs) and puts it in the morning briefing
 * instead of letting the check vanish quietly.
 *
 * The book is bookkeeping, not a verdict: verdicts live
 * in trails and the briefing log; this is the firing
 * ledger those are checked against.
 *
 * Pure: no Android, no I/O (CheckDeferrals' store pattern).
 */
public final class UnattendedLog {

    /** The preferences key the firing book lives under. */
    public static final String PREF_UNATTENDED_LOG =
            "unattended_log";

    /** The firing outcomes, as recorded. "started" is the
     *  receiver's word (the service accepted the run); the
     *  service overwrites it with the run's own ending. */
    public static final String OUT_STARTED = "started";
    public static final String OUT_DONE = "done";
    public static final String OUT_FAILED = "failed";
    public static final String OUT_DEFERRED = "deferred";
    public static final String OUT_POSTED_GRANT = "posted_grant";
    public static final String OUT_POSTED_GIVEUP = "posted_giveup";

    /** One goal's firing record. */
    public static final class Record {
        /** When the user opted this goal in (the missed-
         *  firing judgement's epoch: occurrences at or
         *  before it are not this feature's to answer for). */
        public long enabledMs = 0;
        /** When the goal last fired (any outcome). */
        public long lastFiringMs = 0;
        /** The last firing's outcome (an OUT_* value). */
        public String lastOutcome = "";
        /** The last run's trail file name ("" when the
         *  last firing produced no run). */
        public String lastTrailName = "";
        /** The missed occurrence already reported to the
         *  briefing (the dedupe stamp: a broken chain is
         *  reported once per missed firing, not at every
         *  launch until it heals). */
        public long lastMissedOccurrenceMs = 0;

        public Record() { }
    }

    private final Map<String, Record> map =
            new LinkedHashMap<String, Record>();

    public UnattendedLog() { }

    /** The record for a goal name, or null when the goal
     *  has never been opted in or fired. */
    public Record get(String name) {
        return (name == null) ? null : map.get(name);
    }

    private Record ensure(String name) {
        Record r = map.get(name);
        if (r == null) {
            r = new Record();
            map.put(name, r);
        }
        return r;
    }

    /** Stamp the opt-in moment (the goal row's toggle calls
     *  this when unattended is switched ON). Re-opting-in
     *  after an opt-out restarts the epoch: occurrences
     *  from the off period are not missed firings. */
    public void stampEnabled(String name, long nowMs) {
        if (name == null || name.length() == 0) return;
        ensure(name).enabledMs = nowMs;
    }

    /** Record a firing's outcome: the firing time, what it
     *  did, and the trail it produced ("" when none). */
    public void record(String name, String outcome, long nowMs,
                       String trailName) {
        if (name == null || name.length() == 0) return;
        Record r = ensure(name);
        r.lastFiringMs = nowMs;
        r.lastOutcome = (outcome == null) ? "" : outcome;
        r.lastTrailName = (trailName == null) ? "" : trailName;
    }

    /** Mark a missed occurrence reported (the launch pass's
     *  dedupe: one briefing entry per missed firing). */
    public void recordMissed(String name, long occurrenceMs) {
        if (name == null || name.length() == 0) return;
        ensure(name).lastMissedOccurrenceMs = occurrenceMs;
    }

    public int size() { return map.size(); }

    public List<String> keys() {
        return new ArrayList<String>(map.keySet());
    }

    /** Drop every record whose goal is not in liveKeys
     *  (the launch pass's hygiene, the deferral book's
     *  precedent: a deleted goal's ledger should not
     *  outlive it). */
    public void prune(Set<String> liveKeys) {
        List<String> dead = new ArrayList<String>();
        for (String key : map.keySet()) {
            if (liveKeys == null || !liveKeys.contains(key)) {
                dead.add(key);
            }
        }
        for (int i = 0; i < dead.size(); i++) {
            map.remove(dead.get(i));
        }
    }

    public String serialize() {
        Map<String, Object> out =
                new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Record> e : map.entrySet()) {
            Record r = e.getValue();
            Map<String, Object> m =
                    new LinkedHashMap<String, Object>();
            m.put("enabled", Long.valueOf(r.enabledMs));
            m.put("fired", Long.valueOf(r.lastFiringMs));
            m.put("outcome", r.lastOutcome);
            m.put("trail", r.lastTrailName);
            m.put("missed", Long.valueOf(
                    r.lastMissedOccurrenceMs));
            out.put(e.getKey(), m);
        }
        return Json.write(out);
    }

    /** Tolerant parse: a corrupt value parses to an empty
     *  book (a lost ledger costs the missed-firing report,
     *  never the checks themselves); a malformed entry
     *  costs the entry. */
    public static UnattendedLog parse(String json) {
        UnattendedLog store = new UnattendedLog();
        if (json == null || json.trim().length() == 0) {
            return store;
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Exception e) {
            return store;
        }
        if (!(parsed instanceof Map)) return store;
        Map<?, ?> top = (Map<?, ?>) parsed;
        for (Map.Entry<?, ?> e : top.entrySet()) {
            if (!(e.getKey() instanceof String)
                    || !(e.getValue() instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) e.getValue();
            Record r = new Record();
            r.enabledMs = numVal(m.get("enabled"));
            r.lastFiringMs = numVal(m.get("fired"));
            Object outcome = m.get("outcome");
            r.lastOutcome = (outcome instanceof String)
                    ? (String) outcome : "";
            Object trail = m.get("trail");
            r.lastTrailName = (trail instanceof String)
                    ? (String) trail : "";
            r.lastMissedOccurrenceMs = numVal(m.get("missed"));
            store.map.put((String) e.getKey(), r);
        }
        return store;
    }

    private static long numVal(Object o) {
        return (o instanceof Number)
                ? ((Number) o).longValue() : 0;
    }
}
