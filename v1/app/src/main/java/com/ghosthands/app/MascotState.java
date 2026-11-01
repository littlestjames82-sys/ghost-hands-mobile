package com.ghosthands.app;

/**
 * v3.17 (Mascot Interlude): the Agent screen mascot's
 * DECISIONS, pure and host-tested — the NavState /
 * WatchCompare discipline. MainActivity owns the views and
 * the animators; everything decidable lives here: which
 * state the mascot is in, which pose that state plays, what
 * color its glow is, and how the finish-line one-shots queue.
 *
 * The mascot is a status signal in the ethereal skin, a
 * sibling of the header pill: it reads the same inputs the
 * pill reads (running / stopping / downloading, the two
 * panels' visibility) and adds the finish events the pill
 * never shows (a watch changed, a run done, a run ended
 * stopped / denied / stuck). It takes no input, starts
 * nothing, and announces nothing of its own — the poses ARE
 * the message, and the pill remains the text of it.
 *
 * Priority, highest first: a held STOPPED_DENIED outranks
 * everything until a live state replaces it; a waiting
 * panel outranks the run it interrupts; queued one-shots
 * (WATCH_CHANGED before FINISHED, as offered) outrank the
 * base state they were fired over; the base state is what
 * resolveBase() makes of the live inputs.
 *
 * Glow discipline is the skin's: violet is the mascot's
 * own, cyan is the accent family (downloads, changes),
 * amber is the guard/ask family (waits). NO state maps to
 * red — red belongs to the Stop / stuck / denied signals
 * the pill and the stop button already carry; a stopped
 * mascot dims instead of competing (STOPPED_DENIED's glow
 * role is OFF, alpha 0).
 */
public final class MascotState {

    // States. The names are pinned (group 8): they appear in
    // BUILD_NOTES and any future debug surface.
    public static final int NONE = -1;
    public static final int IDLE = 0;
    public static final int DOWNLOADING = 1;
    public static final int RUNNING = 2;
    public static final int APPROVAL_WAITING = 3;
    public static final int ASK_WAITING = 4;
    public static final int WATCH_CHANGED = 5;
    public static final int FINISHED = 6;
    public static final int STOPPED_DENIED = 7;
    public static final int STATE_COUNT = 8;

    private static final String[] NAMES = {
            "IDLE", "DOWNLOADING", "RUNNING", "APPROVAL_WAITING",
            "ASK_WAITING", "WATCH_CHANGED", "FINISHED",
            "STOPPED_DENIED",
    };

    public static String name(int state) {
        return (state >= 0 && state < STATE_COUNT)
                ? NAMES[state] : "NONE";
    }

    /** The finish-line events: played once, never looped. */
    public static boolean isOneShot(int state) {
        return state == WATCH_CHANGED || state == FINISHED
                || state == STOPPED_DENIED;
    }

    /** The two panel waits share one pose (the approval
     *  family's bounce); the state still names which panel,
     *  so a debug surface can tell them apart. */
    public static boolean isWaiting(int state) {
        return state == APPROVAL_WAITING || state == ASK_WAITING;
    }

    // Pose kinds (what the Activity's animator driver does).
    public static final int KIND_FLOAT = 0;
    public static final int KIND_SWAY = 1;
    public static final int KIND_BOUNCE_LOOP = 2;
    public static final int KIND_HOP = 3;
    public static final int KIND_DROOP = 4;
    public static final int KIND_STATIC = 5;

    // Glow roles (the Activity maps a role to a skin color).
    public static final int GLOW_VIOLET = 0;
    public static final int GLOW_CYAN = 1;
    public static final int GLOW_AMBER = 2;
    public static final int GLOW_OFF = 3;

    // Pose numbers (pinned by the pose-table tests, group 5:
    // the tests pin these constants, not wall-clock behavior).
    public static final long FLOAT_PERIOD_MS = 2600;
    public static final long DOWNLOAD_PERIOD_MS = 2000;
    public static final float FLOAT_AMP_DP = 3f;
    public static final long SWAY_PERIOD_MS = 1800;
    public static final float SWAY_AMP_DEG = 2.5f;
    public static final float SWAY_FLOAT_AMP_DP = 1.5f;
    public static final long PULSE_PERIOD_MS = 1400;
    public static final long BOUNCE_HOP_MS = 550;
    public static final long BOUNCE_CYCLE_MS = 1600;
    public static final float BOUNCE_AMP_DP = 8f;
    public static final long HOP_MS = 450;
    public static final int HOP_COUNT = 2;
    public static final float HOP_AMP_DP = 10f;
    public static final int HERO_HOP_COUNT = 1;
    public static final long DROOP_MS = 400;
    public static final float DROOP_DY_DP = 4f;
    public static final float DROOP_ROT_DEG = 6f;
    public static final float DROOP_ALPHA = 0.55f;
    public static final int GLOW_SWELL_MAX = 140;
    public static final int MAX_ONESHOTS = 2;

