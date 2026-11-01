package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The fact-grounding guard (v3.31) — an agent-loop correction in
 * the RelevanceGuard family, NOT part of the safety Governor. The
 * Governor answers "is this action safe / permitted"; the v2.1 /
 * v3.0 done-grounding rule answers "does this done carry values,
 * and does at least one of them appear in the run's evidence";
 * this guard answers the stricter question the Nov 1, 2026 field
 * run proved necessary: "does EVERY figure this note or done
 * states appear in text the run actually READ?"
 *
 * Why it exists (the failure it kills): on Nov 1, 2026 the
 * trained planner ran the Oakdale weather goal, read only the
 * generic National Weather Service landing page (2,036 chars of
 * menus and footer — no forecast anywhere in it), then noted
 * "Next three days: High 60°F, Low 40°F, Chance of rain 20%" and
 * answered done with the same figures. Those figures appear in
 * NOTHING the run read. Three action guards fired correctly that
 * run; nothing judged the facts. Worse, the note itself then
 * laundered the done past the v3.0 evidence rule: the run's
 * evidence includes the scratchpad's facts, so once an invented
 * figure is NOTED, a done repeating it finds the figure "in the
 * evidence" — its own note. This guard therefore judges BOTH
 * actions, the note first of all, and judges them against a
 * corpus of READ TEXT ONLY: the results of the run's read
 * actions and the loop's own reads (arrival auto-reads, the
 * thin-map fallback read) — the strings that were actually
 * surfaced to the planner as page/document text. Notes never
 * enter the corpus, so a figure can never ground itself.
 *
 * The verdict rules:
 *   - NUMERIC TOKENS are RelevanceGuard.numberTokens' — the
 *     tokenizer the whole guard family (and RunQa, and
 *     WatchCompare) already shares: maximal digit runs, a
 *     decimal point kept between digits ("20.5" is its own
 *     token, distinct from "20"), grouping commas dropped
 *     ("1,200" -> "1200"), so "60°F", "60 degrees" and "60"
 *     are the one token "60".
 *   - A token is GROUNDED iff it appears among the corpus's own
 *     tokens — standalone-token equality on both sides, so
 *     substring hits never count: a corpus carrying "2036" does
 *     not ground "20", and "20910" grounds neither "20" nor
 *     "2091".
 *   - PASS when the candidate carries no numeric tokens (an
 *     honest "I could not find the forecast" done passes
 *     untouched — the honest exit stays open) or when every
 *     token is grounded.
 *   - CORRECT when any token is ungrounded — including the
 *     empty-corpus case: a corpus with no read text grounds
 *     nothing, so a figured note/done before any read corrects.
 *   - The correction is capped per run
 *     (GROUNDING_CORRECTION_CAP): after that many corrections
 *     the verdict is BYPASSED — the action proceeds exactly as
 *     the planner wrote it (the guard NEVER rewrites the
 *     planner's text), and the loop records a "guard" event with
 *     outcome "bypassed" naming the still-ungrounded figures,
 *     so a bypassed invention is visible on the trail forever,
 *     never silent.
 *
 * Pure: no Android, no I/O, no state. The loop owns the corpus
 * and the correction count, exactly as it owns the other guards'
 * counters.
 */
public final class GroundingGuard {

    private GroundingGuard() { }

    /** The per-run correction cap: three explicit "those figures
     *  are in nothing you read" corrections, then the guard
     *  stands down to BYPASSED (the family's standing shape —
     *  RelevanceGuard's caps are 2-3 for the same reason: enough
     *  pointed re-asks to teach, never a permanent trap). */
    public static final int GROUNDING_CORRECTION_CAP = 3;

    /** A judgement's outcome. */
    public enum Outcome { PASS, CORRECT, BYPASSED }

    /** One judgement: the outcome, the candidate's ungrounded
     *  numeric tokens (in the order the candidate states them,
     *  duplicates collapsed; empty on PASS), and the trail /
     *  history wording (null on PASS). */
    public static final class Verdict {
        public final Outcome outcome;
        public final List<String> ungrounded;
        public final String reason;

        Verdict(Outcome outcome, List<String> ungrounded,
                String reason) {
            this.outcome = outcome;
            this.ungrounded = ungrounded;
            this.reason = reason;
        }
    }

    /**
     * Judge a candidate fact string (a note's fact, a done's
     * summary) against the run's read corpus. correctionsSoFar
     * is the run's count of grounding corrections already spent;
     * it decides CORRECT vs BYPASSED only — an all-grounded or
     * figureless candidate passes at any count.
     */
    public static Verdict judge(String candidate, String corpus,
                                int correctionsSoFar) {
        List<String> ungrounded = ungroundedTokens(candidate, corpus);
        if (ungrounded.isEmpty()) {
            return new Verdict(Outcome.PASS, ungrounded, null);
        }
        if (correctionsSoFar < GROUNDING_CORRECTION_CAP) {
            return new Verdict(Outcome.CORRECT, ungrounded,
                    correctReason(ungrounded));
        }
        return new Verdict(Outcome.BYPASSED, ungrounded,
                bypassReason(ungrounded));
    }

    /**
     * The candidate's numeric tokens that the corpus does not
     * ground, in candidate order with duplicates collapsed. A
     * null/empty candidate has none; a null/empty corpus grounds
     * nothing.
     */
    public static List<String> ungroundedTokens(String candidate,
                                                String corpus) {
        List<String> nums = RelevanceGuard.numberTokens(candidate);
        List<String> out = new ArrayList<String>();
        if (nums.isEmpty()) return out;
        Set<String> corpusTokens = new LinkedHashSet<String>(
                RelevanceGuard.numberTokens(corpus));
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < nums.size(); i++) {
            String tok = nums.get(i);
            if (!seen.add(tok)) continue;
            if (!corpusTokens.contains(tok)) out.add(tok);
        }
        return out;
    }

    /** The correction's wording: names the ungrounded figures
     *  and the two honest ways forward — read a page that holds
     *  the answer, or finish saying it could not be found,
     *  without figures. */
    public static String correctReason(List<String> ungrounded) {
        return "fact grounding corrected by guard: the figure(s) "
                + figureList(ungrounded) + " appear in nothing "
                + "you have read this run - a figure can only be "
                + "reported if you read it. Read a page that "
                + "actually contains the answer, or finish with "
                + "{\"action\":\"done\"} saying you could not "
                + "find it, without figures";
    }

    /** The bypass's wording, recorded on the trail when the cap
     *  is spent and an ungrounded action proceeds as written:
     *  the figures are still named, so the invention stays
     *  visible in the run's account of itself. */
    public static String bypassReason(List<String> ungrounded) {
        return "fact grounding bypassed after "
                + GROUNDING_CORRECTION_CAP + " corrections: the "
                + "figure(s) " + figureList(ungrounded)
                + " appear in nothing read this run; the action "
                + "proceeds as the planner wrote it";
    }

    /** "60, 40 and 20" — the figures as the candidate stated
     *  them, for the correction and bypass wordings. */
    public static String figureList(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(i == tokens.size() - 1 ? " and " : ", ");
            }
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }
}
