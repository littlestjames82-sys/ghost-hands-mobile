package com.ghosthands.app;

/**
 * The navigation scheme policy (v3.32) — a page in the
 * WebView may navigate itself (a link tap the page scripts,
 * a redirect chain, a meta refresh) without the agent ever
 * proposing an action, so the Governor never sees those
 * navigations. Through v3.31 the WebViewClient's
 * shouldOverrideUrlLoading returned false for everything —
 * "stay in our WebView" — which let any scheme ride: a page
 * could send the view at file:, javascript:, data:,
 * content: or an intent: and the client waved it through.
 * This class is the deterministic half of the fix: given
 * the URL a navigation names, it answers which SCHEME it
 * carries and whether that scheme may proceed at all.
 *
 * The allowed set, deliberately tiny:
 *   - http: and https: — the web the agent browses.
 *   - about: — the app's own blank page (about:blank is the
 *     view's resting state and a run's opening perception).
 * Everything else is refused: file:, javascript:, data:,
 * content:, blob:, intent:, ftp:, and anything scheme-less
 * or unparseable. data:/blob: DOWNLOADS are not navigations
 * and are untouched — they still arrive through the
 * DownloadListener and are judged there, as before.
 *
 * Scope boundary, stated plainly: this is SCHEME policy
 * only. Which HOSTS an agent action may touch remains the
 * Governor's domain policy, judged per action exactly as
 * before; a page-initiated navigation to an http(s) host
 * the pack would deny the agent is not re-judged here —
 * the page is already loaded in the view, and its own
 * navigations inside the web's schemes are the browser's
 * ordinary life. What this closes is the scheme escape:
 * the view leaving the web's schemes entirely.
 *
 * Evasion handling mirrors the URL standard's parsing:
 * leading whitespace and C0 control characters are stripped
 * before the scheme is read, and tab / newline / carriage
 * return are removed ANYWHERE in the string first (a
 * "java<TAB>script:" is javascript: to a browser, so it is
 * javascript: here too). The scheme is matched
 * case-insensitively and must be a legal scheme (a letter,
 * then letters / digits / '+' / '-' / '.') — anything else
 * reads as no scheme, and no scheme is refused.
 *
 * Pure: no Android, no I/O, no state. MainActivity's
 * WebViewClient consults allowed() and cancels (and feeds)
 * what it refuses.
 */
public final class NavScheme {

    private NavScheme() { }

    /**
     * The normalized scheme of a URL — lowercased, with the
     * browser's own evasions already folded away — or ""
     * when the string carries no legal scheme (null, empty,
     * scheme-relative, or a scheme-shaped prefix that is not
     * a legal scheme).
     */
    public static String schemeOf(String url) {
        if (url == null) return "";
        // The URL standard removes ASCII tab and newline
        // anywhere in the string before parsing.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            sb.append(c);
        }
        String s = sb.toString();
        // ... and strips leading whitespace / C0 controls.
        int start = 0;
        while (start < s.length() && s.charAt(start) <= ' ') {
            start++;
        }
        int colon = s.indexOf(':', start);
        if (colon <= start) return "";
        for (int i = start; i < colon; i++) {
            char c = s.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z');
            boolean rest = (c >= '0' && c <= '9')
                    || c == '+' || c == '-' || c == '.';
            if (i == start ? !letter : !(letter || rest)) {
                return "";
            }
        }
        return s.substring(start, colon).toLowerCase(
                java.util.Locale.US);
    }

    /** May a page-initiated navigation to this URL proceed?
     *  Only http:, https: and about: may — see the class doc. */
    public static boolean allowed(String url) {
        String scheme = schemeOf(url);
        return "http".equals(scheme) || "https".equals(scheme)
                || "about".equals(scheme);
    }
}