    /** One state's pose, animated or (reduce-motion) static.
     *  A plain value: the Activity reads it and drives views;
     *  nothing here touches a view. */
    public static final class Pose {
        public int kind;
        /** Transform cycle length (HOP: one hop's length;
         *  DROOP: the transition; STATIC: 0). */
        public long periodMs;
        /** HOP only: how many hops play. */
        public int cycles;
        /** Bob / bounce / hop amplitude, dp. */
        public float ampDp;
        /** Sway amplitude, degrees (SWAY / DROOP end pose). */
        public float ampDeg;
        /** Glow cycle length; 0 means "same as periodMs". */
        public long glowPeriodMs;
        public int glowRole;
        public int glowMin;
        public int glowMax;
        /** Held end pose (DROOP's landing, STATIC's pose). */
        public float endDyDp;
        public float endRotDeg;
        public float endAlpha;

        Pose() {
            endAlpha = 1f;
        }
    }

    /** The glow role a state wears — the color half of the
     *  discipline the tests pin (group 6). */
    public static int glowRole(int state) {
        switch (state) {
            case DOWNLOADING:
            case WATCH_CHANGED:
                return GLOW_CYAN;
            case APPROVAL_WAITING:
            case ASK_WAITING:
                return GLOW_AMBER;
            case STOPPED_DENIED:
                return GLOW_OFF;
            case IDLE:
            case RUNNING:
            case FINISHED:
            default:
                return GLOW_VIOLET;
        }
    }

    /** The reduce-motion glow: one steady alpha per state, so
     *  the state stays legible with no animator running. A
     *  waiting state's amber holds at 110 — the attention
     *  signal becomes color, not movement. */
    public static int staticGlowAlpha(int state) {
        switch (state) {
            case RUNNING: return 90;
            case APPROVAL_WAITING:
            case ASK_WAITING: return 110;
            case WATCH_CHANGED:
            case FINISHED: return GLOW_SWELL_MAX;
            case STOPPED_DENIED: return 0;
            case IDLE:
            case DOWNLOADING:
            default: return 50;
        }
    }

    /** The hero (model-card greeter) runs a subset of the
     *  poses: a 72dp bounce beside the status text would
     *  fight the text, so every attention state reads as the
     *  idle float there and the header tile does the
     *  attention work. */
    public static int heroStateFor(int state) {
        switch (state) {
            case RUNNING:
            case APPROVAL_WAITING:
            case ASK_WAITING:
            case WATCH_CHANGED:
                return IDLE;
            default:
                return state;
        }
    }

    /** The animated pose for a state. Hero variants: the
     *  finished hop is a single gentle hop at half amplitude;
     *  every other hero state already reaches here through
     *  heroStateFor(). */
    public static Pose poseFor(int state, boolean hero) {
        Pose p = new Pose();
        p.glowRole = glowRole(state);
        switch (state) {
            case DOWNLOADING:
                p.kind = KIND_FLOAT;
                p.periodMs = DOWNLOAD_PERIOD_MS;
                p.ampDp = FLOAT_AMP_DP;
                p.glowPeriodMs = DOWNLOAD_PERIOD_MS;
                p.glowMin = 30;
                p.glowMax = 70;
                break;
            case RUNNING:
                p.kind = KIND_SWAY;
                p.periodMs = SWAY_PERIOD_MS;
                p.ampDp = SWAY_FLOAT_AMP_DP;
                p.ampDeg = SWAY_AMP_DEG;
                p.glowPeriodMs = PULSE_PERIOD_MS;
                p.glowMin = 50;
                p.glowMax = 115;
                break;
            case APPROVAL_WAITING:
            case ASK_WAITING:
                p.kind = KIND_BOUNCE_LOOP;
                p.periodMs = BOUNCE_CYCLE_MS;
                p.ampDp = BOUNCE_AMP_DP;
                p.glowPeriodMs = BOUNCE_CYCLE_MS;
                p.glowMin = 80;
                p.glowMax = 130;
                break;
            case WATCH_CHANGED:
                p.kind = KIND_HOP;
                p.periodMs = BOUNCE_HOP_MS;
                p.cycles = 1;
                p.ampDp = BOUNCE_AMP_DP;
                p.glowMin = 0;
                p.glowMax = GLOW_SWELL_MAX;
                break;
            case FINISHED:
                p.kind = KIND_HOP;
                p.periodMs = HOP_MS;
                p.cycles = hero ? HERO_HOP_COUNT : HOP_COUNT;
                p.ampDp = hero ? HOP_AMP_DP / 2f : HOP_AMP_DP;
                p.glowMin = 0;
                p.glowMax = GLOW_SWELL_MAX;
                break;
            case STOPPED_DENIED:
                p.kind = KIND_DROOP;
                p.periodMs = DROOP_MS;
                p.endDyDp = DROOP_DY_DP;
                p.endRotDeg = DROOP_ROT_DEG;
                p.endAlpha = DROOP_ALPHA;
                p.glowMin = 0;
                p.glowMax = 0;
                break;
            case IDLE:
            default:
                p.kind = KIND_FLOAT;
                p.periodMs = FLOAT_PERIOD_MS;
                p.ampDp = FLOAT_AMP_DP;
                p.glowPeriodMs = FLOAT_PERIOD_MS;
                p.glowMin = 30;
                p.glowMax = 70;
                break;
        }
        return p;
    }

