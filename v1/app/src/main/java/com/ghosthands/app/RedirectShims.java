package com.ghosthands.app;

/**
 * v3.28 (redirect-shim stranding): the pure policy for a
 * page that is not a page at all — a redirect shim, a stub
 * whose only content is the instruction to go somewhere
 * else.
 *
 * The field failure (the Nov 1, 2026 v3.26 device trail
 * trail-20261101-005359): the planner tapped the
 * AccuWeather result on the DuckDuckGo results page; the
 * tap landed on DDG's redirect shim
 * (duckduckgo.com/l/?uddg=<the AccuWeather URL>, a ~200-byte
 * stub) and the app perceived the stub as a settled empty
 * page — through the whole thin-map ritual (three retries,
 * ~45 s of step time) and a planner read that errored "no
 * readable text found" — and the run died at the step cap
 * one redirect away from the forecast. The hands' settle
 * wait could not see it: the stub page itself finishes
 * loading (the load callbacks fire for the stub), so by the
 * time the step perceives, nothing is "in flight" — the
 * forward the stub exists to make simply had not happened.
 *
 * The loop's answer, driven by this policy: a step whose
 * perception URL is a known shim is never judged as a page.
 * The loop waits briefly for the forward (re-perceiving),
 * and a stub that still has not resolved is bypassed — the
 * loop navigates to the shim's decoded destination itself,
 * the very address the tapped link pointed at. This class
 * is the recognition and the destination decoding, plus
 * the wait's shape; the waiting and navigating live in the
 * agent loop, which owns the hands. Nothing here sleeps,
 * navigates, or records.
 *
 * The known shape, deliberately narrow: a DuckDuckGo host,
 * the /l/ path, and a uddg query parameter carrying the
 * destination. Other engines' shims join by the same
 * pattern if a field trail ever names one; nothing is
 * guessed at here.
 */
public final class RedirectShims {

    private RedirectShims() { }

    /** Re-perceptions the loop spends waiting for a shim's
     *  forward before it navigates to the destination
     *  itself. The stub's forward, when it fires at all,
     *  fires in well under a second; three one-second polls
     *  are patience, not hope. */
    public static final int WAIT_ATTEMPTS = 3;

    /** The poll interval between shim re-perceptions. */
    public static final int WAIT_MS = 1000;

    /** Test seam (the ThinMap.BACKOFF_DIVISOR precedent):
     *  the host harness divides the SLEPT wait by this so
     *  its scripted shim runs do not pay real seconds. The
     *  attempt count the trail records is never scaled, and
     *  the app never touches the divisor — it stays 1 on
     *  device. */
    public static volatile long WAIT_DIVISOR = 1L;

    /** The wait actually slept before shim re-perception
     *  `attempt`: the standing interval through the seam. */
    public static int waitSleepMs() {
        long d = WAIT_DIVISOR;
        if (d < 1L) d = 1L;
        long ms = WAIT_MS / d;
        return ms > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : (int) ms;
    }

    /** True when this URL is a known redirect shim: a
     *  DuckDuckGo host, the /l/ path, and a uddg parameter
     *  with a value. A DDG results URL, a DDG home page, or
     *  another host's /l/ path is not a shim. */
    public static boolean isShim(String url) {
        return shimTarget(url) != null;
    }

    /** The destination a shim forwards to — the decoded
     *  uddg value — or null when the URL is not a shim or
     *  the value does not decode to an http(s) address (a
     *  malformed shim is still a shim for isShim's caller
     *  when the raw parameter is present; it simply has
     *  nowhere honest to send the loop). */
    public static String destination(String url) {
        String raw = shimTarget(url);
        if (raw == null) return null;
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (Exception e) {
            return null;
        }
        if (decoded.startsWith("http://")
                || decoded.startsWith("https://")) {
            return decoded;
        }
        return null;
    }

    /** The raw (still encoded) uddg value when the URL has
     *  the shim's shape, else null. */
    private static String shimTarget(String url) {
        if (url == null) return null;
        if (!SearchEngines.isDdgHost(url)) return null;
        java.net.URL u;
        try {
            u = new java.net.URL(url);
        } catch (Exception e) {
            return null;
        }
        String path = u.getPath();
        if (!"/l/".equals(path) && !"/l".equals(path)) {
            return null;
        }
        String query = u.getQuery();
        if (query == null) return null;
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if ("uddg".equals(pair.substring(0, eq))) {
                String value = pair.substring(eq + 1);
                return value.length() > 0 ? value : null;
            }
        }
        return null;
    }
}
