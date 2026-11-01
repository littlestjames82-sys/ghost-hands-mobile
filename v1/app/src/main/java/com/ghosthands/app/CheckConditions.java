package com.ghosthands.app;

/**
 * v3.18 (Everything Train R9 — condition-aware watched
 * checks): should this scheduled check's reminder POST now,
 * wait, or post anyway because it has waited long enough?
 *
 * The whole rule, factored pure like BatteryGuard and
 * QuietHours, because the receiver that applies it is
 * Android's and the rule must be harness-pinned:
 *
 *   - Conditions gate the POST, never browsing. A scheduled
 *     check is notify-and-launch exactly as it has been since
 *     v3.0; a condition can only defer the notification (and
 *     arm a retry). The receiver never starts a run.
 *   - Master off, or a record carrying no conditions, posts —
 *     the pre-v3.18 behaviour, byte for byte.
 *   - Every SET condition must be PROVEN met to post.
 *     Unproven is unmet: a charging state the phone did not
 *     report reads as not charging (the BatteryGuard
 *     precedent — a number the phone did not report is not
 *     one the app may invent — applied to booleans). Same
 *     for Wi-Fi.
 *   - A firing deferred for MAX_DEFER_MS posts anyway: a
 *     check can be late, never lost.
 *
 * Pure: no Android, no I/O.
 */
public final class CheckConditions {

    private CheckConditions() { }

    /** Post the reminder now, conditions met (or none asked). */
    public static final int POST = 0;
    /** Hold the reminder; retry on the cadence. */
    public static final int DEFER = 1;
    /** Post despite unmet conditions — the window ran out. */
    public static final int POST_ANYWAY = 2;
    /** Fire nothing at all: the check is gone or unscheduled
     *  (the receiver's re-judgement, folded in so the whole
     *  firing decision is one pure function). */
    public static final int NOTHING = 3;

    /** The deferral retry cadence: a deferred check re-judges
     *  its conditions every half hour. */
    public static final long RETRY_MS = 30L * 60L * 1000L;

    /** The give-up window: six hours of deferral is enough —
     *  after it, the check posts in whatever state the phone
     *  is in. Late beats lost. */
    public static final long MAX_DEFER_MS = 6L * 60L * 60L * 1000L;

    /**
     * The decision for a live, scheduled check.
     * firstDeferredMs is when this firing's deferral began
     * (0 when it has not deferred yet); nowMs is the firing's
     * own time.
     */
    public static int decide(boolean masterOn,
                             boolean condCharging, boolean condWifi,
                             boolean chargingNow, boolean onWifi,
                             long firstDeferredMs, long nowMs) {
        if (!masterOn || (!condCharging && !condWifi)) {
            return POST;
        }
        boolean met = (!condCharging || chargingNow)
                && (!condWifi || onWifi);
        if (met) return POST;
        long deferredMs = (firstDeferredMs > 0)
                ? nowMs - firstDeferredMs : 0;
        if (deferredMs >= MAX_DEFER_MS) return POST_ANYWAY;
        return DEFER;
    }

    /**
     * The receiver's whole firing judgement: the re-judgement
     * first (a check deleted or unscheduled since its alarm
     * was armed fires NOTHING — conditions never resurrect a
     * dead check), then the condition decision above.
     */
    public static int judgeFiring(boolean exists,
                                  boolean hasSchedule,
                                  boolean masterOn,
                                  boolean condCharging,
                                  boolean condWifi,
                                  boolean chargingNow,
                                  boolean onWifi,
                                  long firstDeferredMs, long nowMs) {
        if (!exists || !hasSchedule) return NOTHING;
        return decide(masterOn, condCharging, condWifi,
                chargingNow, onWifi, firstDeferredMs, nowMs);
    }
}
