package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.18 (Everything Train R9 — goal chaining): chains of saved
 * goals, and the state of a chain run in progress.
 *
 * A chain is data only: a name and 2..5 steps, each a goal text
 * (which may carry the {prev} placeholder) plus the pack that
 * step runs under when started manually. Chains are a MANUAL
 * flow, never scheduled — every step is tapped, governed, and
 * approved on its own merits; nothing runs because a previous
 * step finished. Chaining adds NO planner action: the previous
 * run's evidence enters the next run through the two channels
 * that already exist — the goal text ({prev} replaced by the
 * previous step's outcome line, host-side, before the goal
 * reaches the field or the Agent) and the Scratchpad (the
 * previous run's noted facts, seeded through Scratchpad.seedIn
 * under a "previous step" provenance stamp, after a chain_seed
 * trail event records them).
 *
 * Only a DONE run seeds. A step that ended any other way
 * captures nothing and offers no continuation — its findings
 * fallback is labelled unconfirmed on its own card and must
 * not launder into a later goal's evidence.
 *
 * The store mirrors SavedGoals' pattern: pure, one JSON prefs
 * value, tolerant parse (a corrupt value parses to an empty
 * store; a malformed chain costs the chain, never the store).
 * The chain-run state lives in its own prefs value: which
 * chain, the NEXT step to run, the completed steps' trail
 * file names, and the seed snapshot the next step inherits.
 */
public final class GoalChains {

    /** The preferences key the chain definitions live under. */
    public static final String PREF_CHAINS = "goal_chains";

    /** The preferences key the chain-run state lives under. */
    public static final String PREF_CHAIN_RUN = "goal_chain_run";

    /** A chain's step cap: a sequence of attended runs, not a
     *  program — five is a morning's errands, not a script. */
    public static final int MAX_STEPS_CHAIN = 5;

    /** The placeholder a step's goal text may carry for the
     *  previous step's answer. */
    public static final String PREV = "{prev}";

    // ---------------- one chain ----------------

    public static final class Step {
        public String goal;
        public String packId;

        public Step() { }

        public Step(String goal, String packId) {
            this.goal = goal;
            this.packId = packId;
        }
    }

    public static final class Chain {
        public String name;
        public final List<Step> steps = new ArrayList<Step>();

        public Chain() { }

        public Chain(String name) {
            this.name = name;
        }
    }

    /**
     * Save-time validation (the editor refuses with the
     * returned message; null means the chain may be saved):
     * a name, 2..MAX steps, no empty step goal, and {prev}
     * never in step 1 — there is no previous answer yet.
     */
    public static String validate(Chain c) {
        if (c == null || c.name == null
                || c.name.trim().length() == 0) {
            return "Give the chain a name first";
        }
        if (c.steps.size() < 2) {
            return "A chain needs at least two steps";
        }
        if (c.steps.size() > MAX_STEPS_CHAIN) {
            return "A chain holds at most " + MAX_STEPS_CHAIN
                    + " steps";
        }
        for (int i = 0; i < c.steps.size(); i++) {
            Step s = c.steps.get(i);
            if (s == null || s.goal == null
                    || s.goal.trim().length() == 0) {
                return "Step " + (i + 1) + " has no goal";
            }
            if (i == 0 && s.goal.contains(PREV)) {
                return "Step 1 cannot use " + PREV
                        + " — there is no previous step yet";
            }
        }
        return null;
    }

    /** The goal a step actually runs: {prev} replaced,
     *  literally and once, by the previous step's outcome
     *  line — or by nothing when there is no seed (a seedless
     *  start proceeds; the model never sees a raw placeholder). */
    public static String substitute(String goalText,
                                    String prevAnswer) {
        if (goalText == null) return "";
        String prev = (prevAnswer == null) ? "" : prevAnswer;
        return goalText.replace(PREV, prev);
    }

    /** The stamp a seeded fact carries: its honest provenance. */
    public static String seedStamp(String fromGoal) {
        return "previous step: "
                + ((fromGoal == null) ? "" : fromGoal);
    }

