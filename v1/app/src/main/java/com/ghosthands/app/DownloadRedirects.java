package com.ghosthands.app;

import java.net.URL;

/**
 * The download fetch's redirect discipline (v3.32).
 * Through v3.31 the http(s) download transport set
 * HttpURLConnection's automatic redirect following and the
 * platform rode the chain wherever it went: the Governor
 * (via Downloads.decide) had judged the OFFERED address,
 * but a permitted host could answer 302 to anywhere —
 * a blocked domain, an http downgrade — and the bytes
 * arrived anyway, unjudged. This class is the pure half of
 * the fix: the fetcher in MainActivity now follows
 * redirects MANUALLY, one hop at a time, and every hop is
 * judged here before it is fetched.
 *
 * The rules:
 *   - Only 301 / 302 / 303 / 307 / 308 are followed, at
 *     most MAX_HOPS times; anything else the server
 *     answers is the fetcher's ordinary success/failure
 *     handling, untouched.
 *   - A hop's destination must be http: or https: (the
 *     scheme read is NavScheme's, evasions folded away).
 *   - An https: page may never redirect the fetch DOWN to
 *     http: — refused outright, whatever the policy says.
 *     (http: up to https: is fine.)
 *   - Every destination is re-evaluated through the SAME
 *     policy evaluation the offered address got —
 *     Downloads.decide under the pack in force — behind
 *     the Policy interface below; a denial aborts the
 *     download with that policy's own reason. A hop that
 *     would merely ASK does not re-ask mid-fetch: the
 *     download itself was already allowed or approved as
 *     a whole, and only a DENY bars a hop. Downloads'
 *     domain rules (blocked domains, the allowlist) are
 *     the part of decide() a redirect could actually
 *     escape, and those deny.
 *   - Cookies are not carried across a host change: the
 *     fetcher attaches the WebView's cookies for an
 *     address only when the previous request in the chain
 *     was to the same host (sendCookies) — the first
 *     request of a chain always may, exactly as the
 *     pre-v3.32 fetch behaved.
 *
 * Pure apart from java.net.URL resolution (the platform's
 * own relative-Location semantics, shared with the host
 * harness): no Android, no I/O, no state. Hosts are read
 * through SiteGrants.hostOf — the same parser the policy
 * itself uses, so the hop judge and the policy can never
 * disagree about which host a URL names.
 */
public final class DownloadRedirects {

    private DownloadRedirects() { }

    /** The most redirects one download fetch will follow. */
    public static final int MAX_HOPS = 5;

    /** The policy a hop is re-judged against: the denial
     *  reason for a destination that may not be fetched,
     *  or null when it may. MainActivity wires this to
     *  Downloads.decide (DENY -> its reason); the host
     *  harness wires fakes. */
    public interface Policy {
        String denialFor(String url);
    }

    /** Is this status one the fetcher follows by hand?
     *  (300/304/305 are not follows: they fall to the
     *  fetcher's ordinary non-2xx failure, as before.) */
    public static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303
                || code == 307 || code == 308;
    }

    /**
     * Judge one hop: fromUrl is the address just answered,
     * toUrl the (already resolved) destination it names,
     * hopsSoFar the redirects this fetch has already
     * followed. Returns null when the hop may proceed, or
     * the honest reason it may not — the fetcher aborts
     * the download with it, verbatim.
     */
    public static String hopDenial(Policy policy, String fromUrl,
                                   String toUrl, int hopsSoFar) {
        if (hopsSoFar >= MAX_HOPS) {
            return "the download redirected more than "
                    + MAX_HOPS + " times";
        }
        String toScheme = NavScheme.schemeOf(toUrl);
        if (!"http".equals(toScheme) && !"https".equals(toScheme)) {
            return "the download redirected to a non-web "
                    + "address ("
                    + (toScheme.length() > 0
                            ? toScheme + ":" : "no scheme")
                    + ")";
        }
        if ("https".equals(NavScheme.schemeOf(fromUrl))
                && "http".equals(toScheme)) {
            return "the download redirected from https down "
                    + "to http, which is refused";
        }
        if (policy != null) {
            String denial = policy.denialFor(toUrl);
            if (denial != null) return denial;
        }
        return null;
    }

    /**
     * Resolve a Location header against the address that
     * answered it (relative Locations are the server's
     * ordinary shape). Returns the absolute destination,
     * or null when the pair cannot be read as a URL.
     */
    public static String resolve(String fromUrl, String location) {
        if (fromUrl == null || location == null) return null;
        try {
            return new URL(new URL(fromUrl), location.trim())
                    .toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * May the request for toUrl carry cookies? Only when
     * the chain's previous request (fromUrl) was to the
     * same host — cookies are never carried across a host
     * change. A chain's first request passes fromUrl null
     * and always may (it names the offered address itself,
     * the pre-v3.32 behaviour).
     */
    public static boolean sendCookies(String fromUrl, String toUrl) {
        if (fromUrl == null) return true;
        String a = SiteGrants.hostOf(fromUrl);
        String b = SiteGrants.hostOf(toUrl);
        return a.length() > 0 && a.equals(b);
    }
}
