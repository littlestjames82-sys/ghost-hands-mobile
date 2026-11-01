package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.20 (R10): RUN-COMPARE — two closed trails, distilled
 * side by side. Pure and read-only: the inputs are the same
 * parsed events the answer card reads, the outputs are rows,
 * and nothing here mutates, persists, or judges. There is
 * deliberately NO winner: no score, no "better", only the two
 * records and where they differ.
 *
 * The shared core is TrailStats.factsFromEvents (finished,
 * outcome, steps, last time) — reused exactly as History and
 * the board reuse it, never edited and never re-derived. The
 * rest is a second pass over the events for what TrailStats
 * does not carry.
 *
 * Honesty rules, pinned:
 *  - A tally a trail does not carry (an old trail's prompt
 *    tokens, a run that never wrote a summary) renders "not
 *    recorded" — never 0. A counted family (reads, notes,
 *    approvals...) is different: the whole trail was read, so
 *    an absent family honestly counts 0.
 *  - An unfinished side still distills: its outcome reads
 *    "unfinished" and its partial counts stand.
 *  - The share text names sources the way speech does (title
 *    or host) — never a raw URL.
 */
public final class RunCompare {

    public static final String NOT_RECORDED = "not recorded";

    /** One side of the comparison, distilled. */
    public static final class Side {
        public String goal = "";
        public boolean hasRunEvent = false;
        /** Run-record fields; "" / null when the trail predates
         *  the run record — rendered "not recorded". */
        public String pack = "";
        public String model = "";
        public Boolean grammar = null;
        public boolean finished = false;
        public String outcome = "";
        public String outcomeLine = "unfinished";
        public Long steps = null;
        public Long seconds = null;
        public Long promptTokens = null;
        public Long reusedTokens = null;
        public Long genTokens = null;
        public Long reusePercent = null;
        public int reads = 0;
        public int notes = 0;
        public int asks = 0;
        public int answers = 0;
        public int approvalsAsked = 0;
        public int approvalsDenied = 0;
        public int guardCorrections = 0;
        public int downloads = 0;
        public int tabsOpened = 0;
        /** Landed facts (note events), normalised by the
         *  notebook's own rule, trail order, duplicates once. */
        public final List<String> facts = new ArrayList<String>();
        public String watchVerdict = "";
        public final List<String> watchDiff = new ArrayList<String>();
        public long watchSinceMs = 0;
        public boolean hasScreenshot = false;
        public String sourceTitle = "";
        public String sourceUrl = "";
        public long lastTsMs = 0;

        /** The source named the spoken way: the page title
         *  when there is one, else the URL's host — the
         *  SpeakText.sourceName discipline (never a raw URL). */
        public String sourceName() {
            if (sourceTitle != null && sourceTitle.length() > 0) {
                return sourceTitle;
            }
            if (sourceUrl != null && sourceUrl.length() > 0) {
                String host = Routes.hostOf(sourceUrl);
                if (host.length() > 0) return host;
            }
            return NOT_RECORDED;
        }
    }

    /** One diff row: a label, the two rendered values, whether
     *  they differ, and — for steps and seconds only — the
     *  signed delta B − A ("+3", "-2", "" when either side is
     *  not recorded). */
    public static final class Row {
        public final String label;
        public final String a;
        public final String b;
        public final boolean differs;
        public final String delta;

        public Row(String label, String a, String b,
                boolean differs, String delta) {
            this.label = label;
            this.a = a;
            this.b = b;
            this.differs = differs;
            this.delta = delta;
        }
    }

    /** The fact set work for two runs of the SAME goal. */
    public static final class FactSets {
        public final List<String> onlyA = new ArrayList<String>();
        public final List<String> onlyB = new ArrayList<String>();
        public final List<String> both = new ArrayList<String>();
    }

    private RunCompare() { }

