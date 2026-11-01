package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.20 (R10): the RUN DIGEST — a finished trail distilled
 * into the ONLY evidence a question about the run may be
 * answered from. Pure, read-only, and built to a budget:
 * everything downstream (the question prompt, the grounding
 * check) trusts that what is not in this text does not exist.
 *
 * Budget discipline, pinned: the digest fits
 * DIGEST_TOKEN_BUDGET estimated tokens (measured by the same
 * estimator the prompt-fit ladder trusts — mirrored below so
 * this class stands alone; the harness pins the mirror equal
 * to the original over its fixtures). When a long trail does
 * not fit, the condensation ladder runs in order:
 *  (a) step-line result tails shorten 120 -> 60 chars;
 *  (b) consecutive reads of the same page collapse into one
 *      line naming the count;
 *  (c) middle step lines drop (first 5 + last 10 kept).
 * EVIDENCE lines are never dropped by any rung: notes, asks,
 * answers, approvals, denials, gate corrections and the stop
 * ride outside the step-line count. When the ladder still
 * overflows, build() returns a chunk plan instead (see
 * MAX_QA_CHUNKS): the caller compresses each chunk of step
 * lines into short notes and assemble() joins the skeleton
 * and the notes into the final digest.
 */
public final class RunDigest {

    public static final int DIGEST_TOKEN_BUDGET = 1000;
    public static final int MAX_QA_CHUNKS = 4;
    /** One chunk of step lines, estimated tokens. */
    public static final int CHUNK_TOKEN_BUDGET = 900;
    public static final int RESULT_TAIL_FULL = 120;
    public static final int RESULT_TAIL_MIN = 60;

    /** The built digest. When chunked is true, text is empty
     *  until assemble() joins the caller's chunk notes;
     *  headText / stopText / chunks carry the plan. */
    public static final class Digest {
        public String text = "";
        public boolean condensed = false;
        public boolean chunked = false;
        public boolean chunksDropped = false;
        public String headText = "";
        public String stopText = "";
        public final List<String> chunks = new ArrayList<String>();
    }

    /** One distilled line of the steps section. Evidence lines
     *  (evidence == true) are exempt from every ladder rung. */
    private static final class Line {
        String head = "";
        String tail = "";
        boolean evidence = false;
        boolean readStep = false;
        String pageKey = "";
        String pageLabel = "";
        int step = 0;
    }

    private RunDigest() { }

    // ---- the estimator mirror ---------------------------------
    //
    // A line-for-line mirror of the run loop's token estimator,
    // kept here so this class (and its question-side sibling)
    // references nothing that can act. The harness pins the two
    // implementations equal over every fixture it measures, so
    // the mirror cannot drift silently.

    public static int estTokens(String s) {
        if (s == null) return 0;
        int tokens = (s.length() + 3) / 4;
        int start = 0;
        while (start < s.length()) {
            int nl = s.indexOf('\n', start);
            int end = (nl < 0) ? s.length() : nl;
            int len = end - start;
            int surcharge = 0;
            if (lineHasHttp(s, start, end)) {
                surcharge = (len + 2) / 3 - (len + 3) / 4;
            }
            if (isDigitDenseLine(s, start, end)) {
                int d = (len + 1) / 2 - (len + 3) / 4;
                if (d > surcharge) surcharge = d;
            }
            tokens += surcharge;
            if (nl < 0) break;
            start = nl + 1;
        }
        return tokens;
    }

