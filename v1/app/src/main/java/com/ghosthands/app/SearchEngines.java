package com.ghosthands.app;

/**
 * v3.6 (READING setting — fallback search engine): the engines
 * the search action's fallback path can aim at when a page has
 * no usable search box of its own, and the pure mapping from an
 * engine choice to the URL the fallback navigates to and the
 * line it reports.
 *
 * The fallback has existed since v1.1 (path (c) of the search
 * action) and pointed at DuckDuckGo's HTML endpoint, hardcoded.
 * The results-page recognition that keeps the loop honest on
 * whatever comes back (RelevanceGuard) has covered DuckDuckGo,
 * Google and Bing since v2.1 — so the choice is exactly the
 * navigation target and its report line, nothing deeper: the
 * planner reads whichever results page arrives through the same
 * perception as any other page.
 *
 * The DuckDuckGo mapping reproduced the pre-v3.6 behaviour
 * byte-for-byte — the same endpoint and the same report string —
 * because it is the default: an install that never touches the
 * setting searches exactly where it always searched. v3.26
 * breaks the URL half of that on purpose: the endpoint gains
 * the kl region pin (&kl=us-en) after the Nov 1, 2026 device
 * trail showed an unpinned fallback search landing on DDG's
 * region-picker page instead of results. The report string is
 * still byte-for-byte.
 *
 * v3.28: the Nov 1, 2026 v3.26 device trail corrected the
 * region story — the picker is not a separate page DDG serves
 * instead of results; it is FURNITURE on the results page
 * itself, a region <select> whose option labels flatten into
 * the page text's head while the same page's element map
 * holds the real result links. So this class now also owns
 * the text-side judgements: regionRunLength / stripRegion-
 * Furniture (the picker's run at a text's head, and that run
 * removed), hasResultLinks (the map's verdict that a page is
 * a results page), and the gated isRegionPage overload that
 * believes the map over the text.
 *
 * Pure: no Android. The Activity owns the WebView navigation;
 * this class only decides where "fallback" points.
 */
public final class SearchEngines {

    public static final String DDG = "duckduckgo";
    public static final String GOOGLE = "google";
    public static final String BING = "bing";

    /** The offered choices, in chip order. */
    public static final String[] NAMES = {DDG, GOOGLE, BING};

    /** The default (the behaviour every build through v3.5 had). */
    public static final String DEFAULT_ID = DDG;

    private SearchEngines() { }

    /** A stored choice resolved to a known engine id: unknown
     *  or absent values are the default. Case-insensitive; the
     *  canonical (lowercase) id is what survives. */
    public static String resolveId(String saved) {
        if (saved != null) {
            String key = saved.trim().toLowerCase(java.util.Locale.US);
            for (int i = 0; i < NAMES.length; i++) {
                if (NAMES[i].equals(key)) return key;
            }
        }
        return DEFAULT_ID;
    }

    /** The chip / report name of an engine id. */
    public static String displayName(String id) {
        String r = resolveId(id);
        if (GOOGLE.equals(r)) return "Google";
        if (BING.equals(r)) return "Bing";
        return "DuckDuckGo";
    }

    /** v3.26: the region the DuckDuckGo fallback pins — US
     *  English, the user's locale (Oakdale, Tennessee) — carried
     *  on every fallback URL so DDG serves results directly
     *  instead of its region-picker interstitial (detection of
     *  that page, the backstop, is isRegionPage below). */
    public static final String DDG_REGION = "us-en";

    /**
     * The results URL the fallback navigates to for an
     * already-URL-encoded query. The DuckDuckGo form is the
     * html. endpoint, which serves a plain results page the
     * perception reads well — verbatim the pre-v3.6 URL through
     * v3.25, and since v3.26 carrying "&kl=" + DDG_REGION (the
     * results-page recognition in RelevanceGuard keys on host
     * + path, so the pin changes nothing there); Google and
     * Bing use their standard search endpoints.
     */
    public static String searchUrl(String id, String encodedQuery) {
        String q = (encodedQuery == null) ? "" : encodedQuery;
        String r = resolveId(id);
        if (GOOGLE.equals(r)) return "https://www.google.com/search?q=" + q;
        if (BING.equals(r)) return "https://www.bing.com/search?q=" + q;
        return "https://html.duckduckgo.com/html/?q=" + q
                + "&kl=" + DDG_REGION;
    }

    /**
     * v3.26: the results URL for a RAW (unencoded) query — this
     * method encodes, with the same URLEncoder WebHands uses.
     * The region recovery's re-issue target: the agent loop
     * holds the last executed query unencoded, so its recovery
     * URL is built here rather than by re-encoding in Agent.
     */
    public static String searchUrlForQuery(String id, String rawQuery) {
        String q = (rawQuery == null) ? "" : rawQuery;
        String enc;
        try {
            enc = java.net.URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            enc = q;
        }
        return searchUrl(id, enc);
    }

