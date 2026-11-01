package com.ghosthands.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;
import java.util.TimeZone;

/**
 * v3.0 (items 2 + 5): the app's two notification jobs and the
 * alarm wiring behind them, in one Android-side helper used by
 * both MainActivity and CheckReceiver.
 *
 * - Scheduled checks (item 2): an AlarmManager alarm per saved
 *   goal with a daily time. The alarm wakes CheckReceiver, which
 *   posts the "time for your check" notification and re-arms for
 *   the next day. setAndAllowWhileIdle is used deliberately: it
 *   needs no exact-alarm permission (v3.0 adds exactly one new
 *   permission, RECORD_AUDIO), at the price of doze-era drift —
 *   a check may land minutes late on a sleeping phone, which a
 *   daily reminder tolerates. Alarms do not survive a reboot
 *   (no boot receiver in v3.0); the Activity re-arms every
 *   scheduled goal each time it opens.
 * - Finish notifications (item 5): posted when a run ends with
 *   the app in the background; the tap reopens the app on that
 *   run's answer card (the trail path rides along as an extra).
 * - Watch verdicts (v3.2): the Activity applies the watch
 *   matrix (see BUILD_NOTES) — a watched run that changed is
 *   the one run that notifies even in the foreground, via
 *   postWatchChanged below, carrying its first diff line; a
 *   watched run that did not change stays silent entirely.
 * - Needs-me (v3.6): when the setting is on, a run that starts
 *   waiting on the user (a governor approval, a co-pilot rescue
 *   offer) while the app is in the background posts
 *   postNeedsYou; the NeedsMe decision gates every post, and a
 *   foreground wait never notifies.
 * - Unattended arming (v3.27, R13): a goal opted into
 *   unattended mode arms EXACT (setExactAndAllowWhileIdle)
 *   while its exact-alarm grant is in hand — the exact alarm
 *   is the platform's key for starting the run's foreground
 *   service from the background. Grants are re-read at every
 *   arm (canScheduleExact / batteryExempt below), and every
 *   other goal arms inexact exactly as it always has.
 *
 * Android 13+ note (disclosed in the release notes): this app
 * targets API 34 but deliberately does NOT declare
 * POST_NOTIFICATIONS, so on Android 13+ these notifications
 * appear only when the user has allowed notifications for the
 * app in system settings; the machinery posts regardless and
 * the OS decides.
 */
public final class Alerts {

    private Alerts() { }

    public static final String CHANNEL_CHECKS = "ghost_hands_checks";
    public static final String CHANNEL_RUNS = "ghost_hands_runs";

    public static final String EXTRA_GOAL_NAME = "saved_goal_name";
    public static final String EXTRA_FROM_CHECK = "from_check";
    public static final String EXTRA_CARD_TRAIL = "card_trail_path";
    /** v3.18 (R9): marks a condition RETRY firing — the same
     *  check alarm, carrying this extra, so the receiver can
     *  tell a retry (judge against the deferral book) from the
     *  check's daily firing (re-arm the daily cadence, then
     *  judge). The retry's PendingIntent uses a distinct
     *  request code (see retryIntent): a check has at most one
     *  daily alarm and one retry alarm, and they never alias. */
    public static final String EXTRA_RETRY = "check_retry";

    /** v3.6 (needs-me): the waits that can ask for the user —
     *  the kinds postNeedsYou words its text for. v3.12 (R4)
     *  adds the third: a run asking the user a clarifying
     *  question is a needs-me wait of exactly this family. */
    public static final String NEEDS_APPROVAL = "approval";
    public static final String NEEDS_COPILOT = "copilot";
    public static final String NEEDS_ASK = "ask";

    /** The preferences key the saved-goals JSON lives under (the
     *  preferences file is MainActivity's "ghosthands"). */
    public static final String PREF_SAVED_GOALS = "saved_goals";

    private static final String ACTION_CHECK =
            "com.ghosthands.app.action.CHECK";
    private static final int RUN_NOTIFICATION_REQUEST = 4242;

    // ---------------- channels ----------------

