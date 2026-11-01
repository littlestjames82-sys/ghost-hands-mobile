package com.ghosthands.app;

/**
 * v3.23 (Everything Train R12 — the voice conversation loop):
 * the follow-up conversation as a pure state machine, factored
 * the way Copilot.Pause factors its machine — the whole rule
 * in one place, harness-pinned, with MainActivity owning only
 * the speech engines it drives.
 *
 * The loop, in one paragraph: when a hand-started agent run
 * finishes DONE and its answer card is auto-spoken (the v3.4
 * speak-on-finish rule), the loop may ARM. When the card's
 * utterance completes, it speaks one pinned prompt; when the
 * prompt completes, the recognizer opens once for a follow-up
 * goal; whatever it hears becomes a NORMAL run through the
 * ordinary start path. MAX_FOLLOW_UPS is 1: a loop-started
 * run's finish can speak its answer exactly as today, but it
 * never reopens the mic. Silence — no usable hearing, a
 * recognizer error, the screen going away, another road
 * starting — closes the loop quietly, once: no re-prompt, no
 * second listen, no timer of its own (the recognizer's own
 * session timeout is the only clock).
 *
 * The one deliberate exception in this app's voice design,
 * stated plainly: every other voice path is review-first
 * (dictation fills a field; the user reads it and taps
 * Start). The loop STARTS what it heard, unreviewed — that is
 * the feature ("say the next goal"). Its mitigations are
 * structural: the governor and approval flow are untouched
 * inside the follow-up run, the heard goal is placed in the
 * goal field and named in a feed line before the run's first
 * step, the pack is the user's current pack, and Stop works
 * throughout. The Activity's wiring carries that; this class
 * carries the conversation's shape.
 *
 * Pure: no Android, no I/O.
 */
public final class VoiceLoop {

    /** Follow-ups one hand-started run may earn. One means
     *  one: the loop will not chain a conversation. */
    public static final int MAX_FOLLOW_UPS = 1;

    /** The prompt, pinned verbatim (the harness checks the
     *  exact string): short, and it names the silence exit. */
    public static final String PROMPT_TEXT =
            "Answer done. Say a follow-up goal, or stay silent to finish.";

    /** The utterance ids the TTS listener routes on (the card
     *  id is the one speakCard has always used). */
    public static final String UTTERANCE_CARD = "ghosthands-card";
    public static final String UTTERANCE_PROMPT = "ghosthands-loop-prompt";

    /** The recognizer's prompt extra on the loop's route. */
    public static final String LISTEN_PROMPT = "Say the follow-up goal";

    /** The one feed line a quiet close leaves behind. */
    public static final String NO_HEARD_LINE =
            "Voice loop: no follow-up heard — tap Voice or type the next goal";

    /** The feed line that mirrors the spoken prompt for the
     *  user who cannot hear it (the loop must be followable
     *  with the sound off). */
    public static final String LISTENING_LINE =
            "Listening for a follow-up goal…";

    public enum State { IDLE, ARMED, PROMPTING, LISTENING, STARTING }

    private State state = State.IDLE;
    private int followUpsUsed = 0;
    private String heardText = null;

    public VoiceLoop() { }

    public State state() {
        return state;
    }

    public boolean isListening() {
        return state == State.LISTENING;
    }

    /** The conversation is mid-flight (armed or beyond) —
     *  the Activity's cancel legs consult this before they
     *  tear anything down. */
    public boolean active() {
        return state != State.IDLE;
    }

    public int followUpsUsed() {
        return followUpsUsed;
    }

    /** The follow-up text the STARTING transition accepted. */
    public String heardText() {
        return heardText;
    }

    /**
     * The arm judgement, pure: EVERY leg must hold — the
     * loop setting, the voice master setting, speak-on-finish
     * (the v3.4 legs: on / foreground / done), the finished
     * run was an AGENT run (route replays share finishRunUi
     * and can finish done with a card; a replay's spoken card
     * must never open a mic), it was NOT loop-started (the
     * cap), and a recognizer exists on the device (judged at
     * arm time so a voice-less phone never speaks a prompt it
     * cannot honour).
     */
    public static boolean armJudgement(boolean loopOn, boolean voiceOn,
            boolean speakOnFinishOn, boolean outcomeIsDone,
            boolean foreground, boolean runWasLoopStarted,
            boolean runWasAgentRun, boolean recognizerAvailable) {
        return loopOn && voiceOn && speakOnFinishOn && outcomeIsDone
                && foreground && !runWasLoopStarted && runWasAgentRun
                && recognizerAvailable;
    }

