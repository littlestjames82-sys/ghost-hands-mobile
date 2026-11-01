package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.22 (Everything Train R11 — the templates shelf): four
 * starting points for work the user repeats, authored in
 * code — a pre-filled saved goal or page watch with named
 * slots the user fills in.
 *
 * Templates are CONTENT, not user data, so there is
 * deliberately NO templates store: the definitions live
 * here, the shelf is a render of all(), and an instantiated
 * template lands as an ORDINARY record in the existing
 * stores — a SavedGoals record (through its editor, §3.3
 * of the R11 plan) or a PageWatches record (direct write,
 * baseline pending). After instantiation the instance is
 * indistinguishable from a hand-made record and is edited,
 * scheduled, watched, and deleted by the existing machinery.
 *
 * Posture suggestions are suggestions: the suggested pack
 * for every goal template is the Settings default pack (a
 * template proposes a posture, not a policy — the editor
 * shows it and the user can change it before saving), and
 * the watch / schedule suggestions pre-load the bundle the
 * editor receives; they are applied nowhere else.
 *
 * Pure: definitions + the substitution / validation /
 * bundle functions. A catalogue-integrity rule rides the
 * harness sweep: every {placeholder} in every definition
 * has a slot definition, every slot definition is used,
 * ids are unique — a definition bug fails the suite, never
 * the user's tap.
 */
public final class GoalTemplates {

    public static final String KIND_GOAL = "goal";
    public static final String KIND_PAGE_WATCH = "page_watch";

    /** One fill-in field of a template. Caps are enforced
     *  here and in the form alike (the form asks this class
     *  — there is no second validation logic). */
    public static final class Slot {
        public final String key;
        public final String label;
        public final String hint;
        public final int cap;

        public Slot(String key, String label, String hint,
                    int cap) {
            this.key = key;
            this.label = label;
            this.hint = hint;
            this.cap = cap;
        }
    }

    public static final class Template {
        public final String id;
        public final String title;
        public final String blurb;
        public final String kind;
        /** The goal text with {slot} placeholders ("" for a
         *  page watch — its field is the URL slot). */
        public final String goalText;
        public final Slot[] slots;
        public final boolean watchSuggested;
        /** Minutes after midnight, or SavedGoals.NO_SCHEDULE. */
        public final int schedSuggested;

        public Template(String id, String title, String blurb,
                        String kind, String goalText,
                        Slot[] slots, boolean watchSuggested,
                        int schedSuggested) {
            this.id = id;
            this.title = title;
            this.blurb = blurb;
            this.kind = kind;
            this.goalText = goalText;
            this.slots = slots;
            this.watchSuggested = watchSuggested;
            this.schedSuggested = schedSuggested;
        }

