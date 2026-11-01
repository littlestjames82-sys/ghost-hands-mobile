package com.ghosthands.app;

/**
 * v3.27 (Everything Train R13 — unattended mode): the pure
 * decisions behind a watched check that runs itself.
 *
 * The design authority is R13_SPIKE_REPORT.md (§8, the
 * GO-IF list). Its shape, in one paragraph: a goal the user
 * has opted in (SavedGoal.unattended — per goal, off by
 * default, set up by hand) may, at its daily firing, RUN
 * instead of merely posting — but only while both one-time
 * system grants are in hand (exact alarms; the battery-
 * optimization exemption), only under the charging-first
 * condition discipline below, always under the readonly
 * pack, always inside the wall-clock budget, and always
 * degrading to exactly today's notify-and-launch behaviour
 * when any precondition is absent. Nothing here browses;
 * this class only judges, the way CheckConditions judges
 * for attended checks.
 *
 * The differences from CheckConditions are deliberate and
 * spike-mandated:
 *   - Conditions gate the RUN, not just the post. An unmet
 *     condition defers the RUN (the R9 retry cadence and
 *     deferral book are reused whole); past the give-up
 *     window the check falls back to the morning
 *     notification — it never runs unconditioned late
 *     (spike §8.2: "falls back to the morning notification",
 *     where R9's attended rule posts anyway).
 *   - CHARGING IS STRUCTURAL, not a per-goal option: an
 *     unattended firing requires the phone to be charging,
 *     whatever the goal's R9 conditions say (the spike's
 *     charging-first default, taken at its most
 *     conservative reading — while charging, Doze never
 *     engages and standby-bucket limits do not apply, so
 *     the run's two hardest walls simply leave the room).
 *     The goal's Wi-Fi condition rides on top when set.
 *     The R9 master switch does NOT gate any of this: that
 *     switch governs reminder deferral for attended checks;
 *     an unattended run's conditions are a property of
 *     unattended mode itself.
 *   - A missing grant is a POST, never a crash and never a
 *     silent skip: the check notifies exactly as it did
 *     through v3.26, and missingGrant() names which grant
 *     is absent so the UI can say so (spike §8.1).
 *
 * Pure: no Android, no I/O. The Android side (CheckReceiver,
 * Alerts, UnattendedService) reads the phone's state and
 * applies these judgements.
 */
public final class Unattended {

    private Unattended() { }

    // ---- firing decisions (judgeFiring) -------------------

    /** Fire nothing: the check is gone or unscheduled. */
    public static final int NOTHING = 0;
    /** Notify-and-launch — today's behaviour: the goal is
     *  not opted in, a grant is missing, or the give-up
     *  window ran out on a deferred run. */
    public static final int POST = 1;
    /** Hold the run; re-judge on the R9 retry cadence. */
    public static final int DEFER = 2;
    /** Start the unattended run now. */
    public static final int RUN = 3;

    // ---- missing grants (missingGrant) --------------------

    public static final int GRANTS_OK = 0;
    public static final int GRANT_EXACT_MISSING = 1;
    public static final int GRANT_BATTERY_MISSING = 2;
    public static final int GRANTS_BOTH_MISSING = 3;

    // ---- arm modes (armMode) -------------------------------

    /** The standing setAndAllowWhileIdle arming. */
    public static final int ARM_INEXACT = 0;
    /** setExactAndAllowWhileIdle — only for an opted-in goal
     *  while the exact-alarm grant is in hand at arm time. */
    public static final int ARM_EXACT = 1;

    /** The hard wall-clock budget of one unattended run
     *  (spike §8.4: at most ten minutes). The wake lock is
     *  held for this plus WAKELOCK_SLACK_MS and no longer. */
    public static final long MAX_RUN_MS = 10L * 60L * 1000L;

    /** The wake lock's margin over the run budget: enough
     *  for the finish path (verdict, notifications, store
     *  writes) after the loop's own clock stops it. */
    public static final long WAKELOCK_SLACK_MS = 60L * 1000L;

    /** The missed-firing grace: an occurrence this close
     *  after the last recorded firing IS that firing (alarm
     *  delivery and the receiver's bookkeeping are not
     *  instantaneous). */
    public static final long MISSED_GRACE_MS = 5L * 60L * 1000L;

    /** Which grant is missing, if any. Both grants are
     *  re-judged at arm time and at fire time (spike §8.1:
     *  both are revocable by the user at any time). */
    public static int missingGrant(boolean exactGranted,
                                   boolean batteryExempt) {
        if (exactGranted && batteryExempt) return GRANTS_OK;
        if (!exactGranted && !batteryExempt) {
            return GRANTS_BOTH_MISSING;
        }
        return exactGranted ? GRANT_BATTERY_MISSING
                : GRANT_EXACT_MISSING;
    }

    public static boolean grantsOk(boolean exactGranted,
                                   boolean batteryExempt) {
        return missingGrant(exactGranted, batteryExempt)
                == GRANTS_OK;
    }

    /** The plain name of what is missing ("" when nothing
     *  is): the words the Settings status line and the goal
     *  row speak, so the user is told exactly which Settings
     *  trip an unattended check is waiting on. */
    public static String missingText(boolean exactGranted,
                                     boolean batteryExempt) {
        int m = missingGrant(exactGranted, batteryExempt);
        if (m == GRANTS_OK) return "";
        if (m == GRANT_EXACT_MISSING) {
            return "the Alarms & reminders permission";
        }
        if (m == GRANT_BATTERY_MISSING) {
            return "the battery exemption (Unrestricted)";
        }
        return "the Alarms & reminders permission and the "
                + "battery exemption (Unrestricted)";
    }

