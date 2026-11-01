package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v3.18 (Everything Train R9): the deferral book — one small
 * record per condition-deferred check, so a deferred firing
 * knows how long it has already waited (CheckConditions'
 * give-up window is judged against firstDeferredMs) and the
 * launch pass can prune records whose check no longer exists.
 *
 * A deferral is bookkeeping, not a verdict: it is written when
 * a firing defers, refreshed when a retry defers again, and
 * cleared the moment the check posts (on time, on a retry, or
 * by the give-up), when the check is deleted or unscheduled,
 * and when the master switch goes off. The daily alarm itself
 * is re-armed on every firing regardless — the cadence
 * survives a deferred day exactly as it survives a posted one.
 *
 * Keys follow the receiver's own discipline: a goal check is
 * its goal name; a page-watch check is its alarm name
 * ("page:" + url) — the same strings the check alarms carry.
 *
 * Pure: no Android, no I/O (SavedGoals' store pattern).
 */
public final class CheckDeferrals {

    /** The preferences key the deferral book lives under. */
    public static final String PREF_CHECK_DEFERRALS =
            "check_deferrals";

    /** One check's deferral: when this firing's wait began,
     *  when it last re-deferred, and how many times. */
    public static final class Deferral {
        public long firstDeferredMs = 0;
        public long lastDeferredMs = 0;
        public int count = 0;

        public Deferral() { }
    }

    private final Map<String, Deferral> map =
            new LinkedHashMap<String, Deferral>();

    public CheckDeferrals() { }

    /** The deferral for a check key, or null when the check
     *  is not waiting. */
    public Deferral get(String key) {
        return (key == null) ? null : map.get(key);
    }

    /** Record (or refresh) a deferral: the first time starts
     *  the give-up clock; a refresh moves only last/count. */
    public void record(String key, long nowMs) {
        if (key == null || key.length() == 0) return;
        Deferral d = map.get(key);
        if (d == null) {
            d = new Deferral();
            d.firstDeferredMs = nowMs;
            map.put(key, d);
        }
        d.lastDeferredMs = nowMs;
        d.count++;
    }

    /** Forget one check's deferral (it posted, or it is gone). */
    public void clear(String key) {
        if (key != null) map.remove(key);
    }

    public void clearAll() {
        map.clear();
    }

    public int size() { return map.size(); }

    public List<String> keys() {
        return new ArrayList<String>(map.keySet());
    }

    /** Drop every deferral whose check is not in liveKeys
     *  (the launch pass's hygiene: a deleted check's wait
     *  should not outlive it in the book). */
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
        for (Map.Entry<String, Deferral> e : map.entrySet()) {
            Deferral d = e.getValue();
            Map<String, Object> m =
                    new LinkedHashMap<String, Object>();
            m.put("first", Long.valueOf(d.firstDeferredMs));
            m.put("last", Long.valueOf(d.lastDeferredMs));
            m.put("count", Long.valueOf(d.count));
            out.put(e.getKey(), m);
        }
        return Json.write(out);
    }

    /** Tolerant parse: a corrupt value parses to an empty
     *  book (a lost deferral costs a check its place in the
     *  give-up queue, never the check itself); a malformed
     *  entry costs the entry. */
    public static CheckDeferrals parse(String json) {
        CheckDeferrals store = new CheckDeferrals();
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
            Deferral d = new Deferral();
            Object first = m.get("first");
            Object last = m.get("last");
            Object count = m.get("count");
            d.firstDeferredMs = (first instanceof Number)
                    ? ((Number) first).longValue() : 0;
            d.lastDeferredMs = (last instanceof Number)
                    ? ((Number) last).longValue() : 0;
            d.count = (count instanceof Number)
                    ? ((Number) count).intValue() : 0;
            if (d.firstDeferredMs > 0) {
                store.map.put((String) e.getKey(), d);
            }
        }
        return store;
    }
}
