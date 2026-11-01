package com.ghosthands.app;

/**
 * v3.27 (Everything Train R13 — unattended mode): the
 * Agent.Ui nobody sits in front of.
 *
 * An unattended run's whole safety story is that nobody
 * is present, so every member of the screen contract is
 * answered the way the app already answers an absent
 * user, in its strictest form:
 *
 *   - approve() is false, immediately and always. The
 *     Governor's own discipline is that silence is never
 *     consent; here there is not even silence to wait
 *     out — an ASK verdict with nobody present is a deny
 *     and an honest stop (spike §8.4). Under the readonly
 *     pack the loop forces on unattended runs, writes and
 *     consequential actions are DENIED before an ask can
 *     even form; this answer is the belt to that
 *     suspenders, and it is also what makes a headless run
 *     under any other pack fail closed instead of
 *     proceeding unapproved.
 *   - askUser keeps the interface's default: null — no
 *     answer — and the loop stops rather than guess.
 *   - awaitUserTap is null and awaitUserResume false:
 *     a co-pilot pause nobody can see would be a hang,
 *     so none is offered.
 *   - foreground() is false: the run is off-screen by
 *     definition, so every foreground-only behaviour
 *     (co-pilot offers, needs-me notifications, spoken
 *     finishes) stands down by the loop's own rules.
 *   - stopRequested() is the run's two stopwatches: the
 *     process-wide stop latch (RunControl — the widget's
 *     Stop button and the in-app Stop both land there,
 *     so an unattended run is stoppable from the home
 *     screen exactly as an attended one is) and the
 *     wall-clock budget (Unattended.budgetStop — at ten
 *     minutes the run stops, whatever step it is on).
 *     The loop polls this beside every step and wait, so
 *     either watch stops the run at the next poll, never
 *     mid-action.
 *   - feed() and status() go nowhere: there is no screen.
 *     The trail is the run's record; the morning briefing
 *     is its report.
 *
 * The clock is a seam (the ThinMap.BACKOFF_DIVISOR
 * precedent): production reads the wall clock; the
 * harness injects a scripted one so the budget's trip is
 * testable without a ten-minute test.
 *
 * Pure: no Android, no I/O.
 */
public final class HeadlessUi implements Agent.Ui {

    /** The run's clock. */
    public interface Clock {
        long nowMs();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long nowMs() {
            return System.currentTimeMillis();
        }
    };

    private final long startMs;
    private final Clock clock;

    /** A headless screen for a run starting now. */
    public HeadlessUi(long startMs) {
        this(startMs, SYSTEM_CLOCK);
    }

    /** As above, on a scripted clock (the harness's seam). */
    public HeadlessUi(long startMs, Clock clock) {
        this.startMs = startMs;
        this.clock = (clock == null) ? SYSTEM_CLOCK : clock;
    }

    @Override public void feed(String line) { }

    @Override public void status(String s) { }

    /** Nobody is present: an approval asked of an empty
     *  room is declined, immediately — silence is never
     *  consent, and here there is no silence to wait out. */
    @Override public boolean approve(GhAction action,
                                     Governor.Decision decision,
                                     String describe) {
        return false;
    }

    @Override public boolean stopRequested() {
        return RunControl.stopRequested()
                || Unattended.budgetStop(startMs, clock.nowMs());
    }

    /** An unattended run is off-screen by definition. */
    @Override public boolean foreground() {
        return false;
    }

    /** No co-pilot pause: nobody can tap. */
    @Override public ElementInfo awaitUserTap(int step,
                                              long timeoutMs) {
        return null;
    }

    /** No resume wait: nobody can fill a field. */
    @Override public boolean awaitUserResume(int step,
                                             String message,
                                             long timeoutMs) {
        return false;
    }

    // askUser keeps the interface default (null — no
    // answer; the loop stops rather than guess).

    /** Has the wall-clock budget tripped? (The service
     *  reads this at the finish to name the ending
     *  honestly: a budget stop and a user's stop are both
     *  "stopped" to the loop, but they are not the same
     *  story in the firing book.) */
    public boolean budgetTripped() {
        return Unattended.budgetStop(startMs, clock.nowMs());
    }
}
