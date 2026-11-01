package com.ghosthands.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;

import java.util.HashMap;
import java.util.Map;

/**
 * v3.0 (item 2): the scheduled-check alarm's landing spot. An
 * alarm is only as good as the store it fires for: the receiver
 * re-reads the saved goals, and a goal that was deleted or
 * unscheduled since the alarm was armed fires nothing. A live
 * goal posts its notification and re-arms for the next day, so
 * the daily cadence survives without the app ever opening.
 *
 * This is notify-and-launch, by design: the receiver never
 * browses. The check itself starts when the user taps the
 * notification and presses Start in the app.
 *
 * v3.16 (R8): a page watch's alarm carries its prefix — same
 * receiver, same re-judgement, the page watch's own post and
 * re-arm.
 *
 * v3.18 (R9 — condition-aware checks): between the
 * re-judgement and the post, the condition gate. The gate
 * judges the POST ONLY: a deferred check posts nothing, arms
 * a retry on CheckConditions' cadence, and (on a daily
 * firing) still re-arms tomorrow's alarm — the cadence
 * survives a deferred day exactly as it survives a posted
 * one. A retry firing (EXTRA_RETRY) never re-arms the daily
 * alarm; it judges against the deferral book, posts and
 * clears the deferral when conditions hold (or when the
 * give-up window has run out — late beats lost), and re-arms
 * the retry while they do not. The whole decision is
 * CheckConditions.judgeFiring, pure and harness-pinned; this
 * class only reads the phone's state (charging from the
 * sticky battery intent, Wi-Fi from the active network's
 * capabilities — ACCESS_NETWORK_STATE covers it, no new
 * permission) and applies the outcome. Unproven reads as
 * unmet: a state the phone did not report is never invented.
 *
 * v3.27 (R13 — unattended mode): a goal opted into
 * unattended mode leaves this post path entirely. Its
 * firing is judged by Unattended.judgeFiring (pure): a
 * missing grant or an exhausted give-up posts the reminder
 * exactly as above (notify-and-launch is the degradation,
 * never a silent skip); unmet conditions defer the RUN on
 * this same retry cadence and deferral book; conditions
 * met starts UnattendedService, which runs the check
 * readonly under a foreground service. Every outcome is
 * written to the firing book (UnattendedLog). Page watches
 * are unchanged: they have no unattended opt-in in v3.27
 * and stay notify-and-launch.
 */