        public Slot slot(String key) {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i].key.equals(key)) return slots[i];
            }
            return null;
        }
    }

    /** What the saved-goal editor receives for a GOAL-kind
     *  instantiation: name, goal, pack, and the watch /
     *  schedule suggestions. The mapping itself writes
     *  NOTHING to any store — the store write is the
     *  editor's own Save, with its validation untouched. */
    public static final class EditorBundle {
        public final String name;
        public final String goal;
        public final String packId;
        public final boolean watched;
        public final int schedMinutes;

        public EditorBundle(String name, String goal,
                            String packId, boolean watched,
                            int schedMinutes) {
            this.name = name;
            this.goal = goal;
            this.packId = packId;
            this.watched = watched;
            this.schedMinutes = schedMinutes;
        }
    }

    /** 07:00, the two watched templates' suggested hour. */
    private static final int SEVEN_AM = 7 * 60;

    private static final Template[] ALL = new Template[]{
            new Template(
                    "fare_watch", "Fare watch",
                    "Watch fares between two places and "
                            + "report the cheapest.",
                    KIND_GOAL,
                    "Search for flights from {from} to {to} "
                            + "for around {date}. Report the "
                            + "cheapest fares you find, with "
                            + "the airline and departure times "
                            + "for each.",
                    new Slot[]{
                            new Slot("from", "From",
                                    "e.g. Knoxville", 60),
                            new Slot("to", "To",
                                    "e.g. Denver", 60),
                            new Slot("date", "Date",
                                    "e.g. Nov 14", 40),
                    },
                    true, SEVEN_AM),
            new Template(
                    "forecast", "Forecast",
                    "A daily weather check for a place "
                            + "you name.",
                    KIND_GOAL,
                    "Open the weather forecast for {place} "
                            + "and report today's high, low, "
                            + "and chance of rain.",
                    new Slot[]{
                            new Slot("place", "Place",
                                    "e.g. Oakdale, TN", 60),
                    },
                    true, SEVEN_AM),
            new Template(
                    "restock", "Restock watch",
                    "Watch a product page; its baseline is "
                            + "set on its first check.",
                    KIND_PAGE_WATCH,
                    "",
                    new Slot[]{
                            new Slot("url", "Page address",
                                    "https://…", 500),
                    },
                    false, SavedGoals.NO_SCHEDULE),
            new Template(
                    "headlines", "Headlines",
                    "The top headlines from a news site, "
                            + "right now.",
                    KIND_GOAL,
                    "Open {site} and list the top headlines "
                            + "right now, with a one-line "
                            + "summary of each.",
                    new Slot[]{
                            new Slot("site", "Site",
                                    "e.g. a news site's address",
                                    60),
                    },
                    false, SavedGoals.NO_SCHEDULE),
    };

    private GoalTemplates() { }

    public static List<Template> all() {
        List<Template> out = new ArrayList<Template>();
        for (int i = 0; i < ALL.length; i++) out.add(ALL[i]);
        return out;
    }

    public static Template find(String id) {
        if (id == null) return null;
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].id.equals(id)) return ALL[i];
        }
        return null;
    }

    /**
     * The first validation problem, in slot order, in plain
     * words — or "" when the values are usable. Every slot
     * is required: blank after trim is refused per-slot, and
     * a value over its cap is refused. The form asks this
     * and only this.
     */
    public static String validate(Template t,
                                  Map<String, String> values) {
        if (t == null) return "Pick a template first.";
        for (int i = 0; i < t.slots.length; i++) {
            Slot s = t.slots[i];
            String v = trimmed(values, s.key);
            if (v.length() == 0) {
                return s.label + " is needed.";
            }
            if (v.length() > s.cap) {
                return s.label + " is too long (max " + s.cap
                        + " characters).";
            }
        }
        return "";
    }

    /**
     * Literal substitution: each {key} replaced by its
     * trimmed value. Callers validate first; a missing value
     * substitutes empty rather than inventing text.
     */
    public static String substitute(Template t,
                                    Map<String, String> values) {
        if (t == null) return "";
        String out = t.goalText;
        for (int i = 0; i < t.slots.length; i++) {
            out = out.replace("{" + t.slots[i].key + "}",
                    trimmed(values, t.slots[i].key));
        }
        return out;
    }

    /** The watch URL for a PAGE_WATCH template: its url
     *  slot's trimmed value ("" for a goal template). */
    public static String watchUrl(Template t,
                                  Map<String, String> values) {
        if (t == null || !KIND_PAGE_WATCH.equals(t.kind)) {
            return "";
        }
        return trimmed(values, "url");
    }

    /**
     * The editor bundle for a GOAL template + values, or
     * null when the values do not validate or the template
     * is not a goal template. The pack is the Settings
     * default the caller passes in — a suggestion the
     * editor shows, never a policy this class imposes.
     */
    public static EditorBundle editorBundle(
            Template t, Map<String, String> values,
            String defaultPackId) {
        if (t == null || !KIND_GOAL.equals(t.kind)) return null;
        if (validate(t, values).length() > 0) return null;
        return new EditorBundle(t.title, substitute(t, values),
                (defaultPackId == null || defaultPackId.length() == 0)
                        ? "standard" : defaultPackId,
                t.watchSuggested, t.schedSuggested);
    }

    /**
     * Whether a string is a usable page address — the rule
     * R8's creation uses: http(s) and parseable with a host.
     * An unusable address is a plain refusal at creation;
     * nothing is stored.
     */
    public static boolean isValidUrl(String url) {
        if (url == null || !Downloads.isHttp(url)) return false;
        try {
            java.net.URL u = new java.net.URL(url.trim());
            String h = u.getHost();
            return h != null && h.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String trimmed(Map<String, String> values,
                                  String key) {
        if (values == null) return "";
        String v = values.get(key);
        return (v == null) ? "" : v.trim();
    }
}
