package com.ghosthands.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v3.23 (Everything Train R12 — the home-screen widget): the
 * run-state store the widget renders.
 *
 * The widget is a READER, never a runner: it cannot poll, so
 * run status reaches it by being WRITTEN. MainActivity — the
 * only place a run exists — records each transition into this
 * one JSON preferences value and pushes a widget refresh at
 * that moment; the provider (GhostWidget) only loads the
 * store and renders. A dead app means a frozen widget, and
 * this class carries the judgement that keeps a frozen board
 * honest (judge): a state claiming a live phase with NO
 * run-in-progress marker file on disk is a corpse — the run
 * died with the process (the marker is deleted on every
 * normal finish, so its absence is the only witness an abrupt
 * death leaves) — and renders as interrupted, its goal still
 * named.
 *
 * Written ONLY by MainActivity. Tolerant parse, like every
 * store in this app: corrupt or future-shaped values degrade
 * to the empty/idle state, never to a crash in a renderer.
 *
 * Pure: no Android, no I/O.
 */
public final class WidgetState {

    /** The preferences key this store lives under (in the
     *  shared "ghosthands" prefs file). */
    public static final String PREF_WIDGET_STATE = "widget_state";

    public static final String PHASE_IDLE = "idle";
    public static final String PHASE_RUNNING = "running";
    public static final String PHASE_WAITING_APPROVAL = "waiting_approval";
    public static final String PHASE_WAITING_ASK = "waiting_ask";
    public static final String PHASE_STOPPING = "stopping";
    public static final String PHASE_FINISHED = "finished";
    /** The judged verdict for a live phase whose marker is
     *  gone. Never written by a run transition — written back
     *  only by the judgement (the provider's render and the
     *  launch pass's persist), and it renders as-is. */
    public static final String PHASE_INTERRUPTED = "interrupted";

    /** The caps applied AT WRITE: the widget is a status
     *  board, not a transcript. */
    public static final int GOAL_CAP = 80;
    public static final int OUTCOME_CAP = 120;

    public final String phase;
    public final String goal;
    public final int step;
    public final String outcomeLine;
    public final String trailName;
    public final long updatedMs;

    public WidgetState(String phase, String goal, int step,
                       String outcomeLine, String trailName,
                       long updatedMs) {
        this.phase = knownPhase(phase) ? phase : PHASE_IDLE;
        this.goal = (goal == null) ? "" : goal;
        this.step = (step < 0) ? 0 : step;
        this.outcomeLine = (outcomeLine == null) ? "" : outcomeLine;
        this.trailName = (trailName == null) ? "" : trailName;
        this.updatedMs = updatedMs;
    }

    public static WidgetState idle() {
        return new WidgetState(PHASE_IDLE, "", 0, "", "", 0);
    }

    /** A run (agent or replay) just started. */
    public static WidgetState started(String goal, String trailName,
                                      long nowMs) {
        return new WidgetState(PHASE_RUNNING, cap(goal, GOAL_CAP), 0,
                "", trailName, nowMs);
    }

    /** The same state at a new phase (goal, step, trail
     *  preserved). */
    public WidgetState withPhase(String newPhase, long nowMs) {
        return new WidgetState(newPhase, goal, step, outcomeLine,
                trailName, nowMs);
    }

    /** The same running state at a new step. */
    public WidgetState withStep(int newStep, long nowMs) {
        return new WidgetState(phase, goal, newStep, outcomeLine,
                trailName, nowMs);
    }

    /** The run ended: the finished phase, the card's outcome
     *  line (capped), goal and trail preserved for the
     *  "Last:" line and the voice loop's follows stamp. */
    public WidgetState finished(String outcome, long nowMs) {
        return new WidgetState(PHASE_FINISHED, goal, step,
                cap(outcome, OUTCOME_CAP), trailName, nowMs);
    }

    /** Phases in which a run is (or claims to be) alive: the
     *  widget's Stop is offered, and the marker judgement
     *  applies. */
    public static boolean isLivePhase(String phase) {
        return PHASE_RUNNING.equals(phase)
                || PHASE_WAITING_APPROVAL.equals(phase)
                || PHASE_WAITING_ASK.equals(phase)
                || PHASE_STOPPING.equals(phase);
    }

    public boolean isLive() {
        return isLivePhase(phase);
    }

    /**
     * The dead-process judgement — the phase to RENDER for a
     * stored state, given whether the run-in-progress marker
     * file is on disk. A live phase WITH its marker renders
     * as-is; a live phase WITHOUT one is interrupted (the run
     * died with the process and no finish ever overwrote the
     * state). Finished and idle render as-is either way.
     */
    public static String judge(WidgetState s, boolean markerPresent) {
        if (s == null) return PHASE_IDLE;
        if (isLivePhase(s.phase) && !markerPresent) {
            return PHASE_INTERRUPTED;
        }
        return s.phase;
    }

    /** The state as it should be persisted after judging (the
     *  launch pass corrects the store so the correction
     *  survives; the goal is preserved so the interrupted
     *  line can still name it). */
    public static WidgetState judged(WidgetState s,
                                     boolean markerPresent) {
        if (s == null) return idle();
        String j = judge(s, markerPresent);
        if (j.equals(s.phase)) return s;
        // The judged phase is written back as "interrupted"
        // itself — a storable phase like any other (it is in
        // knownPhase, and judge renders it as-is: it is not a
        // live phase, so nothing re-judges it). It stands
        // until the next run transition overwrites the store.
        return new WidgetState(PHASE_INTERRUPTED, s.goal, s.step,
                s.outcomeLine, s.trailName, s.updatedMs);
    }

    private static boolean knownPhase(String p) {
        return PHASE_IDLE.equals(p) || PHASE_RUNNING.equals(p)
                || PHASE_WAITING_APPROVAL.equals(p)
                || PHASE_WAITING_ASK.equals(p)
                || PHASE_STOPPING.equals(p)
                || PHASE_FINISHED.equals(p)
                || PHASE_INTERRUPTED.equals(p);
    }

    private static String cap(String s, int cap) {
        if (s == null) return "";
        return (s.length() <= cap) ? s : s.substring(0, cap);
    }

    public String serialize() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("phase", phase);
        m.put("goal", goal);
        m.put("step", Long.valueOf(step));
        m.put("outcome", outcomeLine);
        m.put("trail", trailName);
        m.put("updated", Long.valueOf(updatedMs));
        return Json.write(m);
    }

    /** Tolerant parse: null/junk/not-an-object → idle; a
     *  phase string from the future resolves to idle (the
     *  store discipline — a renderer must never meet a phase
     *  it cannot word). */
    public static WidgetState parse(String json) {
        if (json == null || json.trim().length() == 0) return idle();
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Exception e) {
            return idle();
        }
        if (!(parsed instanceof Map)) return idle();
        Map<?, ?> m = (Map<?, ?>) parsed;
        String phase = strVal(m.get("phase"));
        if (!knownPhase(phase)) phase = PHASE_IDLE;
        return new WidgetState(phase, strVal(m.get("goal")),
                numVal(m.get("step")), strVal(m.get("outcome")),
                strVal(m.get("trail")), numValL(m.get("updated")));
    }

    private static String strVal(Object o) {
        return (o instanceof String) ? (String) o : "";
    }

    private static int numVal(Object o) {
        return (o instanceof Number) ? ((Number) o).intValue() : 0;
    }

    private static long numValL(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : 0;
    }
}
