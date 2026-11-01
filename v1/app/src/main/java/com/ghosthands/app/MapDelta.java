package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.10 (Everything Train R2 — perception deltas): what the
 * prompt carries per step, decided purely.
 *
 * Perception itself is untouched: the loop still perceives the
 * FULL map every step, and the stash, the heal, the fit ladder
 * and the trail all work from the complete perception exactly
 * as before. Only the prompt's map block changes. The first
 * block a run sends for a page — and any block after a URL
 * change — is the full map, as always. On later steps of the
 * SAME page the block can be a DELTA: the elements added,
 * removed or changed since the last block actually sent, each
 * entry carrying its stable element number (the numbering is
 * the full map's, shared — an element keeps its number for as
 * long as it keeps its identity). A delta that changed nothing
 * is one line.
 *
 * The diff runs over SENT SETS: the elements whose lines were
 * actually in the last full block (the standing render stops
 * at its char budget, so the tail of a long map was never
 * sent; an element past the cut is "added" the first time it
 * enters the sent set, which is the truth from the planner's
 * side of the glass).
 *
 * Safety valves — any one of them sends the full map instead:
 * - the identity (the heal's: tag + name + href for a link,
 *   tag + name + type for anything else) occurs twice in
 *   either sent set: the diff cannot say which twin changed;
 * - a matched pair's NUMBER differs between the two maps:
 *   the numbering the planner holds would be wrong, and a
 *   delta that renumbers silently is worse than no delta;
 * - the delta's own estimated size is not smaller than the
 *   full map's: churn has eaten the saving;
 * - RESYNC_AFTER_DELTAS consecutive deltas have been sent:
 *   a full resync goes out regardless, so a planner that
 *   lost the thread re-grounds on a complete map.
 * The loop also discards this state whenever the fit ladder
 * replaces the block with its own full rendering (rung 3+):
 * what the planner actually saw is then a full map, and the
 * next block must start from a fresh baseline.
 *
 * Element state for change detection is the element's rendered
 * line itself (the standing render at its standing caps) —
 * it carries exactly what the planner sees, including the
 * v3.9 form state (a checkbox's [x], a select's starred
 * option, a radio group's starred choice), so a pure state
 * flip with an unmoved DOM still diffs as a change.
 *
 * v3.24 (the map budget): the FULL block no longer carries
 * the standing render whole. A full map is capped at
 * MAP_BUDGET_TOKENS estimated tokens, filled by
 * budgetSelect: elements whose text overlaps the goal rank
 * first (more overlap, earlier rank; ties keep document
 * order), then the rest in document order, until the budget
 * is spent. Two kinds of element are never trimmed away —
 * the contract floor: anything that overlaps the goal, and
 * the interactive controls (the shaping tiers 0-2: search
 * boxes, inputs, buttons) the planner acts through. The
 * floor is kept even when it alone exceeds the budget; the
 * budget caps the page's long tail of goal-irrelevant
 * links, which is where a dense page's map text actually
 * goes. Perception itself is still untouched (the loop's
 * `rendered` stays the full standing render for the trail
 * and the density judgement); budgetRender is the full
 * block's text, and sentSet is its element set, so the
 * diff runs over exactly what the planner was shown.
 */
public final class MapDelta {

    private MapDelta() { }

    /** The block kinds (the trail's "map_block" values). */
    public static final String FULL = "full";
    public static final String DELTA = "delta";
    public static final String RESYNC = "resync";

    /** Consecutive delta blocks a run may send before a full
     *  resync goes out regardless of the diff. */
    public static final int RESYNC_AFTER_DELTAS = 8;

    /** v3.24: the full map block's budget, in estimated
     *  tokens (Agent.estTokens — the prompt budgeter's own
     *  measure). The standing render it replaces runs to
     *  Eyes.MAP_BUDGET_CHARS (1500 chars, roughly 375-450
     *  est tokens on a link-heavy page); 250 keeps the
     *  goal's elements and the page's controls and cuts
     *  the irrelevant tail. The contract floor (see the
     *  class doc) is exempt: it is kept whole even when it
     *  alone measures over this budget. */
    public static final int MAP_BUDGET_TOKENS = 250;

    /** The whole delta block when nothing changed: one line. */
    public static final String UNCHANGED_TEXT =
            "Map unchanged since the last full map.\n";

    /** The delta block's header when something changed. */
    public static final String HEADER =
            "Map changes since the last full map "
            + "(numbers unchanged):\n";

    /** The loop's delta state for one run: the last block
     *  actually sent (its page, its sent set and their
     *  rendered lines) and how many deltas have ridden on it.
     *  Mutated only by choose() (and reset() when the fit
     *  ladder overrode a block). */
    public static final class State {
        /** The URL of the page the baseline describes; null
         *  until the first block is sent (or after reset()). */
        public String url;
        /** The baseline's sent set, in map order. */
        public List<ElementInfo> sent =
                new ArrayList<ElementInfo>();
        /** sent's rendered lines (parallel to sent), without
         *  trailing newlines. */
        public List<String> sentLines = new ArrayList<String>();
        /** Consecutive DELTA blocks sent on this baseline. */
        public int deltasSinceFull = 0;

        /** Forget the baseline: the next choose() sends a
         *  full map. The loop calls this when the fit ladder
         *  replaced the chosen block with a full rendering —
         *  the planner's picture no longer matches what
         *  choose() believes it sent. */
        public void reset() {
            url = null;
            sent = new ArrayList<ElementInfo>();
            sentLines = new ArrayList<String>();
            deltasSinceFull = 0;
        }
    }

    /** A chosen block: its kind (FULL / DELTA / RESYNC) and
     *  the exact text the prompt carries in its map slot. */
    public static final class Block {
        public final String kind;
        public final String text;

        public Block(String kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /**
     * The identity the diff matches on — the heal's identity
     * (Eyes.identityMatches), as a key: for a link, tag +
     * name + href; for anything else, tag + name + type. The
     * tag is case-folded (identityMatches compares it
     * case-insensitively). Two elements the heal would call
     * the same element share a key, and only then.
     */
    public static String identityKey(ElementInfo e) {
        if (e == null) return "|";
        String tag = (e.tag == null) ? ""
                : e.tag.toLowerCase(java.util.Locale.US);
        if (Eyes.isLink(e)) {
            return tag + "|L|" + nn(e.name) + "|" + nn(e.href);
        }
        return tag + "|C|" + nn(e.name) + "|" + nn(e.type);
    }

    private static String nn(String s) {
        return (s == null) ? "" : s;
    }

    /** One element's rendered map line (no trailing newline),
     *  at the standing render's caps — the same line the full
     *  map carries for it. */
    public static String lineOf(ElementInfo e, String goal) {
        List<ElementInfo> one = new ArrayList<ElementInfo>();
        one.add(e);
        String r = Eyes.renderElements(one, goal, Integer.MAX_VALUE,
                80, Integer.MAX_VALUE);
        if (r.endsWith("\n")) r = r.substring(0, r.length() - 1);
        return r;
    }

    /**
     * v3.24: how much of the goal an element's text carries —
     * the number of significant goal tokens (the guard
     * family's RelevanceGuard.significantTokens) that match
     * the element's own words, judged by the family's
     * nameMatches rule over the text the planner reads: the
     * name, the label, the placeholder and the href. Zero
     * when the goal has no significant tokens (or is null):
     * every element then ranks by document order alone.
     */
    public static int goalOverlap(ElementInfo e, String goal) {
        if (e == null || goal == null) return 0;
        java.util.Set<String> tokens =
                RelevanceGuard.significantTokens(goal);
        if (tokens.isEmpty()) return 0;
        StringBuilder text = new StringBuilder();
        if (e.name != null) text.append(e.name).append(' ');
        if (e.label != null) text.append(e.label).append(' ');
        if (e.placeholder != null) {
            text.append(e.placeholder).append(' ');
        }
        if (e.href != null) text.append(e.href);
        String hay = text.toString();
        int n = 0;
        for (String t : tokens) {
            java.util.Set<String> one =
                    new java.util.HashSet<String>();
            one.add(t);
            if (RelevanceGuard.nameMatches(hay, one)) n++;
        }
        return n;
    }

    /** The contract floor of the map budget: an element the
     *  trim may never drop — it overlaps the goal, or it is
     *  an interactive control of the shaping tiers 0-2
     *  (search boxes, inputs, buttons/selects), the elements
     *  the planner's actions address. Same priority notion
     *  as the fit ladder's reduced set (Eyes.topElements). */
    public static boolean isContractFloor(ElementInfo e, int overlap) {
        return overlap > 0 || Eyes.tierOf(e) <= 2;
    }

    /**
     * v3.24: the elements a full map block shows, chosen
     * against a token budget. Ranking: goal overlap
     * descending, then document order. The contract floor
     * (isContractFloor) is always kept, whole, even when it
     * alone exceeds the budget — the budget never drops the
     * map below what the planner's contract needs. The
     * remaining elements fill in rank order while the kept
     * lines' total estimated tokens stay within the budget
     * (an element that does not fit is skipped, and a later
     * shorter one may still fit). The result is in the
     * map's own order, with the elements' own numbers: a
     * trimmed map never renumbers. A null list selects
     * nothing; a budget under the floor's own cost returns
     * exactly the floor.
     */
    public static List<ElementInfo> budgetSelect(
            List<ElementInfo> els, String goal, int tokenBudget) {
        List<ElementInfo> out = new ArrayList<ElementInfo>();
        if (els == null || els.isEmpty()) return out;
        final int n = els.size();
        final int[] overlap = new int[n];
        final int[] cost = new int[n];
        Integer[] rank = new Integer[n];
        for (int i = 0; i < n; i++) {
            overlap[i] = goalOverlap(els.get(i), goal);
            cost[i] = Agent.estTokens(lineOf(els.get(i), goal));
            rank[i] = Integer.valueOf(i);
        }
        java.util.Arrays.sort(rank, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                int oa = overlap[a.intValue()];
                int ob = overlap[b.intValue()];
                if (oa != ob) return ob - oa;
                return a.intValue() - b.intValue();
            }
        });
        boolean[] kept = new boolean[n];
        int total = 0;
        // The floor first: kept whole, at whatever it costs.
        for (int i = 0; i < n; i++) {
            int idx = rank[i].intValue();
            if (isContractFloor(els.get(idx), overlap[idx])) {
                kept[idx] = true;
                total += cost[idx];
            }
        }
        // Then the tail, in rank order, while it fits.
        for (int i = 0; i < n; i++) {
            int idx = rank[i].intValue();
            if (kept[idx]) continue;
            if (total + cost[idx] <= tokenBudget) {
                kept[idx] = true;
                total += cost[idx];
            }
        }
        for (int i = 0; i < n; i++) {
            if (kept[i]) out.add(els.get(i));
        }
        return out;
    }

    /**
     * v3.24: the full map block's text — the budget-selected
     * elements rendered at the standing caps, in map order,
     * plus the standing truncation line naming how many
     * elements the budget left out. A page the budget trims
     * nothing from renders byte-identical to the standing
     * render (Perception.render): the same lines, and no
     * truncation line when nothing was dropped.
     */
    public static String budgetRender(Eyes.Perception p,
                                      String goal) {
        if (p == null) return "";
        List<ElementInfo> kept = budgetSelect(p.elements, goal,
                MAP_BUDGET_TOKENS);
        String body = Eyes.renderElements(kept, goal,
                Integer.MAX_VALUE, 80, Integer.MAX_VALUE);
        if (kept.size() < p.elements.size()) {
            body += "... truncated: "
                    + (p.elements.size() - kept.size())
                    + " more element(s) not shown\n";
        }
        return body;
    }

    /**
     * The sent set of a perception: the elements its full
     * block actually shows. v3.24: the budget selection
     * (budgetSelect at MAP_BUDGET_TOKENS) — before, the
     * standing render's char-budget prefix; the truth either
     * way is "what the planner was shown", and an element
     * outside the set is "added" the first time it enters.
     */
    public static List<ElementInfo> sentSet(Eyes.Perception p,
                                            String goal) {
        if (p == null) return new ArrayList<ElementInfo>();
        return budgetSelect(p.elements, goal, MAP_BUDGET_TOKENS);
    }

    /** The rendered lines of a sent set (parallel, no trailing
     *  newlines). */
    public static List<String> linesOf(List<ElementInfo> els,
                                       String goal) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < els.size(); i++) {
            out.add(lineOf(els.get(i), goal));
        }
        return out;
    }

    /** Identity key -> index in the set, or null when any key
     *  occurs twice (the diff cannot tell twins apart). */
    private static Map<String, Integer> keyIndex(
            List<ElementInfo> els) {
        Map<String, Integer> m =
                new LinkedHashMap<String, Integer>();
        for (int i = 0; i < els.size(); i++) {
            String k = identityKey(els.get(i));
            if (m.containsKey(k)) return null;
            m.put(k, Integer.valueOf(i));
        }
        return m;
    }

    /** Commit a full block (kind FULL or RESYNC) as the new
     *  baseline and return it. */
    private static Block sendFull(State st, Eyes.Perception p,
                                  List<ElementInfo> cur,
                                  List<String> curLines,
                                  String fullRendered, String kind) {
        st.url = p.url;
        st.sent = cur;
        st.sentLines = curLines;
        st.deltasSinceFull = 0;
        return new Block(kind, fullRendered);
    }

    /**
     * Choose the map block for this step's prompt and advance
     * the state to describe what was sent. `fullRendered` is
     * the current perception's standing full render (what a
     * full block carries); the state's baseline is the last
     * block choose() sent. See the class doc for the valves.
     */
    public static Block choose(State st, Eyes.Perception p,
                               String goal, String fullRendered) {
        List<ElementInfo> cur = sentSet(p, goal);
        List<String> curLines = linesOf(cur, goal);
        // First block for a page (or after a reset, or after
        // any navigation): the full map.
        if (st.url == null || !st.url.equals(p.url)) {
            return sendFull(st, p, cur, curLines, fullRendered, FULL);
        }
        // The periodic resync: a full map regardless.
        if (st.deltasSinceFull >= RESYNC_AFTER_DELTAS) {
            return sendFull(st, p, cur, curLines, fullRendered,
                    RESYNC);
        }
        Map<String, Integer> baseIdx = keyIndex(st.sent);
        Map<String, Integer> curIdx = keyIndex(cur);
        if (baseIdx == null || curIdx == null) {
            // Identity ambiguity in either set.
            return sendFull(st, p, cur, curLines, fullRendered, FULL);
        }
        StringBuilder adds = new StringBuilder();
        StringBuilder changes = new StringBuilder();
        boolean[] matched = new boolean[st.sent.size()];
        for (int j = 0; j < cur.size(); j++) {
            ElementInfo e = cur.get(j);
            Integer bi = baseIdx.get(identityKey(e));
            if (bi == null) {
                adds.append("+ ").append(curLines.get(j))
                        .append('\n');
                continue;
            }
            matched[bi.intValue()] = true;
            ElementInfo before = st.sent.get(bi.intValue());
            if (before.number != e.number) {
                // Numbering mismatch: the numbers the planner
                // holds for this element would be wrong.
                return sendFull(st, p, cur, curLines, fullRendered,
                        FULL);
            }
            if (!st.sentLines.get(bi.intValue())
                    .equals(curLines.get(j))) {
                changes.append("~ ").append(curLines.get(j))
                        .append('\n');
            }
        }
        StringBuilder removes = new StringBuilder();
        for (int i = 0; i < st.sent.size(); i++) {
            if (!matched[i]) {
                removes.append("- ").append(st.sentLines.get(i))
                        .append('\n');
            }
        }
        String text;
        if (adds.length() == 0 && changes.length() == 0
                && removes.length() == 0) {
            text = UNCHANGED_TEXT;
        } else {
            text = HEADER + adds.toString() + changes.toString()
                    + removes.toString();
        }
        // The size valve: a delta that costs what the full map
        // costs buys nothing.
        if (Agent.estTokens(text) >= Agent.estTokens(fullRendered)) {
            return sendFull(st, p, cur, curLines, fullRendered, FULL);
        }
        st.sent = cur;
        st.sentLines = curLines;
        st.deltasSinceFull++;
        return new Block(DELTA, text);
    }
}
