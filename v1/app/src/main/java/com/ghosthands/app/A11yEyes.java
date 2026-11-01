package com.ghosthands.app;

/**
 * v3.25 (accessibility eyes): the Settings status line for
 * the eyes — pure, so the harness pins both states.
 *
 * The eyes have exactly one switch: the user's own grant of
 * the Ghost Hands accessibility service in Android Settings
 * (Accessibility > Installed services). There is no in-app
 * toggle for them — the DIAGNOSTICS spike toggle arms the
 * R6 spike's probes, a different feature — so this line
 * names the grant's state and what it means, in the place
 * the user went looking for it: shown where the state is
 * asked about, and nowhere else (the app does not nag).
 *
 * The grant is also re-judged at every consultation: the
 * loop's eyes path asks the service for its connected
 * instance at the moment a step needs it, so revoking the
 * service in Android Settings stops the eyes at the next
 * step, not the next launch.
 */
public final class A11yEyes {

    private A11yEyes() { }

    /** The status row's text for the two grant states. */
    public static String statusLine(boolean serviceConnected) {
        if (serviceConnected) {
            return "Eyes ON: the accessibility service is "
                    + "enabled. When a page's browser map "
                    + "comes back empty, the agent may read "
                    + "the page through the accessibility "
                    + "node tree instead. The eyes are "
                    + "read-only — the agent still acts only "
                    + "through the browser.";
        }
        return "Eyes OFF: the accessibility service is not "
                + "enabled. Enable Ghost Hands under "
                + "Android Settings > Accessibility > "
                + "Installed services to let the agent read "
                + "pages whose browser map comes back empty "
                + "(read-only). Until then, runs behave "
                + "exactly as before.";
    }
}
