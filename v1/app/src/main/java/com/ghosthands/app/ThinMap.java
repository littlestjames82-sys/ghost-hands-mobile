package com.ghosthands.app;

/**
 * v3.24 (the weather fix — thin element maps): the pure policy
 * for a page whose element map comes back empty or nearly
 * empty on a page that has finished loading.
 *
 * The Oct 25, 2026 field failure this answers: the run opened
 * the right forecast result and the destination page's map
 * came back empty — the planner then stepped blind (taps at
 * elements that did not exist) until the stuck detector
 * stopped the run. The map was empty because the page was
 * still building itself, not because it had nothing on it:
 * heavy script-driven pages can answer the eyes' first probe
 * before their interactive surface exists.
 *
 * The loop's response, driven by this policy: when a step's
 * perception is thin (fewer than THIN_ELEMENT_LIMIT elements)
 * the loop waits with a short backoff and re-perceives, up to
 * MAX_ATTEMPTS times, recording every attempt in the trail
 * (type "thin_map"). A map that fills in during the waits is
 * an ordinary perception and the step proceeds untouched. A
 * map that is STILL thin after the last attempt is treated as
 * a page the eyes cannot see into: the loop reads the page's
 * text itself (recorded as a read with phase "thin_fallback",
 * the chunk riding the step's history) and the planner
 * decides this step with text in front of it instead of a
 * blind map. The extraction is gated on the run's auto-read
 * setting: a run with loop-reading off gets the retries and
 * a history note, never a loop read.
 *
 * This class is the policy only — thresholds, the attempt
 * cap, the backoff schedule. The waiting, the re-perceiving
 * and the fallback read live in the agent loop, which owns
 * the hands; nothing here sleeps, reads a page, or records.
 */
public final class ThinMap {

    private ThinMap() { }

    /** Fewer actionable elements than this and the map is
     *  thin: too little for the planner to act on — a tap
     *  target or two and nothing to choose between. The
     *  perception's element list carries only interactive
     *  elements by construction, so the count is the count
     *  of things the planner could actually do. */
    public static final int THIN_ELEMENT_LIMIT = 3;

    /** Re-perceptions one step may spend on a thin map
     *  before the fallback read takes the step. Bounded so
     *  a permanently invisible page (a canvas app, a page
     *  that fights injection) costs seconds, not steps. */
    public static final int MAX_ATTEMPTS = 3;

    /** The first backoff, in milliseconds; each later
     *  attempt doubles it, capped at MAX_BACKOFF_MS. The
     *  full schedule is 500 / 1000 / 2000 — 3.5 s of
     *  patience per thin step at most. */
    public static final int BASE_BACKOFF_MS = 500;
    public static final int MAX_BACKOFF_MS = 2000;

    /** Is this perception's map thin? A null perception
     *  (never produced by the loop, which stops on a
     *  perceive failure first) reads as thin. */
    public static boolean isThin(Eyes.Perception p) {
        if (p == null || p.elements == null) return true;
        return p.elements.size() < THIN_ELEMENT_LIMIT;
    }

    /** The wait before re-perception attempt `attempt`
     *  (1-based): the base, doubling per attempt, capped.
     *  Attempts below 1 clamp to the first backoff. */
    public static int backoffMs(int attempt) {
        long ms = BASE_BACKOFF_MS;
        for (int i = 1; i < attempt && ms < MAX_BACKOFF_MS; i++) {
            ms *= 2;
        }
        if (ms > MAX_BACKOFF_MS) ms = MAX_BACKOFF_MS;
        return (int) ms;
    }

    /** Test seam (the RunControl slice-latch precedent): the
     *  host harness divides the SLEPT backoff by this so its
     *  many scripted runs do not pay real seconds on every
     *  thin fixture page. The schedule itself (backoffMs,
     *  and the backoff_ms the trail records) is never
     *  scaled. The app never touches this; it stays 1 on
     *  device. */
    public static volatile long BACKOFF_DIVISOR = 1L;

    /** The backoff actually slept before re-perception
     *  attempt `attempt`: the pure schedule through the
     *  seam. */
    public static int backoffSleepMs(int attempt) {
        long d = BACKOFF_DIVISOR;
        if (d < 1L) d = 1L;
        long ms = backoffMs(attempt) / d;
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms;
    }
}
