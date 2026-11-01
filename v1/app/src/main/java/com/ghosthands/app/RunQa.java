package com.ghosthands.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v3.20 (R10): ASK-ABOUT-A-RUN — the judging half of a
 * read-only conversation about one CLOSED record. Pure: this
 * class builds prompts and judges raw completions; it cannot
 * call the model itself, write anywhere, or act — the caller
 * carries the prompt to the engine and brings the raw text
 * back here to be judged before anything renders it.
 *
 * The grounding rule is enforced HERE, host-side, and never
 * trusted to the prompt: every numeric token in an answer
 * must appear in the digest's evidence token set (tokenized
 * by the relevance guard's own tokenizer, so "1,117" and
 * "1117" and "43.5" mean the same on both sides). A first
 * failure returns a CORRECT verdict carrying a rebuilt prompt
 * that NAMES the offending numbers; a second failure returns
 * REFUSED, and the refused text is never part of the verdict —
 * the panel renders the refusal line, not the model's words.
 *
 * The verbatim refusal is a first-class ANSWER, not an error:
 * when the record does not say, the honest answer is exactly
 * the refusal text, and it passes the grounding check like
 * any grounded answer (it carries no numbers at all).
 */
public final class RunQa {

    /** The generation cap for one answer. */
    public static final int QA_MAX_TOKENS = 220;
    /** The estimated-token allowance for prior Q/A pairs in
     *  the prompt; older pairs drop first. */
    public static final int PRIOR_PAIRS_BUDGET = 250;
    /** The one allowed answer when the record does not say. */
    public static final String REFUSAL = "That is not in the trail.";
    /** What the panel shows when judging refuses an answer. */
    public static final String REFUSED_LINE =
            "The model could not answer that from the trail.";

    /** One earlier exchange in this panel's conversation. */
    public static final class Pair {
        public final String question;
        public final String answer;