    /**
     * The seed snapshot a DONE run leaves: the fact TEXTS its
     * card carries (the "note" events with an added verdict —
     * the AnswerCard's own rule), deduped first-sighting,
     * each normalised to the scratchpad's caps, and capped at
     * the scratchpad's capacity with the OLDEST dropped (the
     * newest findings are the ones a follow-up most needs).
     * The chain_seed event records this exact set.
     */
    public static List<String> captureSeedFacts(
            List<Map<String, Object>> events) {
        List<String> out = new ArrayList<String>();
        if (events == null) return out;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            if (!"note".equals(String.valueOf(ev.get("type")))) {
                continue;
            }
            Object statusObj = ev.get("status");
            String status = (statusObj == null) ? ""
                    : String.valueOf(statusObj);
            if (status.length() > 0
                    && !Scratchpad.ADDED.equals(status)) {
                continue;
            }
            Object f = ev.get("fact");
            if (!(f instanceof String)) continue;
            String fact = Scratchpad.normalise((String) f);
            if (fact == null || fact.length() == 0) continue;
            if (!out.contains(fact)) out.add(fact);
        }
        while (out.size() > Scratchpad.MAX_FACTS) {
            out.remove(0);
        }
        return out;
    }

    /** The (fact, stamp) pairs the Agent seeds from a run
     *  state: every captured fact under the one stamp. */
    public static List<String[]> seedPairs(ChainRun run) {
        List<String[]> pairs = new ArrayList<String[]>();
        if (run == null) return pairs;
        String stamp = seedStamp(run.seedFromGoal);
        for (int i = 0; i < run.seedFacts.size(); i++) {
            pairs.add(new String[]{run.seedFacts.get(i), stamp});
        }
        return pairs;
    }

    // ---------------- the store ----------------

    private final List<Chain> chains = new ArrayList<Chain>();

    public GoalChains() { }

    public int size() { return chains.size(); }

    public List<Chain> all() {
        return new ArrayList<Chain>(chains);
    }

    public Chain find(String name) {
        if (name == null) return null;
        for (int i = 0; i < chains.size(); i++) {
            if (name.equals(chains.get(i).name)) {
                return chains.get(i);
            }
        }
        return null;
    }

    /** Add a chain, replacing any chain of the same name
     *  (the replacement keeps the original's position). */
    public void put(Chain c) {
        if (c == null || c.name == null) return;
        for (int i = 0; i < chains.size(); i++) {
            if (c.name.equals(chains.get(i).name)) {
                chains.set(i, c);
                return;
            }
        }
        chains.add(c);
    }

    public boolean remove(String name) {
        if (name == null) return false;
        for (int i = 0; i < chains.size(); i++) {
            if (name.equals(chains.get(i).name)) {
                chains.remove(i);
                return true;
            }
        }
        return false;
    }

    public String serialize() {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < chains.size(); i++) {
            Chain c = chains.get(i);
            Map<String, Object> m =
                    new LinkedHashMap<String, Object>();
            m.put("name", c.name);
            List<Object> steps = new ArrayList<Object>();
            for (int j = 0; j < c.steps.size(); j++) {
                Step s = c.steps.get(j);
                Map<String, Object> sm =
                        new LinkedHashMap<String, Object>();
                sm.put("goal", s.goal);
                sm.put("pack", s.packId);
                steps.add(sm);
            }
            m.put("steps", steps);
            out.add(m);
        }
        return Json.write(out);
    }

    public static GoalChains parse(String json) {
        GoalChains store = new GoalChains();
        if (json == null || json.trim().length() == 0) {
            return store;
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Exception e) {
            return store;
        }
        if (!(parsed instanceof List)) return store;
        List<?> arr = (List<?>) parsed;
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (!(item instanceof Map)) continue;
            Chain c = chainFromMap((Map<?, ?>) item);
            if (c != null) store.put(c);
        }
        return store;
    }

    /** One chain from its record, or null when the record is
     *  not a chain this build may run (no name, step count
     *  outside 2..MAX, an empty step goal). A malformed chain
     *  costs the chain, never the store. */
    private static Chain chainFromMap(Map<?, ?> m) {
        Object name = m.get("name");
        if (!(name instanceof String)
                || ((String) name).trim().length() == 0) {
            return null;
        }
        Object stepsObj = m.get("steps");
        if (!(stepsObj instanceof List)) return null;
        List<?> arr = (List<?>) stepsObj;
        if (arr.size() < 2 || arr.size() > MAX_STEPS_CHAIN) {
            return null;
        }
        Chain c = new Chain(((String) name).trim());
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (!(item instanceof Map)) return null;
            Map<?, ?> sm = (Map<?, ?>) item;
            Object goal = sm.get("goal");
            if (!(goal instanceof String)
                    || ((String) goal).trim().length() == 0) {
                return null;
            }
            Object pack = sm.get("pack");
            Step s = new Step(((String) goal).trim(),
                    (pack instanceof String
                            && ((String) pack).trim().length() > 0)
                            ? (String) pack : "standard");
            c.steps.add(s);
        }
        return c;
    }

    // ---------------- the chain-run state ----------------

    /**
     * A chain run in progress: the completed steps' trail
     * file names (done steps only — a failed step's trail is
     * never listed, so the combined card is built from the
     * steps that actually answered), the index of the NEXT
     * step to run, and the seed snapshot the next step
     * inherits. One chain run at a time; a stored state whose
     * chainName is empty is an ad-hoc follow-up (its step 1
     * was an ordinary run).
     */
    public static final class ChainRun {
        public String chainName = "";
        public int stepIndex = 0;
        public final List<String> stepTrailNames =
                new ArrayList<String>();
        public final List<String> seedFacts =
                new ArrayList<String>();
        public String seedAnswer = "";
        public String seedFromGoal = "";
        public String seedFromTrail = "";
        public String seedSourceTitle = "";
        public String seedSourceUrl = "";

        public ChainRun() { }

        public Map<String, Object> toMap() {
            Map<String, Object> m =
                    new LinkedHashMap<String, Object>();
            m.put("chain", chainName);
            m.put("step", Long.valueOf(stepIndex));
            List<Object> trails = new ArrayList<Object>();
            for (int i = 0; i < stepTrailNames.size(); i++) {
                trails.add(stepTrailNames.get(i));
            }
            m.put("trails", trails);
            List<Object> facts = new ArrayList<Object>();
            for (int i = 0; i < seedFacts.size(); i++) {
                facts.add(seedFacts.get(i));
            }
            m.put("seed_facts", facts);
            m.put("seed_answer", seedAnswer);
            m.put("seed_from_goal", seedFromGoal);
            m.put("seed_from_trail", seedFromTrail);
            m.put("seed_source_title", seedSourceTitle);
            m.put("seed_source_url", seedSourceUrl);
            return m;
        }

        public String serialize() {
            return Json.write(toMap());
        }

        /** Tolerant parse: anything that is not a JSON object
         *  with a trails list is no run at all (null) — the
         *  state is a convenience, never load-bearing. */
        public static ChainRun parse(String json) {
            if (json == null || json.trim().length() == 0) {
                return null;
            }
            Object parsed;
            try {
                parsed = Json.parse(json);
            } catch (Exception e) {
                return null;
            }
            if (!(parsed instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) parsed;
            ChainRun r = new ChainRun();
            r.chainName = str(m.get("chain"));
            Object step = m.get("step");
            r.stepIndex = (step instanceof Number)
                    ? ((Number) step).intValue() : 0;
            if (r.stepIndex < 0) r.stepIndex = 0;
            Object trails = m.get("trails");
            if (trails instanceof List) {
                List<?> arr = (List<?>) trails;
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i) instanceof String) {
                        r.stepTrailNames.add((String) arr.get(i));
                    }
                }
            }
            Object facts = m.get("seed_facts");
            if (facts instanceof List) {
                List<?> arr = (List<?>) facts;
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i) instanceof String) {
                        r.seedFacts.add((String) arr.get(i));
                    }
                }
            }
            r.seedAnswer = str(m.get("seed_answer"));
            r.seedFromGoal = str(m.get("seed_from_goal"));
            r.seedFromTrail = str(m.get("seed_from_trail"));
            r.seedSourceTitle = str(m.get("seed_source_title"));
            r.seedSourceUrl = str(m.get("seed_source_url"));
            return r;
        }

        private static String str(Object o) {
            return (o instanceof String) ? (String) o : "";
        }
    }
}
