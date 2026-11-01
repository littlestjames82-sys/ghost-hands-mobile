package com.ghosthands.app;

/**
 * v3.22 (Everything Train R11 — share intake): the pure
 * parser behind the share-sheet target, and the launch-extra
 * decision the tile and the intake share.
 *
 * Shared text is another app's data — untrusted until the
 * user reads it. The parser's whole job is to say what
 * arrived, faithfully:
 *
 *  - goalText is the shared text, trimmed, VERBATIM — never
 *    rewritten, summarized, or templated. It lands in the
 *    goal field for review; Start is the user's own tap,
 *    under the ordinary governor.
 *  - url is the FIRST http(s) URL in the text, or null.
 *    Extraction scans for scheme starts (case-insensitive),
 *    takes characters to the next whitespace, strips
 *    trailing punctuation that is never part of a shared
 *    link, and requires the candidate to parse with a host
 *    (the Routes discipline) — a scheme-shaped fragment
 *    with no host is not a URL, and the scan continues past
 *    it. Multiple URLs: the first wins, stated.
 *  - The cap (GOAL_CAP, the goal field's) truncates the
 *    GOAL TEXT only: the URL scan runs on the FULL text
 *    first, so a link past char 2000 is still watchable
 *    even when the prefill truncates.
 *
 * EXTRA_SUBJECT is ignored, on the record: senders
 * duplicate the text there or put a page title in it; the
 * parser's one input is EXTRA_TEXT. EXTRA_STREAM shares are
 * out of scope (a shared file is the SAF picker's job, R8).
 *
 * The intake itself fetches nothing, runs nothing, and
 * schedules nothing — that discipline is the activity's;
 * this class is only the reading of what arrived.
 */
public final class ShareIntake {

    public static final int EMPTY = 0;
    public static final int TEXT = 1;
    public static final int URL_TEXT = 2;

    /** The goal field's cap on an incoming text (the tile's
     *  clipboard read rides the same number). */
    public static final int GOAL_CAP = 2000;

    /** Launch-extra decisions (prefillDecision's verdicts). */
    public static final int LOAD = 0;
    public static final int IGNORE_WITH_NOTE = 1;
    public static final int EMPTY_NOTE = 2;

    /** Characters that end a shared link but are never part
     *  of it: closers and sentence punctuation. */
    private static final String TRAILING = ")]}\"'.,;:!?";

    public static final class Parsed {
        public int kind = EMPTY;
        public String goalText = "";
        public String url = null;
    }

    private ShareIntake() { }

    public static Parsed parse(String extraText) {
        Parsed p = new Parsed();
        if (extraText == null) return p;
        String url = extractUrl(extraText);
        String text = extraText.trim();
        if (text.length() == 0 && url == null) return p;
        p.goalText = (text.length() > GOAL_CAP)
                ? text.substring(0, GOAL_CAP) : text;
        p.url = url;
        p.kind = (url != null) ? URL_TEXT : TEXT;
        return p;
    }

    /**
     * The first usable http(s) URL in the text, or null.
     * Scans the FULL text (never a capped copy), so a link
     * beyond the goal cap is still found.
     */
    public static String extractUrl(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase();
        int from = 0;
        while (from < text.length()) {
            int a = lower.indexOf("http://", from);
            int b = lower.indexOf("https://", from);
            int start;
            if (a < 0) start = b;
            else if (b < 0) start = a;
            else start = Math.min(a, b);
            if (start < 0) return null;
            int end = start;
            while (end < text.length()
                    && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            String candidate = stripTrailing(
                    text.substring(start, end));
            if (candidate.length() > 0 && hasHost(candidate)) {
                return candidate;
            }
            from = Math.max(end, start + 1);
        }
        return null;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '>' || TRAILING.indexOf(c) >= 0) {
                end--;
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /** The Routes discipline: a candidate must parse and
     *  carry a host, or it is not a URL. */
    private static boolean hasHost(String candidate) {
        try {
            java.net.URL u = new java.net.URL(candidate);
            String h = u.getHost();
            return h != null && h.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The launch-extra decision both new sources share
     * (a share prefill, the tile's clipboard read): an
     * incoming text loads only when no run is live (the
     * v3.5 never-disturb invariant — a live run yields the
     * note, never a load); no usable text yields the empty
     * note, live run or not (there is nothing to disturb).
     */
    public static int prefillDecision(boolean runLive,
                                      boolean hasText) {
        if (!hasText) return EMPTY_NOTE;
        if (runLive) return IGNORE_WITH_NOTE;
        return LOAD;
    }

    /** Trim and cap an incoming text for the goal field —
     *  the clipboard read's one transformation (the intake's
     *  parse does its own, above, because its scan precedes
     *  its cap). */
    public static String capForField(String text) {
        if (text == null) return "";
        String t = text.trim();
        return (t.length() > GOAL_CAP)
                ? t.substring(0, GOAL_CAP) : t;
    }
}
