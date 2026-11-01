package com.ghosthands.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * v3.27 (Everything Train R13 — unattended mode): the
 * reboot gap, closed (spike §7).
 *
 * Alarms do not survive a reboot, and through v3.26 the
 * app's only repair was re-arming when the user next
 * opened it — an unattended feature that silently stops
 * existing after every reboot is not unattended. This
 * receiver re-arms every scheduled check (goals and page
 * watches, through the same Alerts entry points the
 * launch pass uses, so grant-aware exact arming applies
 * identically) — and nothing more. It starts no service
 * and runs no check: starting a foreground service from a
 * boot receiver is both unnecessary here and, for several
 * service types on Android 15, prohibited (spike §3).
 *
 * Force-stop is the harder wall this cannot close: a
 * force-stopped app's alarms are cancelled and nothing —
 * not even this receiver — fires until the user relaunches
 * the app. What the design owes for that case is a report,
 * not silence: the launch pass judges missed firings
 * against the firing book (Unattended.missedFiring) and
 * puts them in the morning briefing.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(
                intent.getAction())) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(
                "ghosthands", Context.MODE_PRIVATE);
        SavedGoals goals = SavedGoals.parse(
                prefs.getString(Alerts.PREF_SAVED_GOALS, null));
        Alerts.armAllChecks(context, goals);
        PageWatches watches = PageWatches.parse(
                prefs.getString(
                        PageWatches.PREF_PAGE_WATCHES, null));
        Alerts.armAllPageWatches(context, watches);
    }
}