    /** The reduce-motion pose: no motion at all — the state
     *  as a held picture (rest pose, steady glow) with the
     *  droop applied instantly for a stopped ending. */
    public static Pose staticPoseFor(int state, boolean hero) {
        int s = hero ? heroStateFor(state) : state;
        Pose p = new Pose();
        p.kind = KIND_STATIC;
        p.periodMs = 0;
        p.glowRole = glowRole(s);
        p.glowMin = staticGlowAlpha(s);
        p.glowMax = p.glowMin;
        if (s == STOPPED_DENIED) {
            p.endDyDp = DROOP_DY_DP;
            p.endRotDeg = DROOP_ROT_DEG;
            p.endAlpha = DROOP_ALPHA;
        }
        return p;
    }

    /** Whether this state, on this surface, under this
     *  motion setting, wants an animator at all. Reduced
     *  motion never animates (group 7); a STATIC pose never
     *  does either. */
    public static boolean wantsAnimator(int state, boolean hero,
                                        boolean reduceMotion) {
        if (reduceMotion) return false;
        return poseFor(hero ? heroStateFor(state) : state,
                hero).kind != KIND_STATIC;
    }

    /** The base state the live inputs resolve to. Panels
     *  beat the run they interrupt; a stopping run is still
     *  a running mascot (the droop is a finish event, never
     *  a mid-stop pose — the pill's STOPPING treatment). */
    public static int resolveBase(boolean running,
                                  boolean stopping,
                                  boolean downloading,
                                  boolean approvalUp,
                                  boolean askUp) {
        if (approvalUp) return APPROVAL_WAITING;
        if (askUp) return ASK_WAITING;
        if (running) return RUNNING;
        if (downloading) return DOWNLOADING;
        return IDLE;
    }

    // ---------------- the live instance ----------------
    // One per Activity. All methods synchronized: the base
    // is set and the one-shots are offered from whichever
    // thread a finish lands on; the display state is read
    // on the UI thread.

    private int baseState = IDLE;
    private final int[] queue = new int[MAX_ONESHOTS];
    private int queueSize = 0;
    private int activeOneShot = NONE;
    private boolean heldStopped = false;
    private int epoch = 0;

    public synchronized void setBase(boolean running,
                                     boolean stopping,
                                     boolean downloading,
                                     boolean approvalUp,
                                     boolean askUp) {
        baseState = resolveBase(running, stopping, downloading,
                approvalUp, askUp);
        // A live state replaces a held droop: the next run,
        // a download, or a panel is the state change the
        // droop was held until.
        if (heldStopped && baseState != IDLE) {
            heldStopped = false;
        }
    }

    public synchronized int base() {
        return baseState;
    }

    /** Offer a finish-line event. STOPPED_DENIED is not
     *  queued — it takes the display over and holds it (and
     *  clears anything queued ahead of it: a change bounce
     *  does not play over a stopped run's droop). Others
     *  queue in offer order behind the active one, capped
     *  at MAX_ONESHOTS in flight; a third is dropped, never
     *  stacked. Returns whether the event was taken. */
    public synchronized boolean offerOneShot(int state) {
        if (!isOneShot(state)) return false;
        if (state == STOPPED_DENIED) {
            queueSize = 0;
            activeOneShot = NONE;
            heldStopped = true;
            return true;
        }
        if (pendingOneShots() >= MAX_ONESHOTS) return false;
        queue[queueSize++] = state;
        return true;
    }

    public synchronized int pendingOneShots() {
        return queueSize + (activeOneShot != NONE ? 1 : 0);
    }

    /** The state to show right now: the active one-shot,
     *  else the queue's head, else a held droop over an
     *  idle base, else the base state. */
    public synchronized int current() {
        if (activeOneShot != NONE) return activeOneShot;
        if (queueSize > 0) return queue[0];
        if (heldStopped && baseState == IDLE) {
            return STOPPED_DENIED;
        }
        return baseState;
    }

    /** Mark the current one-shot as playing (the Activity
     *  calls this when it starts the pose). Each begin is a
     *  new epoch, so a second surface (the hero) can tell a
     *  fresh play from the one it already showed. */
    public synchronized int beginCurrent() {
        int c = current();
        if (activeOneShot == NONE && isOneShot(c)
                && (queueSize > 0 && queue[0] == c
                        || c == STOPPED_DENIED && heldStopped)) {
            if (queueSize > 0 && queue[0] == c) {
                System.arraycopy(queue, 1, queue, 0,
                        queueSize - 1);
                queueSize--;
            }
            activeOneShot = c;
            epoch++;
        }
        return c;
    }

    /** The active one-shot finished (or was settled by a
     *  lifecycle stop): drop it and return what shows next.
     *  A held droop survives its own transition this way —
     *  finishing the droop's 400 ms leaves the droop held. */
    public synchronized int finishCurrent() {
        activeOneShot = NONE;
        return current();
    }

    public synchronized int epoch() {
        return epoch;
    }

    public synchronized boolean heldStopped() {
        return heldStopped;
    }
}