    /** Create both notification channels (idempotent; a no-op
     *  before API 26, where channels do not exist). */
    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_CHECKS) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_CHECKS, "Daily checks",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        if (nm.getNotificationChannel(CHANNEL_RUNS) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_RUNS, "Run finished",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    // ---------------- notifications ----------------

    /** Item 2: "Time for your check" — the tap opens the app
     *  with that goal loaded, ready to start under readonly. */
    public static void postCheck(Context ctx, SavedGoals.SavedGoal g) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.putExtra(EXTRA_GOAL_NAME, g.name);
        open.putExtra(EXTRA_FROM_CHECK, true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx,
                ("check:" + g.name).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification n = build(ctx, CHANNEL_CHECKS,
                "Ghost Hands — time for your check",
                "Time for your check: " + g.name + " — tap to run",
                pi);
        notify(ctx, ("check:" + g.name).hashCode(), n);
    }

    /** v3.2: a watched check found a change — the tap opens
     *  the app on that run's answer card (banner and all). The
     *  text is the verdict's first diff line, the one number
     *  the user most likely wants at a glance. Posted per-goal
     *  (its id derives from the goal name) so two watched goals
     *  changing on the same morning do not clobber each other. */
    public static void postWatchChanged(Context ctx, String goalName,
                                        String firstDiffLine,
                                        String trailPath) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        if (trailPath != null) {
            open.putExtra(EXTRA_CARD_TRAIL, trailPath);
        }
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int id = ("watch:" + goalName).hashCode();
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        String text = (firstDiffLine == null
                || firstDiffLine.length() == 0)
                ? "the answer changed" : firstDiffLine;
        if (text.length() > 120) {
            text = text.substring(0, 119) + "…";
        }
        Notification n = build(ctx, CHANNEL_RUNS,
                "Ghost Hands — watch: " + goalName, text, pi);
        notify(ctx, id, n);
    }

    /** v3.6 (needs-me): a run is WAITING on the user and the app
     *  is in the background (the decision itself is NeedsMe's,
     *  made by the caller at the wait's entry point). The tap
     *  brings the app forward with no extras: the waiting panel
     *  already switched the app to the Agent screen when it was
     *  shown (the v3.5 invariant), so the user lands on the very
     *  decision that is waiting. Posted per-kind, so an approval
     *  and a rescue offer never clobber each other's text. Like
     *  every notification here, on Android 13+ it appears only
     *  when the user has allowed notifications for the app in
     *  system settings — POST_NOTIFICATIONS is still not
     *  declared. */
    public static void postNeedsYou(Context ctx, String kind) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int id = ("needs:" + kind).hashCode();
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        String text = NEEDS_COPILOT.equals(kind)
                ? "A run is stuck and offering a co-pilot rescue"
                        + " — tap to step in"
                : (NEEDS_ASK.equals(kind)
                        ? "A run is asking you a question"
                                + " — tap to answer"
                        : "A run is waiting for your approval"
                                + " — tap to decide");
        Notification n = build(ctx, CHANNEL_RUNS,
                "Ghost Hands — needs you", text, pi);
        notify(ctx, id, n);
    }

    /** Item 5: a run ended off-screen — the tap opens the app
     *  on that run's answer card. */
    public static void postRunFinished(Context ctx, AnswerCard card,
                                       String trailPath) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        if (trailPath != null) {
            open.putExtra(EXTRA_CARD_TRAIL, trailPath);
        }
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx,
                RUN_NOTIFICATION_REQUEST, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification n = build(ctx, CHANNEL_RUNS,
                "Ghost Hands — run finished",
                card.notificationLine(), pi);
        notify(ctx, RUN_NOTIFICATION_REQUEST, n);
    }

    private static Notification build(Context ctx, String channel,
                                      String title, String text,
                                      PendingIntent pi) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(ctx, channel)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_dialog_info);
        b.setContentTitle(title);
        b.setContentText(text);
        b.setAutoCancel(true);
        b.setContentIntent(pi);
        return b.build();
    }

    private static void notify(Context ctx, int id, Notification n) {
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, n);
    }

    // ---------------- alarms ----------------

    private static PendingIntent alarmIntent(Context ctx, String name) {
        Intent i = new Intent(ctx, CheckReceiver.class);
        i.setAction(ACTION_CHECK);
        i.putExtra(EXTRA_GOAL_NAME, name);
        return PendingIntent.getBroadcast(ctx, name.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Arm (or re-arm) one goal's daily check at its next
     *  occurrence. A goal without a schedule disarms instead.
     *  v3.27 (R13): an unattended goal whose exact-alarm
     *  grant is in hand AT ARM TIME arms exact (the exact
     *  alarm is the documented key that lets the receiver
     *  start the foreground service — R13 spike §2); every
     *  other goal arms exactly as it always has. The grant
     *  is re-read here, never assumed (it is revocable),
     *  and a SecurityException from the exact call degrades
     *  to the inexact arming rather than crashing the
     *  caller — degrade, not crash (spike §8.1). */
    public static void armCheck(Context ctx, SavedGoals.SavedGoal g) {
        if (g == null) return;
        if (!g.hasSchedule()) {
            cancelCheck(ctx, g.name);
            return;
        }
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long when = SavedGoals.nextFireMillis(
                System.currentTimeMillis(), g.schedMinutes,
                TimeZone.getDefault());
        if (Unattended.armMode(g.unattended,
                canScheduleExact(ctx))
                == Unattended.ARM_EXACT) {
            try {
                am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, when,
                        alarmIntent(ctx, g.name));
                return;
            } catch (SecurityException denied) {
                // The grant left between the check and the
                // call: fall through to the inexact arming.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when,
                alarmIntent(ctx, g.name));
    }

    /** v3.27 (R13): can this app schedule exact alarms right
     *  now? Before Android 12 (API 31) exact alarms need no
     *  grant at all; from 31 the user-granted
     *  SCHEDULE_EXACT_ALARM state is the answer. Shared by
     *  the arming above, the receiver's fire-time judgement
     *  and the Settings status line — one reader, so the
     *  three can never disagree. */
    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** v3.27 (R13): is the app exempt from battery
     *  optimization ("Unrestricted") right now? The grant
     *  the unattended design stands on (spike §5: network
     *  and wake locks inside Doze, a standing FGS-start
     *  exemption, exact-alarm eligibility). Shared by the
     *  receiver and the Settings status line. */
    public static boolean batteryExempt(Context ctx) {
        android.os.PowerManager pm = (android.os.PowerManager)
                ctx.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(
                ctx.getPackageName());
    }

    /** Disarm one goal's daily check. */
    public static void cancelCheck(Context ctx, String name) {
        if (name == null) return;
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmIntent(ctx, name));
    }

    /** Arm every scheduled goal in the store (the Activity calls
     *  this at launch: alarms are the app's memory of its checks,
     *  and a reboot forgets them). */
    public static void armAllChecks(Context ctx, SavedGoals store) {
        if (store == null) return;
        List<SavedGoals.SavedGoal> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).hasSchedule()) armCheck(ctx, all.get(i));
        }
    }

    // ---------------- v3.16: page-watch checks (R8) ----------------
    // The goal checks' machinery, verbatim in shape: a page
    // watch's alarm carries its alarm name ("page:" + url) in
    // the same EXTRA_GOAL_NAME, its notification offers the
    // same tap-to-run, and its cadence is the same next-fire
    // computation. Notify-and-launch, exactly: nothing browses
    // unattended, and the check itself runs readonly.

    /** "Time for your check" for a page watch — the tap opens
     *  the app with the watch's check loaded, ready to start
     *  under readonly. */
    public static void postPageWatchCheck(Context ctx,
                                          PageWatches.PageWatch w) {
        ensureChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.putExtra(EXTRA_GOAL_NAME, PageWatches.alarmName(w));
        open.putExtra(EXTRA_FROM_CHECK, true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx,
                ("check:" + PageWatches.alarmName(w)).hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification n = build(ctx, CHANNEL_CHECKS,
                "Ghost Hands — time for your check",
                "Time to check: " + PageWatches.label(w.url)
                        + " — tap to run",
                pi);
        notify(ctx, ("check:" + PageWatches.alarmName(w)).hashCode(),
                n);
    }

    /** Arm (or re-arm) one page watch's daily check at its
     *  next occurrence. A watch without a schedule disarms. */
    public static void armPageWatchCheck(Context ctx,
                                         PageWatches.PageWatch w) {
        if (w == null) return;
        if (!w.hasSchedule()) {
            cancelPageWatchCheck(ctx, w.url);
            return;
        }
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long when = SavedGoals.nextFireMillis(
                System.currentTimeMillis(), w.schedMinutes,
                TimeZone.getDefault());
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when,
                alarmIntent(ctx, PageWatches.alarmName(w)));
    }

    /** Disarm one page watch's daily check. */
    public static void cancelPageWatchCheck(Context ctx,
                                            String url) {
        if (url == null) return;
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmIntent(ctx, PageWatches.ALARM_PREFIX + url));
    }

    /** Arm every scheduled page watch (the Activity calls
     *  this at launch, beside armAllChecks). */
    public static void armAllPageWatches(Context ctx,
                                         PageWatches store) {
        if (store == null) return;
        List<PageWatches.PageWatch> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).hasSchedule()) {
                armPageWatchCheck(ctx, all.get(i));
            }
        }
    }

    // ---------------- v3.18: condition retries (R9) ----------------
    // A deferred check's retry is a SECOND alarm for the same
    // check name, on the same intent shape plus EXTRA_RETRY,
    // under a request code the daily alarm never uses — so
    // arming, firing, or cancelling one never touches the
    // other. The cadence is CheckConditions.RETRY_MS; the
    // receiver owns the judgement, this class only the alarm.

    private static PendingIntent retryIntent(Context ctx,
                                             String name) {
        Intent i = new Intent(ctx, CheckReceiver.class);
        i.setAction(ACTION_CHECK);
        i.putExtra(EXTRA_GOAL_NAME, name);
        i.putExtra(EXTRA_RETRY, true);
        return PendingIntent.getBroadcast(ctx,
                ("retry:" + name).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Arm a goal check's condition retry RETRY_MS out. */
    public static void armCheckRetry(Context ctx, String name,
                                     long delayMs) {
        if (name == null) return;
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                retryIntent(ctx, name));
    }

    /** Arm a page-watch check's condition retry RETRY_MS out. */
    public static void armPageWatchRetry(Context ctx,
                                         PageWatches.PageWatch w,
                                         long delayMs) {
        if (w == null) return;
        armCheckRetry(ctx, PageWatches.alarmName(w), delayMs);
    }

    /** Disarm a check's condition retry (it posted, or the
     *  check is gone). Name-based, for goal checks and
     *  page-watch checks alike (the alarm name is the key). */
    public static void cancelCheckRetry(Context ctx, String name) {
        if (name == null) return;
        AlarmManager am = (AlarmManager)
                ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(retryIntent(ctx, name));
    }
}
