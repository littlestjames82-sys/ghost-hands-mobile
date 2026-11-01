package com.ghosthands.app;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Trail — a port of ghost_hands/trail.py: an append-only JSONL
 * provenance log of everything a run did and why. The govern and
 * execute events are written (and flushed to disk) BEFORE the action
 * runs — record-before-execute — so even a crash leaves an honest
 * account of what was about to happen.
 *
 * Event types mirror the Python set relevant on a phone:
 * perceive / decide / govern / approval / execute / result / stop /
 * dialog / download, plus "guard" (v1.1): the agent-loop relevance
 * guard correcting an off-goal proposal before the Governor's turn —
 * a relevance event, not a safety verdict, and "read" (v1.3): an
 * agent-initiated auto-read extraction on arrival (phase "auto"),
 * as distinct from a planner-chosen read action, which is recorded
 * as ordinary execute/result events, and "recover" (v1.6): the
 * agent loop's deterministic reload of a page that failed to load
 * (carries the error page's url and the failed_url it names), and
 * "race" (v2.2): an executed action failed against a page that had
 * already navigated away while the planner was deciding (the stale
 * "element N not on page" shape); the loop re-perceived and
 * re-decided the same step (carries the action, the perceived_url
 * the decision was made against, the current_url found after, and
 * the stale result), and "detour" (v2.5): a result tapped from a
 * search-results page failed to load and its one reload did not
 * save it, so instead of stopping the loop went back to that
 * results page to let the planner pick another (carries the
 * failed_url, the results_url returned to, and detours_used), and
 * "run_summary" (v2.5): the run's closing account of itself —
 * outcome, steps, wall-clock seconds, total prompt tokens, total
 * reused tokens and the reuse percent, recorded next to every
 * "stop" event, and "stale_stash" (v2.7): an executed action
 * failed against a page that had NOT moved — same URL, identical
 * signature — so the loop refreshed the perception and re-decided
 * the step (the stale-stash sibling of "race"; registered here
 * in v2.9 — the loop has recorded it since v2.7 but the type was
 * missing from EVENT_TYPES, and record() refuses unknown types),
 * and "fit" (v2.9): the pre-flight fit degraded a step prompt
 * down its ladder before the engine call (carries the rungs
 * applied and the fitted estimate), and "run" (v3.0): the run's
 * opening record — the goal, the pack, the model — so a trail
 * file is self-describing (the answer card reads its goal from
 * here), and "note" (v3.0): a fact the planner recorded in the
 * scratchpad, with the page stamp it was taken from and the
 * notebook's verdict (added / duplicate / full); the loop
 * records it BEFORE the notebook is touched, and its execute
 * record precedes it, so record-before-execute holds for the
 * new action exactly as for the old ones, and "watch" (v3.2):
 * a watched saved goal's run verdict — the goal's name, the
 * verdict (baseline / no_change / changed / not_done), the
 * baseline timestamp the comparison ran against (since_ms) and,
 * for a change, its diff lines; recorded by the Activity after
 * the run's stop, when the verdict exists, so the answer card
 * (live or rebuilt from this file) can show its watch banner.
 * v3.3 adds three: "route" (a taught route's replay — its start,
 * and each redacted step's pause for the user's own fill; route
 * steps' govern / execute / result records carry the same
 * provenance instead of route events of their own), "screenshot"
 * (a finished run's end-of-run photograph, naming the file in
 * the app's private shots store; recorded by the Activity after
 * the stop, before the answer card is built, so the card can
 * show it), and "assist" (a co-pilot pause: offered at a stuck
 * stop, then tapped — with provenance USER, because the tap was
 * the user's own hand, never the agent's — or declined).
 * v3.12 adds two: "ask" (the planner asked the user a clarifying
 * question — the question text; recorded by the loop BEFORE the
 * question is put to the user, so record-before-execute holds
 * for the new action exactly as for the old ones) and "answer"
 * (the user's reply — the question it answers, the answer text
 * and provenance USER, because the words are the user's own,
 * never the agent's; an unanswered ask records no answer event —
 * the run stops instead, and the stop record says why).
 * v3.15 adds three: "tab_opened" (a browser tab came into being —
 * the tab's 1-based number, its url and how it was opened: the
 * planner's open_tab or the user's + chip), "tab_switched" (the
 * active tab changed — the tab's number and its url) and
 * "tab_closed" (a tab went away — the closed tab's number and
 * url, and the number of the tab active after). Tab numbers in
 * trails are the planner-facing 1-based numbers the tab roster
 * shows; the loop records each tab event BEFORE the hands move,
 * so record-before-execute holds for the tab actions exactly as
 * for the old ones.
 * v3.16 adds "page_watch" (R8): a page watch's CREATION,
 * recorded in the live run's trail when one is running
 * (stamped by:"user" — creation is the user's own tap, never
 * a planner action, and no Governor ever judges it; idle
 * creations have no trail to record in and live in the watch
 * store itself). A page watch's check VERDICTS ride the
 * existing "watch" event instead — goal_name carrying the
 * watch's label, a url field and a page_watch flag added —
 * so the answer card's watch banner reads them exactly as
 * it reads a goal watch's, untouched.
 * v3.18 adds "chain_seed" (R9): a chain step's inheritance —
 * the facts the previous step's done run left for this one,
 * recorded by the loop at run start BEFORE the scratchpad
 * is seeded (record-before-touch, the "note" discipline),
 * carrying the seeded facts, their provenance stamps, the
 * previous step's goal as run (from_goal) and its trail's
 * file name (from_trail). Only a DONE run ever seeds; a
 * failure's findings are never laundered into a later
 * goal's evidence.
 * v3.24 adds "thin_map": a step's perception came back with
 * fewer than ThinMap.THIN_ELEMENT_LIMIT elements on a loaded
 * page, so the loop waited and re-perceived instead of
 * letting the planner step blind (the Oct 25 weather-goal
 * failure). Carries the phase: "retry" (one per attempt, with
 * the attempt number, the backoff waited and the element
 * count seen), "recovered" (an attempt's map filled in),
 * "retry_error" (a re-perception itself failed; the step
 * continues on the last perception), "fallback_read" (the
 * map stayed thin through every attempt, so the loop read
 * the page's text for the step — the extraction itself
 * rides the "read" event with phase "thin_fallback") and
 * "fallback_unread" (still thin, but no loop read happened,
 * with the reason: "auto_read_off", "read_failed", or
 * "text_already_present").
 * v3.25 adds "a11y_perception": a step's DOM perception was
 * unavailable or stayed thin, and the accessibility eyes —
 * the user's own enabled accessibility service, reading
 * this app's WebView subtree only — supplied the step's
 * element map instead. Carries the phase: "used" (the eyes'
 * map replaced a still-thin DOM map; carries the element
 * count supplied and the DOM count it replaced) and
 * "dom_failed" (the DOM perception itself threw and the
 * eyes' map carried the step; carries the failure's
 * message). The elements the eyes supply are read-only:
 * their descriptors carry source "a11y", and the loop's
 * guard refuses element actions against them before the
 * Governor's turn (that refusal rides the existing "guard"
 * event, its reason naming the eyes).
 * v3.26 adds no type. Its DuckDuckGo region recovery rides
 * the standing "recover" event with phase "ddg_region": a
 * fallback search landed on DDG's region-picker page instead
 * of results, and the loop re-issued the search against the
 * region-pinned results URL inside the same step (carries
 * the picker's url, the recovered_url navigated to, and the
 * query re-issued). One recovery per query per run.
 * v3.28 adds no type either. Its redirect-shim settle rides
 * "recover" with two phases: "redirect_shim" (the step's
 * page was a redirect stub — carries the stub's url and the
 * destination it forwards to, empty when the stub's target
 * does not decode to an http(s) address) and
 * "redirect_shim_goto" (the stub never resolved inside the
 * wait, so the loop navigated to the decoded destination
 * itself). Its ddg_region amendment also shows here: a
 * ddg_region event whose recovered_url EQUALS its url is
 * the no-navigation case — the pinned address was already
 * open, so the recovery recorded and noted without
 * navigating.
 * v3.32 adds no type either. It makes this class honest
 * about its own writes: record() tracks whether the latest
 * write actually reached the file (see fileWritten()), and
 * Agent's record-before-execute gate stops a run whose
 * govern / approval / execute records did not land on disk,
 * rather than acting unrecorded. The failure itself stays
 * accounted in memory — record() keeps the event in the
 * in-memory list whether or not the file write succeeded,
 * so the run's own stop record survives to say why it
 * stopped.
 */
public class Trail {

    public static final String[] EVENT_TYPES = {
            "perceive", "decide", "govern", "approval", "execute",
            "result", "stop", "dialog", "download", "guard", "read",
            "recover", "race", "detour", "run_summary", "stale_stash",
            "fit", "run", "note", "watch", "route", "screenshot",
            "assist", "step_screenshot", "ask", "answer",
            "tab_opened", "tab_switched", "tab_closed",
            "page_watch", "chain_seed", "thin_map",
            "a11y_perception",
    };

    private final File path;
    private final List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();

    // v3.32: the durability of the most recent record()'s file
    // write. True until a write fails; a successful write sets
    // it true again (the semantics are per-write, not a latch —
    // see fileWritten()). A null-path (in-memory) trail never
    // attempts a write, so this stays true for it by design.
    private boolean lastWriteOk = true;
    private String lastWriteError = null;

    public Trail(File path) {
        this.path = path;
        if (path != null) {
            File parent = path.getParentFile();
            if (parent != null) parent.mkdirs();
        }
    }

    public File getPath() {
        return path;
    }

    /** True when this trail is backed by a file on disk (as
     *  opposed to a null-path, memory-only trail). */
    public synchronized boolean fileBacked() {
        return path != null;
    }

    /**
     * v3.32: did the most recent record() actually reach its
     * backing store? For a file-backed trail: true iff the
     * latest write opened, wrote, flushed and synced without
     * throwing; false when it failed (lastWriteError() names
     * the failure). The semantics are deliberately per-write:
     * a trail whose storage recovers reports true again after
     * its next successful record, so a caller checking right
     * after the record it cares about learns about THAT
     * record. For a memory-only trail (null path) the
     * in-memory list is the only store there is and every
     * record lands in it, so this is always true — durability
     * has no second store to fail against; Agent's gate
     * enforces only on file-backed trails regardless.
     */
    public synchronized boolean fileWritten() {
        return lastWriteOk;
    }

    /** The most recent write failure's message, or null when
     *  the latest write landed (or none was attempted). */
    public synchronized String lastWriteError() {
        return lastWriteError;
    }

    public synchronized Map<String, Object> record(String type, int step,
                                                   Map<String, Object> fields) {
        boolean known = false;
        for (int i = 0; i < EVENT_TYPES.length; i++) {
            if (EVENT_TYPES[i].equals(type)) { known = true; break; }
        }
        if (!known) {
            throw new IllegalArgumentException("unknown trail event type: " + type);
        }
        Map<String, Object> ev = new LinkedHashMap<String, Object>();
        ev.put("type", type);
        ev.put("step", Long.valueOf(step));
        ev.put("ts", Double.valueOf(System.currentTimeMillis() / 1000.0));
        if (fields != null) ev.putAll(fields);
        events.add(ev);
        if (path != null) {
            try {
                FileOutputStream out = new FileOutputStream(path, true);
                out.write((Json.write(ev) + "\n").getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
                out.close();
                lastWriteOk = true;
                lastWriteError = null;
            } catch (Exception e) {
                // The in-memory copy remains (the event was added
                // above, before the write was attempted); a trail
                // write failure must not crash the run, but it is
                // no longer silent: lastWriteOk / lastWriteError
                // carry it, and Agent's record-before-execute
                // gate checks fileWritten() after the govern,
                // approval and execute records — when it matters,
                // which is before the hands move.
                lastWriteOk = false;
                lastWriteError = e.getMessage() != null
                        ? e.getMessage() : String.valueOf(e);
            }
        }
        return ev;
    }

    /** Convenience: build the fields map from alternating key/value pairs. */
    public static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public synchronized List<Map<String, Object>> events() {
        return new ArrayList<Map<String, Object>>(events);
    }

    /** The whole trail as JSONL text (for the trail screen / export). */
    public synchronized String toJsonLines() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> ev : events) {
            sb.append(Json.write(ev)).append('\n');
        }
        return sb.toString();
    }

    /** Per-run accounting, mirroring trail.py's accounting(). */
    public synchronized Map<String, Object> accounting() {
        long steps = 0;
        long mapChars = 0;
        Map<String, Object> byClass = new LinkedHashMap<String, Object>();
        for (Map<String, Object> ev : events) {
            String t = String.valueOf(ev.get("type"));
            if ("execute".equals(t)) {
                steps++;
            } else if ("perceive".equals(t)) {
                Object mc = ev.get("map_chars");
                if (mc instanceof Number) mapChars += ((Number) mc).longValue();
            } else if ("govern".equals(t)) {
                String cls = String.valueOf(ev.get("classification"));
                Object cur = byClass.get(cls);
                byClass.put(cls, Long.valueOf(cur instanceof Number
                        ? ((Number) cur).longValue() + 1 : 1));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("steps", Long.valueOf(steps));
        out.put("map_chars", Long.valueOf(mapChars));
        out.put("est_tokens", Long.valueOf(Math.round(mapChars / 4.0)));
        out.put("actions_by_class", byClass);
        return out;
    }
}
