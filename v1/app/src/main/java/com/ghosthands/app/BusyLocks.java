package com.ghosthands.app;

/** v3.33: the speaking lock. The model surface's busy
 *  flags (a run, a model download, a trained-model
 *  import) make whole families of taps idle-time-only,
 *  and through v3.32 a tap that arrived while one was
 *  set died in a bare silent return — Ryan tapped
 *  "Import trained model" mid-import on Nov 1, 2026 and
 *  the app said nothing at all: no picker, no toast, no
 *  progress, indistinguishable from a dead button. The
 *  handlers still refuse, exactly as before; what
 *  changes is that the refusal NAMES the busy state and
 *  where its progress can be watched.
 *
 *  messageFor returns the line to show for the FIRST
 *  set flag in priority order — running, then
 *  importing, then downloading (the order the handlers
 *  have always judged them in) — or null when nothing
 *  is busy and the tap may proceed. The action names
 *  what the tap was trying to do, in the gerund form
 *  the standing "Finish the run before..." toasts use,
 *  so the running line is byte-for-byte the toast those
 *  handlers already showed. Pure and static: the host
 *  harness pins every branch. */
public final class BusyLocks {

    private BusyLocks() { }

    public static String messageFor(boolean running,
                                    boolean downloading,
                                    boolean importing,
                                    String action) {
        if (running) {
            return "Finish the run before " + action;
        }
        if (importing) {
            return "An import is already running — its "
                    + "progress is on the model card";
        }
        if (downloading) {
            return "A model download is running — let it "
                    + "finish first";
        }
        return null;
    }
}