    /** Arm at a finish whose card is about to be auto-spoken.
     *  Refused arms change nothing (the finish speaks exactly
     *  as it always has, and no prompt follows). The arm is
     *  recorded BEFORE the speak call, in this machine — not
     *  inferred later from which card was spoken — and only
     *  from inside the branch where the speak is issued: if
     *  the card is not spoken, the loop does not arm. */
    public boolean arm(boolean loopOn, boolean voiceOn,
            boolean speakOnFinishOn, boolean outcomeIsDone,
            boolean foreground, boolean runWasLoopStarted,
            boolean runWasAgentRun, boolean recognizerAvailable) {
        if (state != State.IDLE) return false;
        if (followUpsUsed >= MAX_FOLLOW_UPS) return false;
        if (!armJudgement(loopOn, voiceOn, speakOnFinishOn,
                outcomeIsDone, foreground, runWasLoopStarted,
                runWasAgentRun, recognizerAvailable)) {
            return false;
        }
        state = State.ARMED;
        return true;
    }

    /** The card utterance completed while ARMED → the prompt
     *  is spoken next. Speech completing in any other state
     *  changes nothing (a Listen tap's utterance never arms —
     *  the arm flag is set only by the auto-speak path, so a
     *  read-aloud request is not a conversation opener). */
    public boolean onSpeechDone() {
        if (state != State.ARMED) return false;
        state = State.PROMPTING;
        return true;
    }

    /** A speech ERROR while ARMED → the conversation dies
     *  quietly (a flaky TTS engine means no loop, never a
     *  stuck mic). */
    public boolean onSpeechError() {
        if (state != State.ARMED) return false;
        state = State.IDLE;
        return true;
    }

    /** The prompt utterance completed while PROMPTING → the
     *  recognizer opens. */
    public boolean onPromptDone() {
        if (state != State.PROMPTING) return false;
        state = State.LISTENING;
        return true;
    }

    /** The prompt utterance errored while PROMPTING → quiet
     *  close. */
    public boolean onPromptError() {
        if (state != State.PROMPTING) return false;
        state = State.IDLE;
        return true;
    }

    /**
     * Recognizer results while LISTENING. A usable hearing
     * (VoiceGoal.pickHeard non-null, judged by the caller)
     * → STARTING, the text accepted and the follow-up spent.
     * Anything else → IDLE: silence is terminal. Returns the
     * accepted text, or null when the loop closed unheard.
     */
    public String onHeard(String picked) {
        if (state != State.LISTENING) return null;
        if (picked != null && picked.length() > 0) {
            heardText = picked;
            followUpsUsed++;
            state = State.STARTING;
            return picked;
        }
        state = State.IDLE;
        return null;
    }

    /** The STARTING hand-off completed (the start path owns
     *  the run now): the conversation is over. */
    public boolean onStarted() {
        if (state != State.STARTING) return false;
        state = State.IDLE;
        return true;
    }

    /**
     * The quiet close: recognizer error or timeout, an
     * unusable results bundle, a run starting by another road,
     * the goal mic tapped — ANY interposition while PROMPTING
     * or LISTENING closes the loop. Returns true exactly once
     * per conversation (the transition itself), so the caller
     * leaves exactly one feed line: no re-prompt, no second
     * listen, no toast storm.
     */
    public boolean onSilence() {
        if (state != State.PROMPTING && state != State.LISTENING) {
            return false;
        }
        state = State.IDLE;
        return true;
    }

    /** Close from any state without ceremony (the Activity
     *  pausing or being destroyed — a mic must never outlive
     *  the screen that opened it). */
    public void closeQuietly() {
        state = State.IDLE;
    }
}