    private static boolean isDigitDenseLine(String s, int start,
            int end) {
        int digits = 0;
        int nonSpace = 0;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') continue;
            nonSpace++;
            if (c >= '0' && c <= '9') digits++;
        }
        return digits >= 2 && digits * 3 >= nonSpace;
    }

    private static boolean lineHasHttp(String s, int start,
            int end) {
        for (int i = start; i + 4 <= end; i++) {
            char a = s.charAt(i);
            if (a != 'h' && a != 'H') continue;
            if (lowerEq(s.charAt(i + 1), 't')
                    && lowerEq(s.charAt(i + 2), 't')
                    && lowerEq(s.charAt(i + 3), 'p')) {
                return true;
            }
        }
        return false;
    }

    private static boolean lowerEq(char c, char lower) {
        return c == lower || c == (char) (lower - 'a' + 'A');
    }

    // ---- building ----------------------------------------------

    /** Distill a trail's events (oldest first) into a digest.
     *  fallbackGoal names the run when the trail predates the
     *  run record (the answer card's fallback discipline). */
    public static Digest build(List<Map<String, Object>> events,
            String fallbackGoal) {
        Digest out = new Digest();
        if (events == null) events = new ArrayList<Map<String, Object>>();
        AnswerCard card = AnswerCard.fromEvents(events, fallbackGoal);
        String head = buildHead(events, card, fallbackGoal);
        String asks = buildAsks(events);
        out.stopText = buildStopLine(events, card);
        out.headText = head + asks;
        List<Line> lines = buildLines(events);

        // The ladder. Each rung re-renders and re-measures;
        // the first fit wins.
        String text = assembleText(out.headText, lines,
                RESULT_TAIL_FULL, out.stopText);
        if (estTokens(text) <= DIGEST_TOKEN_BUDGET) {
            out.text = text;
            return out;
        }
        out.condensed = true;
        text = assembleText(out.headText, lines, RESULT_TAIL_MIN,
                out.stopText);
        if (estTokens(text) <= DIGEST_TOKEN_BUDGET) {
            out.text = text;
            return out;
        }
        List<Line> collapsed = collapseReads(lines);
        text = assembleText(out.headText, collapsed,
                RESULT_TAIL_MIN, out.stopText);
        if (estTokens(text) <= DIGEST_TOKEN_BUDGET) {
            out.text = text;
            return out;
        }
        List<Line> trimmed = dropMiddle(collapsed);
        text = assembleText(out.headText, trimmed, RESULT_TAIL_MIN,
                out.stopText);
        if (estTokens(text) <= DIGEST_TOKEN_BUDGET) {
            out.text = text;
            return out;
        }
        // The chunk plan: the surviving lines, in order, split
        // into budget-sized chunks, oldest first. Evidence
        // lines ride the chunks too — nothing is dropped here
        // except, past the cap, the NEWEST chunks (the oldest
        // compress first), which chunksDropped reports.
        out.chunked = true;
        List<String> rendered = new ArrayList<String>();
        for (int i = 0; i < trimmed.size(); i++) {
            rendered.add(renderLine(trimmed.get(i), RESULT_TAIL_MIN));
        }
        List<String> all = chunkLines(rendered);
        for (int i = 0; i < all.size() && i < MAX_QA_CHUNKS; i++) {
            out.chunks.add(all.get(i));
        }
        out.chunksDropped = all.size() > MAX_QA_CHUNKS;
        out.text = "";
        return out;
    }

    /** Join a chunk plan and the caller's compressed notes
     *  into the final digest. Notes that still overflow the
     *  budget are shortened from the end (whole notes first) —
     *  the skeleton itself (header, facts, questions, stop) is
     *  never cut. */
    public static Digest assemble(Digest plan, List<String> notes) {
        Digest out = new Digest();
        out.condensed = true;
        out.chunked = true;
        out.chunksDropped = plan.chunksDropped;
        out.headText = plan.headText;
        out.stopText = plan.stopText;
        List<String> kept = new ArrayList<String>();
        if (notes != null) kept.addAll(notes);
        while (true) {
            StringBuilder sb = new StringBuilder();
            sb.append(plan.headText);
            sb.append("Step notes (compressed from the record):\n");
            for (int i = 0; i < kept.size(); i++) {
                sb.append(kept.get(i)).append("\n");
            }
            sb.append(plan.stopText);
            String text = sb.toString();
            if (estTokens(text) <= DIGEST_TOKEN_BUDGET
                    || kept.isEmpty()) {
                out.text = text;
                return out;
            }
            kept.remove(kept.size() - 1);
        }
    }

    private static String assembleText(String headText,
            List<Line> lines, int tailCap, String stopText) {
        StringBuilder sb = new StringBuilder();
        sb.append(headText);
        if (!lines.isEmpty()) {
            sb.append("Steps:\n");
            for (int i = 0; i < lines.size(); i++) {
                sb.append(renderLine(lines.get(i), tailCap))
                        .append("\n");
            }
        }
        sb.append(stopText);
        return sb.toString();
    }

    private static String renderLine(Line ln, int tailCap) {
        if (ln.tail.length() == 0) return ln.head;
        return ln.head + " -> " + cap(ln.tail, tailCap);
    }

    // ---- the ladder's line transforms ---------------------------

    /** Rung (b): runs of consecutive read lines on the same
     *  page become one line naming the count. Evidence lines
     *  and reads of different pages break a run. */
    private static List<Line> collapseReads(List<Line> lines) {
        List<Line> out = new ArrayList<Line>();
        int i = 0;
        while (i < lines.size()) {
            Line ln = lines.get(i);
            if (!ln.readStep) {
                out.add(ln);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < lines.size() && lines.get(j).readStep
                    && lines.get(j).pageKey.equals(ln.pageKey)) {
                j++;
            }
            int count = j - i;
            if (count == 1) {
                out.add(ln);
            } else {
                Line merged = new Line();
                merged.readStep = true;
                merged.pageKey = ln.pageKey;
                merged.pageLabel = ln.pageLabel;
                merged.step = ln.step;
                merged.head = "Steps " + ln.step + "-"
                        + lines.get(j - 1).step + ": read "
                        + ln.pageLabel + " (" + count + " reads)";
                merged.tail = lines.get(j - 1).tail;
                out.add(merged);
            }
            i = j;
        }
        return out;
    }

    /** Rung (c): the middle STEP lines drop (the first 5 and
     *  last 10 survive); evidence lines are not step lines and
     *  are never dropped. The dropped span is marked, so the
     *  record says what it did. */
    private static List<Line> dropMiddle(List<Line> lines) {
        int stepLines = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).evidence) stepLines++;
        }
        if (stepLines <= 15) return lines;
        List<Line> out = new ArrayList<Line>();
        int seen = 0;
        boolean marked = false;
        for (int i = 0; i < lines.size(); i++) {
            Line ln = lines.get(i);
            if (ln.evidence) {
                out.add(ln);
                continue;
            }
            seen++;
            if (seen <= 5 || seen > stepLines - 10) {
                out.add(ln);
            } else if (!marked) {
                marked = true;
                Line marker = new Line();
                marker.head = "\u2026 " + (stepLines - 15)
                        + " step lines condensed \u2026";
                out.add(marker);
            }
        }
        return out;
    }

    /** Split rendered lines into chunks of at most
     *  CHUNK_TOKEN_BUDGET estimated tokens, oldest first. A
     *  single line longer than a chunk rides alone. */
    private static List<String> chunkLines(List<String> rendered) {
        List<String> chunks = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < rendered.size(); i++) {
            String line = rendered.get(i);
            if (cur.length() > 0
                    && estTokens(cur.toString() + line)
                            > CHUNK_TOKEN_BUDGET) {
                chunks.add(cur.toString());
                cur = new StringBuilder();
            }
            cur.append(line).append("\n");
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    // ---- the sections -------------------------------------------

    /** (1) The header: goal, outcome, source, size, brain,
     *  pack, watch verdict — each piece present only when the
     *  record carries it. Then (2) the facts, verbatim, in the
     *  card's own rendered form, with the inherited (seeded)
     *  facts under their own heading: they are evidence the
     *  run stood on, labeled as inherited, never merged. */
    private static String buildHead(List<Map<String, Object>> events,
            AnswerCard card, String fallbackGoal) {
        StringBuilder sb = new StringBuilder();
        sb.append("Run record\n");
        String goal = card != null ? card.goal
                : (fallbackGoal == null ? "" : fallbackGoal);
        String model = "";
        String pack = "";
        Boolean grammar = null;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            if (e == null) continue;
            String type = str(e.get("type"));
            if ("run".equals(type)) {
                if (goal.length() == 0) goal = str(e.get("goal"));
                pack = str(e.get("pack"));
                model = str(e.get("model"));
                Object g = e.get("grammar_setting");
                if (g instanceof Boolean) grammar = (Boolean) g;
            } else if ("run_summary".equals(type)) {
                if (model.length() == 0) {
                    model = str(e.get("model"));
                }
                Object g = e.get("grammar_setting");
                if (grammar == null && g instanceof Boolean) {
                    grammar = (Boolean) g;
                }
            }
        }
        if (goal.length() > 0) sb.append("Goal: ").append(goal)
                .append("\n");
        if (card != null) {
            sb.append("Outcome: ").append(card.outcomeLine)
                    .append("\n");
            if (card.sourceTitle.length() > 0
                    || card.sourceUrl.length() > 0) {
                sb.append("Source: ");
                if (card.sourceTitle.length() > 0) {
                    sb.append(card.sourceTitle);
                }
                String host = Routes.hostOf(card.sourceUrl);
                if (host.length() > 0) {
                    if (card.sourceTitle.length() > 0) {
                        sb.append(" (");
                    }
                    sb.append(host);
                    if (card.sourceTitle.length() > 0) {
                        sb.append(")");
                    }
                }
                sb.append("\n");
            }
            if (card.hasStats) {
                sb.append("Steps: ").append(card.steps)
                        .append(" \u00b7 Seconds: ")
                        .append(card.seconds).append("\n");
            }
        } else {
            sb.append("Outcome: unfinished\n");
        }
        if (model.length() > 0) {
            sb.append("Model: ").append(model).append("\n");
        }
        if (grammar != null) {
            sb.append("Grammar: ").append(grammar.booleanValue()
                    ? "on" : "off").append("\n");
        }
        if (pack.length() > 0) {
            sb.append("Pack: ").append(pack).append("\n");
        }
        if (card != null && card.watchBanner().length() > 0) {
            sb.append(card.watchBanner()).append("\n");
        }
        if (card != null && !card.facts.isEmpty()) {
            sb.append("Facts noted:\n");
            for (int i = 0; i < card.facts.size(); i++) {
                sb.append(card.facts.get(i)).append("\n");
            }
        }
        if (card != null && !card.seededFacts.isEmpty()) {
            sb.append("From the previous step:\n");
            for (int i = 0; i < card.seededFacts.size(); i++) {
                sb.append("- ").append(card.seededFacts.get(i))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /** (3) The questions the run asked and the answers it got,
     *  quoted as they stand — the user's own words are
     *  evidence the answer may lean on. */
    private static String buildAsks(
            List<Map<String, Object>> events) {
        StringBuilder sb = new StringBuilder();
        StringBuilder body = new StringBuilder();
        String pendingQ = null;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            if (e == null) continue;
            String type = str(e.get("type"));
            if ("ask".equals(type)) {
                if (pendingQ != null) {
                    body.append("Q: ").append(pendingQ).append("\n");
                    body.append("(no answer recorded)\n");
                }
                pendingQ = str(e.get("question"));
            } else if ("answer".equals(type)) {
                String q = str(e.get("question"));
                if (q.length() == 0) q = pendingQ == null
                        ? "" : pendingQ;
                if (q.length() > 0) {
                    body.append("Q: ").append(q).append("\n");
                }
                body.append("You: ").append(str(e.get("answer")))
                        .append("\n");
                pendingQ = null;
            }
        }
        if (pendingQ != null) {
            body.append("Q: ").append(pendingQ).append("\n");
            body.append("(no answer recorded)\n");
        }
        if (body.length() > 0) {
            sb.append("Questions and answers:\n").append(body);
        }
        return sb.toString();
    }

    /** (5) The stop line — outside the step-line count, like
     *  every evidence line. */
    private static String buildStopLine(
            List<Map<String, Object>> events, AnswerCard card) {
        if (card != null) {
            return "Stop: " + card.outcomeLine + "\n";
        }
        return "";
    }

    /** (4) The step lines, from the execute/result spine, with
     *  the evidence events interleaved in trail order. */
    private static List<Line> buildLines(
            List<Map<String, Object>> events) {
        List<Line> out = new ArrayList<Line>();
        Line pending = null;
        String pageUrl = "";
        String pageTitle = "";
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            if (e == null) continue;
            String type = str(e.get("type"));
            long step = num(e.get("step"));
            if ("perceive".equals(type)) {
                pageUrl = str(e.get("url"));
                pageTitle = str(e.get("title"));
            } else if ("execute".equals(type)) {
                if (pending != null) {
                    pending.tail = "no result recorded";
                    out.add(pending);
                }
                pending = new Line();
                pending.step = (int) step;
                Object a = e.get("action");
                Map<?, ?> action = (a instanceof Map)
                        ? (Map<?, ?>) a : null;
                String kind = action == null ? ""
                        : str(action.get("action"));
                pending.head = "Step " + step + ": "
                        + describeAction(action, e.get("element"));
                if ("read".equals(kind) || "read_file".equals(kind)) {
                    pending.readStep = true;
                    pending.pageKey = pageUrl;
                    pending.pageLabel = pageLabel(pageTitle, pageUrl);
                }
            } else if ("result".equals(type)) {
                if (pending != null) {
                    pending.tail = str(e.get("result"));
                    out.add(pending);
                    pending = null;
                }
            } else if ("read".equals(type)) {
                Line ln = new Line();
                ln.step = (int) step;
                ln.readStep = true;
                ln.pageKey = pageUrl;
                ln.pageLabel = pageLabel(pageTitle, pageUrl);
                ln.head = "Step " + step + ": auto-read of "
                        + ln.pageLabel;
                ln.tail = str(e.get("result"));
                out.add(ln);
            } else if ("note".equals(type)) {
                Line ln = evidenceLine(step, "note -> "
                        + noteStatusWord(str(e.get("status"))));
                out.add(ln);
            } else if ("ask".equals(type)) {
                out.add(evidenceLine(step, "asked you: "
                        + cap(str(e.get("question")),
                                RESULT_TAIL_FULL)));
            } else if ("answer".equals(type)) {
                out.add(evidenceLine(step, "you answered: "
                        + cap(str(e.get("answer")),
                                RESULT_TAIL_FULL)));
            } else if ("approval".equals(type)) {
                Object a = e.get("action");
                String kind = (a instanceof Map)
                        ? str(((Map<?, ?>) a).get("action")) : "";
                boolean ok = Boolean.TRUE.equals(e.get("approved"));
                out.add(evidenceLine(step, "approval asked ("
                        + (kind.length() > 0 ? kind : "action")
                        + ") -> " + (ok ? "approved" : "denied")));
            } else if ("govern".equals(type)) {
                if ("deny".equals(str(e.get("outcome")))) {
                    out.add(evidenceLine(step, "the gate denied "
                            + str(e.get("classification")) + " ("
                            + cap(str(e.get("reason")), 80) + ")"));
                }
            } else if ("guard".equals(type)) {
                Object a = e.get("action");
                String kind = (a instanceof Map)
                        ? str(((Map<?, ?>) a).get("action")) : "";
                out.add(evidenceLine(step, "guard corrected "
                        + (kind.length() > 0 ? kind : "an action")
                        + " \u2014 " + cap(str(e.get("reason")),
                                RESULT_TAIL_FULL)
                        + " (not executed)"));
            } else if ("download".equals(type)) {
                Line ln = new Line();
                ln.step = (int) step;
                ln.head = "Step " + step + ": downloaded "
                        + str(e.get("filename")) + " ("
                        + str(e.get("status")) + ")";
                out.add(ln);
            } else if ("tab_opened".equals(type)) {
                Line ln = new Line();
                ln.step = (int) step;
                ln.head = "Step " + step + ": opened a new tab";
                out.add(ln);
            } else if ("tab_switched".equals(type)) {
                Line ln = new Line();
                ln.step = (int) step;
                ln.head = "Step " + step + ": switched tab";
                out.add(ln);
            } else if ("tab_closed".equals(type)) {
                Line ln = new Line();
                ln.step = (int) step;
                ln.head = "Step " + step + ": closed a tab";
                out.add(ln);
            }
        }
        if (pending != null) {
            pending.tail = "no result recorded";
            out.add(pending);
        }
        return out;
    }

    private static Line evidenceLine(long step, String text) {
        Line ln = new Line();
        ln.step = (int) step;
        ln.evidence = true;
        ln.head = "Step " + step + ": " + text;
        return ln;
    }

    /** An executed action in short words, from the recorded
     *  action map and element descriptor: kind, then the
     *  target's number/name, a typed text, or a URL's host. */
    private static String describeAction(Map<?, ?> action,
            Object element) {
        if (action == null) return "action";
        String kind = str(action.get("action"));
        if (kind.length() == 0) kind = "action";
        StringBuilder sb = new StringBuilder(kind);
        if (element instanceof Map) {
            Map<?, ?> el = (Map<?, ?>) element;
            Object number = el.get("number");
            String name = str(el.get("name"));
            if (number instanceof Number) {
                sb.append(" #").append(((Number) number).longValue());
            }
            if (name.length() > 0) {
                sb.append(" '").append(cap(name, 40)).append("'");
            }
        }
        String text = str(action.get("text"));
        if (text.length() > 0) {
            sb.append(" '").append(cap(text, 40)).append("'");
        }
        String url = str(action.get("url"));
        if (url.length() > 0) {
            String host = Routes.hostOf(url);
            sb.append(" ").append(host.length() > 0 ? host : url);
        }
        String query = str(action.get("query"));
        if (query.length() > 0) {
            sb.append(" '").append(cap(query, 40)).append("'");
        }
        return sb.toString();
    }

    private static String pageLabel(String title, String url) {
        if (title != null && title.length() > 0) {
            return "'" + cap(title, 40) + "'";
        }
        String host = Routes.hostOf(url == null ? "" : url);
        return host.length() > 0 ? host : "the page";
    }

    private static String noteStatusWord(String status) {
        if (status.length() == 0 || Scratchpad.ADDED.equals(status)) {
            return "noted";
        }
        if (Scratchpad.DUPLICATE.equals(status)) {
            return "already noted";
        }
        if (Scratchpad.FULL.equals(status)) {
            return "notebook full \u2014 not added";
        }
        return "not added";
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        if (t.length() > max) {
            t = t.substring(0, max) + "\u2026";
        }
        return t;
    }

    private static long num(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