        public Pair(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    /** The judged outcome of one raw completion. ANSWER
     *  carries the text the panel may render; CORRECT carries
     *  the rebuilt prompt for the single re-ask (and the
     *  offending numbers it names); REFUSED carries nothing —
     *  the ungrounded text is dropped here, by construction. */
    public static final class Verdict {
        public static final int ANSWER = 0;
        public static final int CORRECT = 1;
        public static final int REFUSED = 2;

        public final int kind;
        public final String text;
        public final List<String> offenders;
        public final String prompt;

        private Verdict(int kind, String text,
                List<String> offenders, String prompt) {
            this.kind = kind;
            this.text = text;
            this.offenders = offenders;
            this.prompt = prompt;
        }

        static Verdict answer(String text) {
            return new Verdict(ANSWER, text,
                    new ArrayList<String>(), null);
        }

        static Verdict correct(List<String> offenders,
                String prompt) {
            return new Verdict(CORRECT, null, offenders, prompt);
        }

        static Verdict refused() {
            return new Verdict(REFUSED, null,
                    new ArrayList<String>(), null);
        }
    }

    private RunQa() { }

    /** The standing text, pinned in substance: answer only
     *  from the record, cite the step or fact behind a claim,
     *  the refusal verbatim when the record does not say, no
     *  invented numbers / names / places / times, keep it
     *  short. The prompt asks with words never used for acting,
     *  so the standing text a run obeys can never be this one. */
    public static String standingText() {
        return "You are answering questions about one finished "
                + "run. Answer ONLY from the run record below. "
                + "For each claim, name the step or the fact it "
                + "comes from. If the record does not say, "
                + "answer with exactly: " + REFUSAL + " Never "
                + "invent numbers, names, places or times. Keep "
                + "the answer short.";
    }

    /** The caller-side prompt for compressing one chunk of
     *  step lines (the chunked digest path): keep every
     *  number, name and outcome exactly as written, add
     *  nothing. */
    public static String chunkPrompt(String chunkText) {
        return "Below are step lines from a finished run's "
                + "record. Compress them into short notes (at "
                + "most about 150 tokens). Keep every number, "
                + "name and outcome exactly as written. Do not "
                + "add anything that is not in the lines.\n\n"
                + chunkText + "\nNotes:";
    }

    /** The full prompt: standing text, the digest (the entire
     *  evidence universe), the surviving prior pairs, then the
     *  question. */
    public static String buildPrompt(String digestText,
            List<Pair> priors, String question) {
        StringBuilder sb = new StringBuilder();
        sb.append(standingText()).append("\n\n");
        sb.append("The run record:\n").append(digestText);
        if (!digestText.endsWith("\n")) sb.append("\n");
        List<Pair> kept = trimPriors(priors);
        if (!kept.isEmpty()) {
            sb.append("\nEarlier in this conversation:\n");
            for (int i = 0; i < kept.size(); i++) {
                sb.append("Q: ").append(kept.get(i).question)
                        .append("\n");
                sb.append("A: ").append(kept.get(i).answer)
                        .append("\n");
            }
        }
        sb.append("\nQuestion: ").append(question).append("\n");
        sb.append("Answer:");
        return sb.toString();
    }

    /** Prior pairs within their allowance: the newest survive,
     *  the OLDEST drop first, each pair measured whole. */
    public static List<Pair> trimPriors(List<Pair> priors) {
        List<Pair> out = new ArrayList<Pair>();
        if (priors == null) return out;
        int total = 0;
        for (int i = priors.size() - 1; i >= 0; i--) {
            Pair p = priors.get(i);
            int cost = RunDigest.estTokens("Q: " + p.question
                    + "\nA: " + p.answer + "\n");
            if (total + cost > PRIOR_PAIRS_BUDGET) break;
            total += cost;
            out.add(0, p);
        }
        return out;
    }

    /** The digest's evidence token set: every numeric token
     *  the record carries, tokenized by the guard's tokenizer. */
    public static Set<String> evidenceTokens(String digestText) {
        return new HashSet<String>(
                RelevanceGuard.numberTokens(digestText));
    }

    /** The numbers in an answer that the evidence does not
     *  carry, in the order the answer used them, each once. */
    public static List<String> ungroundedNumbers(String answer,
            Set<String> evidence) {
        List<String> out = new ArrayList<String>();
        List<String> tokens = RelevanceGuard.numberTokens(answer);
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!evidence.contains(t) && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Judge a first raw completion. ANSWER when every number
     *  is grounded (the verbatim refusal included); CORRECT
     *  with a rebuilt prompt naming the offenders otherwise;
     *  REFUSED when there is no text to judge at all. */
    public static Verdict judge(String digestText,
            List<Pair> priors, String question, String rawAnswer) {
        String clean = cleanAnswer(rawAnswer);
        if (clean.length() == 0) return Verdict.refused();
        List<String> offenders = ungroundedNumbers(clean,
                evidenceTokens(digestText));
        if (offenders.isEmpty()) return Verdict.answer(clean);
        StringBuilder fix = new StringBuilder();
        fix.append(buildPrompt(digestText, priors, question));
        fix.append("\nCorrection: your previous answer used "
                + "numbers that are not in the record: ");
        for (int i = 0; i < offenders.size(); i++) {
            if (i > 0) fix.append(", ");
            fix.append(offenders.get(i));
        }
        fix.append(". Answer again using only the record, or "
                + "say it is not in the trail.");
        return Verdict.correct(offenders, fix.toString());
    }

    /** Judge the correction re-ask's completion: grounded ->
     *  ANSWER; anything else -> REFUSED (the exchange ends;
     *  there is no third ask). */
    public static Verdict judgeCorrection(Set<String> evidence,
            String rawAnswer) {
        String clean = cleanAnswer(rawAnswer);
        if (clean.length() == 0) return Verdict.refused();
        if (!ungroundedNumbers(clean, evidence).isEmpty()) {
            return Verdict.refused();
        }
        return Verdict.answer(clean);
    }

    /** A raw completion, presentable: trimmed, with a stray
     *  echoed "Answer:" prefix removed. */
    public static String cleanAnswer(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("Answer:")) {
            t = t.substring("Answer:".length()).trim();
        }
        return t;
    }
}