    /**
     * v3.26: true when a URL is on DuckDuckGo (any subdomain —
     * the picker has been seen served from the bare domain and
     * the html host alike).
     */
    public static boolean isDdgHost(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URL(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.US);
            return host.equals("duckduckgo.com")
                    || host.endsWith(".duckduckgo.com");
        } catch (Exception e) {
            return false;
        }
    }

    /** v3.26: region names whose JOINT presence with the
     *  picker's "All Regions" header is the page's signature.
     *  The picker lists every region alphabetically from the
     *  top, so its text preview (the head of the page) always
     *  opens with the header and the first names below. */
    private static final String[] REGION_SIGNATURE = {
            "argentina", "australia", "austria", "belgium",
            "brazil", "bulgaria", "canada", "chile", "china",
            "colombia", "croatia", "denmark", "finland",
            "france", "germany", "greece", "hong kong",
            "hungary", "india", "indonesia", "ireland",
            "italy", "japan", "korea", "malaysia", "mexico",
            "netherlands", "new zealand", "norway", "peru",
            "philippines", "poland", "portugal", "romania",
            "russia", "singapore", "south africa", "spain",
            "sweden", "switzerland", "taiwan", "thailand",
            "ukraine", "united kingdom", "united states",
            "vietnam",
    };

    /**
     * v3.26: true when a perception is DuckDuckGo's
     * region-picker page rather than search results. Field
     * evidence — the Nov 1, 2026 device trail
     * trail-20261101-002036: the fallback search executed, the
     * WebView landed on the picker ("All Regions / Argentina /
     * Australia / Austria / Belgium (fr) / ..."), and the
     * planner read that page AS the results (step 8), because
     * the v2.1 results recognition counts a duckduckgo.com URL
     * carrying q= as a results page. The deterministic signal
     * the code affords is the pair: a DuckDuckGo host AND the
     * picker's text signature in the perception's preview —
     * the "all regions" header plus at least three region
     * names. A genuine results page carries neither the header
     * nor the alphabetical run of names at its head.
     *
     * v3.28: the head half of that claim did not survive the
     * v3.26 device trail — a genuine results page DOES carry
     * the header and the run at its head, because the picker
     * is furniture on it (see the class doc). This two-arg
     * form is kept for what it literally judges (the TEXT
     * carries the picker signature); the recovery gate uses
     * the three-arg overload below, which also asks the map.
     */
    public static boolean isRegionPage(String url,
                                       String textPreview) {
        if (!isDdgHost(url)) return false;
        if (textPreview == null) return false;
        String t = textPreview.toLowerCase(java.util.Locale.US);
        if (!t.contains("all regions")) return false;
        int hits = 0;
        for (int i = 0; i < REGION_SIGNATURE.length; i++) {
            if (t.contains(REGION_SIGNATURE[i])) hits++;
        }
        return hits >= 3;
    }

    /**
     * v3.28: the recovery gate's form — a page is the picker,
     * for recovery purposes, only when its text carries the
     * signature AND its element map holds NO result links. A
     * results page wearing the picker as furniture is a
     * results page: the v3.26 trail's step 4 is the evidence
     * (the recovery fired on a page whose map held the
     * AccuWeather result and navigated to the URL the tab was
     * already on — a no-op that spent the query's one
     * recovery and told the planner nothing true).
     */
    public static boolean isRegionPage(String url,
                                       String textPreview,
                                       java.util.List<ElementInfo>
                                               elements) {
        return isRegionPage(url, textPreview)
                && !hasResultLinks(elements);
    }

    /**
     * v3.28: does this element map hold a DuckDuckGo result
     * link — an anchor through DDG's redirect shim
     * (duckduckgo.com/l/?uddg=...)? The html endpoint wraps
     * every result href in the shim, so a shaped map carrying
     * one is a results page whatever its text says. Protocol-
     * relative hrefs are judged as https.
     */
    public static boolean hasResultLinks(
            java.util.List<ElementInfo> elements) {
        if (elements == null) return false;
        for (ElementInfo e : elements) {
            if (e == null || e.href == null) continue;
            String h = e.href;
            if (h.startsWith("//")) h = "https:" + h;
            if (isDdgHost(h) && h.contains("/l/?uddg=")) {
                return true;
            }
        }
        return false;
    }

    /** v3.28: the match list for the picker's run at a text's
     *  head — the signature names plus the labels the
     *  signature list omits: the variants the picker prints
     *  ("Belgium (fr)", "Belgium (nl)", "Canada (en)",
     *  "Canada (fr)") and the regions the Nov 1 trail's
     *  preview names in the run that REGION_SIGNATURE never
     *  carried ("Catalonia", "Czech Republic", "Estonia",
     *  "Iceland", and DDG's remaining regions past the
     *  preview's 240-char cut — Israel, Latvia, Lithuania,
     *  Slovakia, Slovenia, Turkey). The sequential matcher
     *  needs the whole run spelled out; the detector's
     *  contains-based signature does not, which is why the
     *  omission only surfaces here. Ordered longest first
     *  so a variant wins over its base name at the same
     *  position. */
    private static final String[] REGION_RUN_MATCH =
            buildRegionRunMatch();

    private static String[] buildRegionRunMatch() {
        java.util.List<String> all =
                new java.util.ArrayList<String>();
        for (int i = 0; i < REGION_SIGNATURE.length; i++) {
            all.add(REGION_SIGNATURE[i]);
        }
        all.add("belgium (fr)");
        all.add("belgium (nl)");
        all.add("canada (en)");
        all.add("canada (fr)");
        all.add("india (en)");
        all.add("india (hi)");
        all.add("united states (en)");
        all.add("united states (es)");
        all.add("catalonia");
        all.add("czech republic");
        all.add("estonia");
        all.add("iceland");
        all.add("israel");
        all.add("latvia");
        all.add("lithuania");
        all.add("slovakia");
        all.add("slovenia");
        all.add("turkey");
        // Insertion sort, longest first (the list is ~55
        // entries, built once).
        for (int i = 1; i < all.size(); i++) {
            String v = all.get(i);
            int j = i - 1;
            while (j >= 0 && all.get(j).length() < v.length()) {
                all.set(j + 1, all.get(j));
                j--;
            }
            all.set(j + 1, v);
        }
        return all.toArray(new String[0]);
    }

    /**
     * v3.28: the length of the region-picker run at the HEAD
     * of a page text — the "all regions" header followed by
     * the picker's alphabetical run of region names — or 0
     * when the text does not open with it. The judgement is
     * strict, the same bar as the detector: the header, then
     * at least three region names matched whole (each ending
     * at whitespace or the text's end), longest match first.
     * Anything else at the head — a results page that merely
     * mentions regions, a snippet about Argentina — is not
     * the run, and nothing is stripped from it.
     */
    public static int regionRunLength(String text) {
        if (text == null) return 0;
        String lower = text.toLowerCase(java.util.Locale.US);
        int n = lower.length();
        int i = 0;
        while (i < n
                && Character.isWhitespace(lower.charAt(i))) {
            i++;
        }
        if (!lower.startsWith("all regions", i)) return 0;
        int p = i + "all regions".length();
        if (p < n
                && !Character.isWhitespace(lower.charAt(p))) {
            return 0;
        }
        int matched = 0;
        int lastEnd = -1;
        while (true) {
            int q = p;
            while (q < n
                    && Character.isWhitespace(lower.charAt(q))) {
                q++;
            }
            if (q >= n) break;
            String hit = null;
            for (int k = 0; k < REGION_RUN_MATCH.length; k++) {
                String name = REGION_RUN_MATCH[k];
                if (lower.startsWith(name, q)) {
                    int end = q + name.length();
                    if (end >= n || Character.isWhitespace(
                            lower.charAt(end))) {
                        hit = name;
                        break;
                    }
                }
            }
            if (hit == null) break;
            matched++;
            lastEnd = q + hit.length();
            p = lastEnd;
        }
        if (matched < 3) return 0;
        return lastEnd;
    }

    /**
     * v3.28: a page text with the region-picker furniture
     * removed from its head — the perception hygiene the
     * planner's surfaces apply on DuckDuckGo pages. Host-
     * scoped by construction: a non-DDG URL returns the text
     * untouched, and so does a DDG text that does not open
     * with the run. The remainder keeps its own text; only
     * the separator run the list ended into is dropped. A
     * page that is ALL picker strips to the empty string —
     * the honest preview of a bare picker.
     */
    public static String stripRegionFurniture(String url,
                                              String text) {
        if (text == null) return "";
        if (!isDdgHost(url)) return text;
        int cut = regionRunLength(text);
        if (cut <= 0) return text;
        String rest = text.substring(cut);
        int i = 0;
        while (i < rest.length()
                && Character.isWhitespace(rest.charAt(i))) {
            i++;
        }
        return rest.substring(i);
    }

    /**
     * The search action's fallback report line for a (raw,
     * unencoded) query — for DuckDuckGo this is byte-for-byte
     * the string the action has returned since v1.1.
     */
    public static String searchedMessage(String id, String query) {
        return "ok: no search box on this page; searched "
                + displayName(id) + " for \"" + query + "\"";
    }
}
