package com.ghosthands.app;

/**
 * v3.23 (Everything Train R12 — widget + voice loop): the
 * process-wide stop latch.
 *
 * The in-app Stop button talks to the running agent through
 * the Activity's own stopFlag and by releasing the Activity's
 * latch waits directly. A stop that originates OUTSIDE the
 * Activity — the home-screen widget's Stop button, received
 * by GhostWidget while the app may not even be in the
 * foreground — has no Activity to touch. This latch is the
 * road in: a process-wide flag the widget sets, the Ui's
 * stopRequested() ORs into every poll the loop makes, and the
 * Activity's blocking waits poll in slices (awaitSliced) so
 * an outside stop lands within one slice instead of waiting
 * out the pack clock.
 *
 * It is deliberately NOT a store: it means "a stop was
 * requested from outside the Activity", it lives exactly as
 * long as a run can, and the start path clears it (a stale
 * widget stop from a previous run must never kill a fresh
 * one — the harness pins that).
 *
 * The sliced wait itself lives here, factored pure the way
 * Copilot.Pause factors its machine, so the harness can drive
 * it against a scripted latch: await one slice, then judge —
 * the latch fired (the wait's own answer arrived), an outside
 * stop landed (resolve exactly as the in-app Stop resolves:
 * the caller reads stopRequested() in its return expression
 * and declines / returns no-answer), or the slice expired and
 * the clock still has room.
 */
public final class RunControl {

    /** The slice each blocking wait polls in — an outside
     *  stop is never more than this far from landing. */
    public static final long STOP_POLL_SLICE_MS = 250L;

    private static volatile boolean stop = false;

    private RunControl() { }

    /** A stop was requested from outside the Activity (the
     *  widget). Idempotent: a second request changes nothing. */
    public static void requestStop() {
        stop = true;
    }

    /** The start path clears the latch: a request belongs to
     *  the run it was made against, never to the next one. */
    public static void clear() {
        stop = false;
    }

    /** Has an outside stop been requested and not cleared? */
    public static boolean stopRequested() {
        return stop;
    }

    /** A latch a sliced wait can sit on: awaitSlice blocks
     *  for at most the given milliseconds and reports whether
     *  the latch fired inside the slice (CountDownLatch.await
     *  is the production implementation). */
    public interface SliceLatch {
        boolean awaitSlice(long sliceMs) throws InterruptedException;
    }

    /**
     * Wait on a latch in slices of STOP_POLL_SLICE_MS until it
     * fires or the timeout runs out. Returns true only when
     * the latch itself fired; an outside stop (or the clock)
     * returns false, promptly — within one slice of the stop
     * landing — and the caller resolves that as the in-app
     * Stop resolves (its return expression consults
     * stopRequested() beside its own flags). A latch already
     * set returns… the first slice reports it, as a zero-wait
     * would; a stop already set on entry returns false without
     * waiting at all (the entry guard).
     */
    public static boolean awaitSliced(SliceLatch latch, long timeoutMs)
            throws InterruptedException {
        if (latch == null) return false;
        if (stop) return false;
        long remaining = (timeoutMs > 0) ? timeoutMs : 0;
        while (true) {
            long slice = Math.min(STOP_POLL_SLICE_MS, remaining);
            if (slice <= 0) return false;
            boolean fired = latch.awaitSlice(slice);
            if (fired) return true;
            if (stop) return false;
            remaining -= slice;
            if (remaining <= 0) return false;
        }
    }
}