    /** How a check's alarm is armed: exact only for an
     *  opted-in goal while the grant is in hand at arm
     *  time — an exact arming attempted without the grant
     *  throws on modern Android, so the mode is judged
     *  here, never assumed (spike §8.1: re-check at arm
     *  time and degrade, not crash). */
    public static int armMode(boolean unattendedOn,
                              boolean exactGranted) {
        return (unattendedOn && exactGranted)
                ? ARM_EXACT : ARM_INEXACT;
    }

    /**
     * The whole firing judgement for a saved goal's check.
     *
     * exists / hasSchedule — the receiver's re-judgement
     * (a deleted or unscheduled check fires NOTHING;
     * unattended never resurrects a dead check).
     * unattendedOn — the goal's opt-in.
     * exactGranted / batteryExempt — the phone's grant
     * state, read at fire time.
     * condWifi — the goal's own R9 Wi-Fi condition (its
     * charging condition is NOT consulted: charging is
     * structural here, see the class doc).
     * chargingNow / onWifiNow — the phone's state, read at
     * fire time; unproven reads as unmet, exactly as
     * CheckConditions judges.
     * firstDeferredMs — when this firing's deferral began
     * (0 when it has not deferred yet), judged against
     * CheckConditions.MAX_DEFER_MS, the R9 give-up window.
     */
    public static int judgeFiring(boolean exists,
                                  boolean hasSchedule,
                                  boolean unattendedOn,
                                  boolean exactGranted,
                                  boolean batteryExempt,
                                  boolean condWifi,
                                  boolean chargingNow,
                                  boolean onWifiNow,
                                  long firstDeferredMs,
                                  long nowMs) {
        if (!exists || !hasSchedule) return NOTHING;
        if (!unattendedOn) return POST;
        if (!grantsOk(exactGranted, batteryExempt)) {
            return POST;
        }
        boolean met = chargingNow && (!condWifi || onWifiNow);
        if (met) return RUN;
        long deferredMs = (firstDeferredMs > 0)
                ? nowMs - firstDeferredMs : 0;
        if (deferredMs >= CheckConditions.MAX_DEFER_MS) {
            return POST;
        }
        return DEFER;
    }

    /** The unattended run's wall-clock budget, judged: at
     *  or past MAX_RUN_MS the run must stop (the headless
     *  Ui's stopRequested consults this beside the widget's
     *  stop latch). A run whose start was never stamped
     *  (startMs 0) is always over — a budget judged against
     *  no start is a stopped run, never an unbounded one. */
    public static boolean budgetStop(long startMs, long nowMs) {
        if (startMs <= 0) return true;
        return nowMs - startMs >= MAX_RUN_MS;
    }

    /**
     * The most recent daily occurrence of schedMinutes at
     * or before nowMs, in the given timezone (0 when the
     * record carries no schedule). Computed by walking
     * SavedGoals.nextFireMillis forward from two days back
     * — at most three steps — so DST's 23/25-hour days are
     * the schedule math's own, not a fixed 24h subtraction.
     */
    public static long lastOccurrenceMs(long nowMs,
                                        int schedMinutes,
                                        java.util.TimeZone tz) {
        if (schedMinutes < 0 || schedMinutes >= 24 * 60) {
            return 0;
        }
        long occ = SavedGoals.nextFireMillis(
                nowMs - 48L * 60L * 60L * 1000L, schedMinutes, tz);
        while (true) {
            long next = SavedGoals.nextFireMillis(
                    occ, schedMinutes, tz);
            if (next > nowMs) return occ;
            occ = next;
        }
    }

    /**
     * Did a firing go missing? Judged at launch (and only
     * there — the launch pass is the one place a broken
     * alarm chain can be FOUND): an occurrence exists, the
     * goal was opted in before it, and no firing was
     * recorded for it (the last recorded firing is older
     * than the occurrence, beyond the delivery grace).
     * The chain breaks when alarms die and nothing re-arms
     * them — a reboot before the boot receiver existed in
     * the user's habits, or a force-stop, which cancels a
     * scheduled app's alarms outright (spike §7). A goal
     * opted in AFTER the occurrence judges nothing: its
     * first firing is still ahead of it.
     */
    public static boolean missedFiring(long enabledMs,
                                       long lastFiringMs,
                                       long lastOccurrenceMs) {
        if (enabledMs <= 0 || lastOccurrenceMs <= 0) {
            return false;
        }
        if (lastOccurrenceMs <= enabledMs) return false;
        return lastOccurrenceMs > lastFiringMs + MISSED_GRACE_MS;
    }

    /** The Settings status line for the two grants (the
     *  A11yEyes.statusLine pattern): what state the phone
     *  is in, and what an unattended check does in it. */
    public static String grantStatusLine(boolean exactGranted,
                                         boolean batteryExempt) {
        if (grantsOk(exactGranted, batteryExempt)) {
            return "Unattended ready: exact alarms allowed "
                    + "and battery use is Unrestricted. A "
                    + "check you mark unattended runs itself "
                    + "at its time (charging, read-only, "
                    + "10-minute budget) instead of only "
                    + "reminding you.";
        }
        return "Unattended not ready: missing "
                + missingText(exactGranted, batteryExempt)
                + ". Until both are given, every check stays "
                + "notify-and-launch, exactly as before.";
    }

    /** One goal's unattended line for its row in Goals:
     *  what this goal will do at its next firing, in the
     *  phone's current grant state. */
    public static String goalLine(boolean unattendedOn,
                                  boolean exactGranted,
                                  boolean batteryExempt) {
        if (!unattendedOn) return "";
        if (grantsOk(exactGranted, batteryExempt)) {
            return "unattended: runs itself at its time "
                    + "(charging, read-only)";
        }
        return "unattended: waiting on "
                + missingText(exactGranted, batteryExempt)
                + " — reminds only until then";
    }
}