    /** Distill one side from its trail's events (oldest
     *  first). fallbackGoal names the run when the trail
     *  predates the run record (the answer card's own
     *  fallback discipline). */
    public static Side distill(List<Map<String, Object>> events,
            String fallbackGoal) {
        Side s = new Side();
        if (events == null) return s;
        TrailStats.RunFacts core = TrailStats.factsFromEvents(events);
        s.finished = core.finished;
        s.outcome = core.outcome == null ? "" : core.outcome;
        s.steps = core.steps;
        s.lastTsMs = core.lastTsMs;
        String stopSummary = "";
        Map<String, Object> lastPerceive = null;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> e = events.get(i);
            if (e == null) continue;
            String type = str(e.get("type"));
            if ("run".equals(type)) {
                s.hasRunEvent = true;
                s.goal = str(e.get("goal"));
                s.pack = str(e.get("pack"));
                s.model = str(e.get("model"));
                Object g = e.get("grammar_setting");
                if (g instanceof Boolean) s.grammar = (Boolean) g;
            } else if ("run_summary".equals(type)) {
                s.seconds = num(e.get("seconds"));
                s.promptTokens = num(e.get("prompt_tokens"));
                s.reusedTokens = num(e.get("reused_tokens"));
                s.genTokens = num(e.get("gen_tokens"));
                s.reusePercent = num(e.get("reuse_percent"));
                if (s.model.length() == 0) {
                    s.model = str(e.get("model"));
                }
                Object g = e.get("grammar_setting");
                if (s.grammar == null && g instanceof Boolean) {
                    s.grammar = (Boolean) g;
                }
            } else if ("stop".equals(type)) {
                stopSummary = str(e.get("summary"));
            } else if ("perceive".equals(type)) {
                lastPerceive = e;
            } else if ("execute".equals(type)) {
                Object a = e.get("action");
                if (a instanceof Map) {
                    String kind = str(((Map<?, ?>) a).get("action"));
                    if ("read".equals(kind) || "read_file".equals(kind)) {
                        s.reads++;
                    }
                }
            } else if ("read".equals(type)) {
                s.reads++;
            } else if ("note".equals(type)) {
                Object status = e.get("status");
                if (status == null || Scratchpad.ADDED.equals(
                        String.valueOf(status))) {
                    s.notes++;
                    String fact = Scratchpad.normalise(
                            str(e.get("fact")));
                    if (fact != null && fact.length() > 0
                            && !s.facts.contains(fact)) {
                        s.facts.add(fact);
                    }
                }
            } else if ("ask".equals(type)) {
                s.asks++;
            } else if ("answer".equals(type)) {
                s.answers++;
            } else if ("approval".equals(type)) {
                s.approvalsAsked++;
                if (Boolean.FALSE.equals(e.get("approved"))) {
                    s.approvalsDenied++;
                }
            } else if ("guard".equals(type)) {
                s.guardCorrections++;
            } else if ("download".equals(type)) {
                s.downloads++;
            } else if ("tab_opened".equals(type)) {
                s.tabsOpened++;
            } else if ("watch".equals(type)) {
                s.watchVerdict = str(e.get("verdict"));
                s.watchDiff.clear();
                Object diff = e.get("diff");
                if (diff instanceof List) {
                    for (Object d : (List<?>) diff) {
                        if (d != null) {
                            s.watchDiff.add(String.valueOf(d));
                        }
                    }
                }
                Object since = e.get("since_ms");
                s.watchSinceMs = (since instanceof Number)
                        ? ((Number) since).longValue() : 0;
            } else if ("screenshot".equals(type)) {
                if (str(e.get("file")).length() > 0) {
                    s.hasScreenshot = true;
                }
            }
        }
        if (lastPerceive != null) {
            s.sourceUrl = str(lastPerceive.get("url"));
            s.sourceTitle = str(lastPerceive.get("title"));
        }
        if (s.goal.length() == 0 && fallbackGoal != null) {
            s.goal = fallbackGoal;
        }
        if (s.finished) {
            s.outcomeLine = AnswerCard.outcomeLine(s.outcome,
                    stopSummary);
        } else {
            s.outcomeLine = "unfinished";
        }
        return s;
    }

    /** "Same goal" is exact equality of the two runs' goal
     *  texts — the fact set work only makes sense then. */
    public static boolean sameGoal(Side a, Side b) {
        return a != null && b != null && a.goal.length() > 0
                && a.goal.equals(b.goal);
    }

    /** The set work: a fact is "in both" only when its
     *  normalised text matches — the notebook's own rule,
     *  applied as written (trim + cap, exact equality). */
    public static FactSets factSets(Side a, Side b) {
        FactSets out = new FactSets();
        for (int i = 0; i < a.facts.size(); i++) {
            String f = a.facts.get(i);
            if (b.facts.contains(f)) out.both.add(f);
            else out.onlyA.add(f);
        }
        for (int i = 0; i < b.facts.size(); i++) {
            String f = b.facts.get(i);
            if (!a.facts.contains(f)) out.onlyB.add(f);
        }
        return out;
    }

    /** The scalar diff, in a stable order. Steps and seconds
     *  carry the signed delta; everything else is two values
     *  and a differs flag. */
    public static List<Row> diff(Side a, Side b) {
        List<Row> rows = new ArrayList<Row>();
        rows.add(new Row("Outcome", a.outcomeLine, b.outcomeLine,
                !a.outcomeLine.equals(b.outcomeLine), ""));
        rows.add(textRow("Pack", a.pack, b.pack));
        rows.add(textRow("Model", a.model, b.model));
        rows.add(new Row("Grammar", grammarLabel(a.grammar),
                grammarLabel(b.grammar),
                !grammarLabel(a.grammar).equals(grammarLabel(b.grammar)),
                ""));
        rows.add(numRow("Steps", a.steps, b.steps, ""));
        rows.add(numRow("Seconds", a.seconds, b.seconds, ""));
        rows.add(numRow("Prompt tokens", a.promptTokens,
                b.promptTokens, ""));
        rows.add(numRow("Reused tokens", a.reusedTokens,
                b.reusedTokens, ""));
        rows.add(numRow("Generated tokens", a.genTokens,
                b.genTokens, ""));
        rows.add(numRow("Reuse", a.reusePercent, b.reusePercent, "%"));
        rows.add(countRow("Reads", a.reads, b.reads));
        rows.add(countRow("Notes", a.notes, b.notes));
        rows.add(countRow("Questions asked", a.asks, b.asks));
        rows.add(countRow("Answers given", a.answers, b.answers));
        rows.add(countRow("Approvals asked", a.approvalsAsked,
                b.approvalsAsked));
        rows.add(countRow("Approvals denied", a.approvalsDenied,
                b.approvalsDenied));
        rows.add(countRow("Guard corrections", a.guardCorrections,
                b.guardCorrections));
        rows.add(countRow("Downloads", a.downloads, b.downloads));
        rows.add(countRow("Tabs opened", a.tabsOpened, b.tabsOpened));
        rows.add(new Row("Watch", watchLabel(a), watchLabel(b),
                !a.watchVerdict.equals(b.watchVerdict)
                        || a.watchSinceMs != b.watchSinceMs, ""));
        rows.add(new Row("Source", a.sourceName(), b.sourceName(),
                !a.sourceName().equals(b.sourceName()), ""));
        rows.add(new Row("Screenshot", a.hasScreenshot ? "yes" : "no",
                b.hasScreenshot ? "yes" : "no",
                a.hasScreenshot != b.hasScreenshot, ""));
        return rows;
    }

    /** The whole comparison as shareable plain text: the two
     *  records side by side, differences marked, facts by set
     *  when the goals match (per-side lists when they do not),
     *  sources named by title or host — never a raw URL. */
    public static String shareText(String labelA, Side a,
            String labelB, Side b) {
        StringBuilder sb = new StringBuilder();
        sb.append("Run comparison\n");
        sb.append("A: ").append(labelA).append("\n");
        sb.append("B: ").append(labelB).append("\n\n");
        List<Row> rows = diff(a, b);
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            sb.append(r.label).append(": A ").append(r.a)
                    .append(" \u00b7 B ").append(r.b);
            if (r.delta.length() > 0 && !"0".equals(r.delta)) {
                sb.append(" (").append(r.delta).append(")");
            }
            if (r.differs) sb.append(" *");
            sb.append("\n");
        }
        sb.append("\n");
        if (sameGoal(a, b)) {
            FactSets sets = factSets(a, b);
            appendFacts(sb, "Facts only in A", sets.onlyA);
            appendFacts(sb, "Facts only in B", sets.onlyB);
            appendFacts(sb, "Facts in both", sets.both);
        } else {
            appendFacts(sb, "Facts from A", a.facts);
            appendFacts(sb, "Facts from B", b.facts);
        }
        return sb.toString();
    }

    private static void appendFacts(StringBuilder sb, String head,
            List<String> facts) {
        sb.append(head).append(":\n");
        if (facts.isEmpty()) {
            sb.append("- none\n");
            return;
        }
        for (int i = 0; i < facts.size(); i++) {
            sb.append("- ").append(facts.get(i)).append("\n");
        }
    }

    // ---- row helpers ------------------------------------------

    private static Row textRow(String label, String av, String bv) {
        String a = (av == null || av.length() == 0)
                ? NOT_RECORDED : av;
        String b = (bv == null || bv.length() == 0)
                ? NOT_RECORDED : bv;
        return new Row(label, a, b, !a.equals(b), "");
    }

    private static Row numRow(String label, Long av, Long bv,
            String suffix) {
        String a = av == null ? NOT_RECORDED
                : String.valueOf(av.longValue()) + suffix;
        String b = bv == null ? NOT_RECORDED
                : String.valueOf(bv.longValue()) + suffix;
        String delta = "";
        if (av != null && bv != null) {
            long d = bv.longValue() - av.longValue();
            delta = d > 0 ? "+" + d : String.valueOf(d);
        }
        boolean differs = (av == null) != (bv == null)
                || (av != null && bv != null
                        && av.longValue() != bv.longValue());
        return new Row(label, a, b, differs, delta);
    }

    private static Row countRow(String label, int av, int bv) {
        return new Row(label, String.valueOf(av), String.valueOf(bv),
                av != bv, "");
    }

    private static String grammarLabel(Boolean g) {
        if (g == null) return NOT_RECORDED;
        return g.booleanValue() ? "on" : "off";
    }

    /** The watch row in the banner's short vocabulary, or its
     *  honest absence. */
    private static String watchLabel(Side s) {
        if (s.watchVerdict.length() == 0) return "not watched";
        StringBuilder sb = new StringBuilder(s.watchVerdict);
        if (s.watchSinceMs > 0) {
            sb.append(" since ").append(
                    AnswerCard.watchTimeLabel(s.watchSinceMs));
        }
        if (WatchCompare.CHANGED.equals(s.watchVerdict)
                && !s.watchDiff.isEmpty()) {
            sb.append(" \u2014 ").append(s.watchDiff.get(0));
        }
        return sb.toString();
    }

    private static Long num(Object o) {
        return (o instanceof Number)
                ? Long.valueOf(((Number) o).longValue()) : null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
