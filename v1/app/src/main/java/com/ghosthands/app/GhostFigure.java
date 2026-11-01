package com.ghosthands.app;

/**
 * The mascot's BLINK SCHEDULER, pure and host-tested —
 * and, since v3.21, the whole of this class.
 *
 * History: v3.19 (the Mascot Animation Upgrade) put a
 * drawn ghost in the header tile, and this class computed
 * its whole figure per frame — tail wave, breathing,
 * squash-and-stretch, the droop morph, the glance — of
 * which the blink scheduler below was one section.
 * v3.21 (Art Ghost + Blink, Ryan's call: "I like the
 * other ghost on the app but I like the blinking
 * animation") retired the drawn face: the painted studio
 * ghost is the mascot again, and the ONE piece of the
 * drawn ghost that carried over is the blink — the tiles
 * swap to a closed-eyes cut of the same artwork while
 * this scheduler says the eyes are shut. The figure math
 * went with the face it described; what remains is the
 * scheduler, byte-for-byte the v3.19 code, plus the two
 * small adapters the bitmap swap needs (blinkScaleFor,
 * eyesClosedAt). The v3.19 scheduler checks pin it
 * unchanged.
 *
 * The scheduler (v3.19 plan section 2): a deterministic
 * hashed cadence — gaps in [2.8 s, 4.6 s] x scale, every
 * fifth-ish blink a double, the shape 70 ms down / 90 ms
 * up smoothstepped to a 0.08 floor. No RNG state, no
 * clocks read internally: (time in state, scale) in, eye
 * openness out, which is what makes it testable.
 */
public final class GhostFigure {

    private GhostFigure() { }

    // Blink scheduler numbers (v3.19 plan section 2).
    public static final long BLINK_MIN_MS = 2800;
    public static final long BLINK_SPAN_MS = 1801;
    public static final long BLINK_CLOSE_MS = 70;
    public static final long BLINK_OPEN_MS = 90;
    public static final long BLINK_TOTAL_MS =
            BLINK_CLOSE_MS + BLINK_OPEN_MS;
    public static final long DOUBLE_GAP_MS = 220;
    public static final float EYE_MIN_OPEN = 0.08f;

    // ---------------- the blink scheduler ----------------

    /** The blink clock's gaps, each in [2800, 4600] x
     *  scale: gap 0 is the first blink's delay, gap k
     *  (k >= 1) is the start-to-start interval ending at
     *  blink k. A deterministic hash of the index (no
     *  RNG state — host-testable; plan 2/10). */
    public static long blinkIntervalMs(int k, float scale) {
        long h = ((long) k * 2654435761L) >>> 16;
        long base = BLINK_MIN_MS + (h % BLINK_SPAN_MS);
        return Math.round(base * (double) scale);
    }

    /** Every blink whose index hashes to a multiple of 5
     *  is a double: a second close DOUBLE_GAP_MS after the
     *  first begins (plan 2). */
    public static boolean isDoubleBlink(int k) {
        long h = ((long) k * 1103515245L + 12345L) >>> 16;
        return h % 5L == 0L;
    }

    /** Eye openness 1 down to 0.08 and back across one
     *  blink whose close begins at tau 0 (70 ms down,
     *  90 ms up, both smoothstepped); 1 outside the blink. */
    public static float blinkShape(long tauMs) {
        if (tauMs < 0 || tauMs >= BLINK_TOTAL_MS) return 1f;
        if (tauMs < BLINK_CLOSE_MS) {
            float f = smooth((float) tauMs
                    / (float) BLINK_CLOSE_MS);
            return 1f - (1f - EYE_MIN_OPEN) * f;
        }
        float f = smooth((float) (tauMs - BLINK_CLOSE_MS)
                / (float) BLINK_OPEN_MS);
        return EYE_MIN_OPEN + (1f - EYE_MIN_OPEN) * f;
    }

    /** Openness at absolute time t on the blink clock:
     *  events at cumulative hashed intervals; a double
     *  blink's second close rides DOUBLE_GAP_MS behind
     *  the first. */
    public static float blinkOpenness(long tMs, float scale) {
        if (tMs < 0) return 1f;
        long eventStart = blinkIntervalMs(0, scale);
        int k = 0;
        while (true) {
            long tau = tMs - eventStart;
            if (tau < 0) return 1f;
            long span = isDoubleBlink(k)
                    ? DOUBLE_GAP_MS + BLINK_TOTAL_MS
                    : BLINK_TOTAL_MS;
            if (tau < span) {
                float o = blinkShape(tau);
                if (isDoubleBlink(k)) {
                    o = Math.min(o, blinkShape(tau - DOUBLE_GAP_MS));
                }
                return o;
            }
            eventStart += blinkIntervalMs(k + 1, scale);
            k++;
        }
    }

    // ---------------- the bitmap adapters (v3.21) --------

    /** The blink interval scale for a display state, in
     *  the pose table's own idiom (the hero rides
     *  heroStateFor first, so its attention states blink
     *  at the calm idle rate). The two panel waits stare:
     *  x1.6, longer open intervals between blinks. The
     *  droop SUSPENDS the blink (scale 0): v3.19 held the
     *  drawn eyes at half-mast under the slump with no
     *  blink riding on top, and the bitmap has no
     *  half-mast cut — the honest equivalent is that the
     *  painted eyes simply stay open under the droop's
     *  transform. Every other state blinks at x1. */
    public static float blinkScaleFor(int state, boolean hero) {
        int s = hero ? MascotState.heroStateFor(state) : state;
        switch (s) {
            case MascotState.APPROVAL_WAITING:
            case MascotState.ASK_WAITING:
                return 1.6f;
            case MascotState.STOPPED_DENIED:
                return 0f;
            default:
                return 1f;
        }
    }

    /** Is the eye shut at time t on the blink clock? A
     *  bitmap swap has no half-open frame to show, so
     *  "closed" is the shape's closed phase: openness at
     *  or below half (the middle ~70 ms of each 160 ms
     *  blink; both dips of a double count). A suspended
     *  scale (0, the droop) never closes — and never
     *  reaches blinkOpenness, whose interval walk a 0
     *  scale would stall. */
    public static boolean eyesClosedAt(long tMs, float scale) {
        if (scale <= 0f) return false;
        return blinkOpenness(tMs, scale) <= 0.5f;
    }

    // ---------------- small pure helpers ----------------

    static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    /** smoothstep. */
    static float smooth(float x) {
        x = clamp01(x);
        return x * x * (3f - 2f * x);
    }
}