public class CheckReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String name = intent.getStringExtra(Alerts.EXTRA_GOAL_NAME);
        if (name == null) return;
        boolean retry = intent.getBooleanExtra(
                Alerts.EXTRA_RETRY, false);
        SharedPreferences prefs = context.getSharedPreferences(
                "ghosthands", Context.MODE_PRIVATE);
        Settings settings = loadSettings(prefs);
        CheckDeferrals deferrals = CheckDeferrals.parse(prefs
                .getString(CheckDeferrals.PREF_CHECK_DEFERRALS, ""));
        long now = System.currentTimeMillis();
        CheckDeferrals.Deferral existing = deferrals.get(name);
        long firstDeferred = (existing == null)
                ? 0 : existing.firstDeferredMs;

        // v3.16 (R8): a page watch's alarm carries its prefix —
        // same receiver, same re-judgement.
        if (PageWatches.isPageAlarm(name)) {
            PageWatches watches = PageWatches.parse(
                    prefs.getString(
                            PageWatches.PREF_PAGE_WATCHES, null));
            PageWatches.PageWatch w = watches.find(
                    PageWatches.urlFromAlarm(name));
            int decision = CheckConditions.judgeFiring(
                    w != null, w != null && w.hasSchedule(),
                    settings.checkConditionsOn,
                    w != null && w.condCharging,
                    w != null && w.condWifi,
                    chargingNow(context), onWifiNow(context),
                    firstDeferred, now);
            if (decision == CheckConditions.NOTHING) {
                clearDeferral(context, prefs, deferrals, name);
                return;
            }
            if (decision == CheckConditions.DEFER) {
                deferrals.record(name, now);
                persistDeferrals(prefs, deferrals);
                Alerts.armPageWatchRetry(context, w,
                        CheckConditions.RETRY_MS);
                if (!retry) Alerts.armPageWatchCheck(context, w);
                return;
            }
            // POST or POST_ANYWAY.
            Alerts.postPageWatchCheck(context, w);
            if (!retry) Alerts.armPageWatchCheck(context, w);
            clearDeferral(context, prefs, deferrals, name);
            return;
        }

        SavedGoals store = SavedGoals.parse(
                prefs.getString(Alerts.PREF_SAVED_GOALS, null));
        SavedGoals.SavedGoal g = store.find(name);
        // v3.27 (R13): an opted-in goal's firing is judged by
        // the unattended rule, not the post gate.
        if (g != null && g.unattended) {
            onUnattendedFiring(context, prefs, deferrals, g,
                    retry, now, firstDeferred);
            return;
        }
        int decision = CheckConditions.judgeFiring(
                g != null, g != null && g.hasSchedule(),
                settings.checkConditionsOn,
                g != null && g.condCharging,
                g != null && g.condWifi,
                chargingNow(context), onWifiNow(context),
                firstDeferred, now);
        if (decision == CheckConditions.NOTHING) {
            clearDeferral(context, prefs, deferrals, name);
            return;
        }
        if (decision == CheckConditions.DEFER) {
            deferrals.record(name, now);
            persistDeferrals(prefs, deferrals);
            Alerts.armCheckRetry(context, name,
                    CheckConditions.RETRY_MS);
            if (!retry) Alerts.armCheck(context, g);
            return;
        }
        // POST or POST_ANYWAY.
        Alerts.postCheck(context, g);
        if (!retry) Alerts.armCheck(context, g);
        clearDeferral(context, prefs, deferrals, name);
    }

    /** v3.27 (R13): one unattended goal's firing, applied.
     *  The judgement is Unattended.judgeFiring's; this method
     *  reads the phone (grants, charging, Wi-Fi — the same
     *  readers the post gate uses) and carries the outcome
     *  out: re-arm the daily cadence on a daily firing,
     *  defer on the R9 cadence, post on degradation, start
     *  the service on RUN — and write the firing book for
     *  every outcome, so a firing is never silent. */
    private void onUnattendedFiring(Context context,
                                    SharedPreferences prefs,
                                    CheckDeferrals deferrals,
                                    SavedGoals.SavedGoal g,
                                    boolean retry, long now,
                                    long firstDeferred) {
        boolean exact = Alerts.canScheduleExact(context);
        boolean exempt = Alerts.batteryExempt(context);
        int decision = Unattended.judgeFiring(
                true, g.hasSchedule(), true, exact, exempt,
                g.condWifi, chargingNow(context),
                onWifiNow(context), firstDeferred, now);
        UnattendedLog log = UnattendedLog.parse(prefs.getString(
                UnattendedLog.PREF_UNATTENDED_LOG, ""));
        if (decision == Unattended.NOTHING) {
            clearDeferral(context, prefs, deferrals, g.name);
            return;
        }
        if (decision == Unattended.DEFER) {
            deferrals.record(g.name, now);
            persistDeferrals(prefs, deferrals);
            Alerts.armCheckRetry(context, g.name,
                    CheckConditions.RETRY_MS);
            if (!retry) Alerts.armCheck(context, g);
            log.record(g.name, UnattendedLog.OUT_DEFERRED, now,
                    "");
            persistUnattendedLog(prefs, log);
            return;
        }
        if (decision == Unattended.RUN) {
            clearDeferral(context, prefs, deferrals, g.name);
            if (!retry) Alerts.armCheck(context, g);
            boolean started = UnattendedService.start(
                    context, g.name);
            // The service overwrites "started" with the run's
            // own ending; a start that was refused lands here
            // as a failure AND falls back to the reminder, so
            // the check is heard from either way.
            log.record(g.name, started
                    ? UnattendedLog.OUT_STARTED
                    : UnattendedLog.OUT_FAILED, now, "");
            persistUnattendedLog(prefs, log);
            if (!started) Alerts.postCheck(context, g);
            return;
        }
        // POST: a grant is missing, or a deferred run's
        // give-up window ran out — notify-and-launch, exactly
        // the attended behaviour, with the reason in the book.
        Alerts.postCheck(context, g);
        if (!retry) Alerts.armCheck(context, g);
        clearDeferral(context, prefs, deferrals, g.name);
        log.record(g.name, Unattended.grantsOk(exact, exempt)
                ? UnattendedLog.OUT_POSTED_GIVEUP
                : UnattendedLog.OUT_POSTED_GRANT, now, "");
        persistUnattendedLog(prefs, log);
    }

    private static void persistUnattendedLog(
            SharedPreferences prefs, UnattendedLog log) {
        prefs.edit().putString(
                UnattendedLog.PREF_UNATTENDED_LOG,
                log.serialize()).apply();
    }

    /** The receiver's settings read: the same ALL_KEYS bridge
     *  the Activity uses, over the same preferences file. */
    private static Settings loadSettings(SharedPreferences prefs) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < Settings.ALL_KEYS.length; i++) {
            String v = prefs.getString(Settings.ALL_KEYS[i], null);
            if (v != null) m.put(Settings.ALL_KEYS[i], v);
        }
        return Settings.fromMap(m);
    }

    private static void persistDeferrals(SharedPreferences prefs,
                                         CheckDeferrals deferrals) {
        prefs.edit().putString(CheckDeferrals.PREF_CHECK_DEFERRALS,
                deferrals.serialize()).apply();
    }

    /** A posted (or dead) check's deferral is over: forget it
     *  and disarm any retry still outstanding. */
    private static void clearDeferral(Context ctx,
                                      SharedPreferences prefs,
                                      CheckDeferrals deferrals,
                                      String name) {
        if (deferrals.get(name) != null) {
            deferrals.clear(name);
            persistDeferrals(prefs, deferrals);
        }
        Alerts.cancelCheckRetry(ctx, name);
    }

    /** Charging, from the sticky battery intent: any plug
     *  (AC, USB, wireless) counts. Unreadable is unproven,
     *  and unproven is unmet. */
    private static boolean chargingNow(Context ctx) {
        Intent batt = ctx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batt == null) return false;
        return batt.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** On Wi-Fi: the active network carries the Wi-Fi
     *  transport. Anything unreadable is unproven, and
     *  unproven is unmet. */
    private static boolean onWifiNow(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI);
    }
}
