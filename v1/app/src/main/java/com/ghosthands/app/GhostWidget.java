package com.ghosthands.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.io.File;

/**
 * v3.23 (Everything Train R12 — the home-screen widget): the
 * AppWidgetProvider. NEW EXPORTED SURFACE, named plainly (the
 * R6/R8/R11 discipline): exported="true" is platform-mandated
 * for widget providers — the launcher and the system
 * AppWidget service must be able to reach it — and it is the
 * app's third exported component (MainActivity and R11's
 * share-intake activity are the others; R11's QS tile service
 * is exported behind its system permission). It exposes
 * nothing to other apps: it renders the app's own stores into
 * the app's own widget, and it acts on exactly one
 * explicit-component action (its own Stop) that only this
 * app can mint a PendingIntent for — and it re-checks the
 * action string before acting.
 *
 * The widget is a READER, never a runner. This class starts
 * no run, polls nothing (updatePeriodMillis is 0), and holds
 * no state of its own: every render loads WidgetState +
 * BriefingLog from the shared prefs, applies WidgetState's
 * dead-process judgement against the run marker's presence,
 * and composes through WidgetText (pure). MainActivity
 * pushes refresh() at each run transition and at each
 * verdict; onUpdate covers the widget being added and the
 * launcher restarting.
 *
 * Widget Stop: the button's PendingIntent is an EXPLICIT
 * broadcast to this receiver. onReceive intercepts it before
 * super: RunControl.requestStop() (the process-wide latch
 * the Ui ORs into stopRequested() and the sliced waits poll),
 * a WidgetState write of phase "stopping" with goal/step
 * preserved, and a refresh. If no run is live (judged), the
 * same tap just refreshes the corrected board — never an
 * error surface. If the app process is dead there is no run
 * to stop: the receiver starts a process, finds the marker
 * gone, judges the board interrupted, and the latch it sets
 * dies with that process, harming nothing.
 */
public class GhostWidget extends AppWidgetProvider {

    /** The one action this receiver acts on besides the
     *  system's widget updates. Explicit-component only. */
    public static final String ACTION_WIDGET_STOP =
            "com.ghosthands.app.WIDGET_STOP";

    private static final int REQ_OPEN = 7230;
    private static final int REQ_STOP = 7231;

    /** The run marker's file name — the same literal
     *  MainActivity's runMarkerFile() writes (the marker is
     *  the liveness witness the judgement reads). */
    static final String MARKER_NAME = "run-in-progress.json";

    private static final int COL_TEXT = 0xFFE8F1FA;
    private static final int COL_MUTED = 0xFF5A6B85;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null
                && ACTION_WIDGET_STOP.equals(intent.getAction())) {
            try {
                WidgetState s = loadState(context);
                if (WidgetState.isLivePhase(
                        WidgetState.judge(s, markerPresent(context)))) {
                    RunControl.requestStop();
                    WidgetState stopping = s.withPhase(
                            WidgetState.PHASE_STOPPING,
                            System.currentTimeMillis());
                    persist(context, stopping);
                }
            } catch (Throwable ignored) {
                // A widget tap must never surface an error.
            }
            refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager,
                         int[] appWidgetIds) {
        for (int i = 0; i < appWidgetIds.length; i++) {
            try {
                manager.updateAppWidget(appWidgetIds[i],
                        buildViews(context));
            } catch (Throwable ignored) {
                // One bad widget must not fail the others.
            }
        }
    }

    /** Push a fresh render to every placed widget. Called by
     *  MainActivity at each run transition and verdict, and
     *  once per launch. Swallow-guarded end to end: a widget
     *  failure must never break a finish path (the
     *  beepFinish/vibrateFinish guard discipline). */
    public static void refresh(Context context) {
        try {
            AppWidgetManager manager =
                    AppWidgetManager.getInstance(context);
            if (manager == null) return;
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(context, GhostWidget.class));
            if (ids == null || ids.length == 0) return;
            RemoteViews views = buildViews(context);
            for (int i = 0; i < ids.length; i++) {
                manager.updateAppWidget(ids[i], views);
            }
        } catch (Throwable ignored) { }
    }

    /** Resource ids resolve by NAME (getIdentifier), never
     *  the generated R class — the v3.17 precedent: the app
     *  has never referenced R, and the host harness compiles
     *  against android.jar alone, where no R exists. The ids
     *  are the layout's own, pinned by the harness's layout
     *  grep. */
    private static int resId(Context context, String type,
                             String name) {
        return context.getResources().getIdentifier(name, type,
                context.getPackageName());
    }

    /** Compose the board: stores + judgement + WidgetText,
     *  then the two intents (the body's open-app intent in
     *  all states; the Stop button's intent by WidgetText's
     *  pure decision). */
    static RemoteViews buildViews(Context context) {
        WidgetState state = loadState(context);
        BriefingLog log = loadLog(context);
        String judged = WidgetState.judge(state,
                markerPresent(context));
        long now = System.currentTimeMillis();
        int bodyId = resId(context, "id", "widget_body");
        int runId = resId(context, "id", "widget_run_line");
        int verdictsId = resId(context, "id", "widget_verdicts");
        int stopId = resId(context, "id", "widget_stop");
        RemoteViews views = new RemoteViews(context.getPackageName(),
                resId(context, "layout", "ghost_widget"));
        views.setTextViewText(runId,
                WidgetText.runLine(judged, state, now));
        views.setTextViewText(verdictsId,
                WidgetText.verdictBlock(log, now));
        PendingIntent open = openIntent(context);
        views.setOnClickPendingIntent(bodyId, open);
        views.setTextViewText(stopId, "■ Stop");
        if (WidgetText.stopArmed(judged)) {
            views.setInt(stopId, "setTextColor", COL_TEXT);
            views.setOnClickPendingIntent(stopId,
                    stopIntent(context));
        } else {
            views.setInt(stopId, "setTextColor", COL_MUTED);
            views.setOnClickPendingIntent(stopId, open);
        }
        return views;
    }

    private static PendingIntent openIntent(Context context) {
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, REQ_OPEN, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent stopIntent(Context context) {
        Intent stop = new Intent(context, GhostWidget.class);
        stop.setAction(ACTION_WIDGET_STOP);
        return PendingIntent.getBroadcast(context, REQ_STOP, stop,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean markerPresent(Context context) {
        try {
            return new File(context.getFilesDir(), MARKER_NAME)
                    .exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static WidgetState loadState(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    "ghosthands", Context.MODE_PRIVATE);
            return WidgetState.parse(
                    prefs.getString(WidgetState.PREF_WIDGET_STATE,
                            null));
        } catch (Throwable ignored) {
            return WidgetState.idle();
        }
    }

    static BriefingLog loadLog(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    "ghosthands", Context.MODE_PRIVATE);
            return BriefingLog.parse(
                    prefs.getString(BriefingLog.PREF_BRIEFING_LOG,
                            null));
        } catch (Throwable ignored) {
            return new BriefingLog();
        }
    }

    private static void persist(Context context, WidgetState s) {
        try {
            context.getSharedPreferences("ghosthands",
                            Context.MODE_PRIVATE)
                    .edit()
                    .putString(WidgetState.PREF_WIDGET_STATE,
                            s.serialize())
                    .apply();
        } catch (Throwable ignored) { }
    }
}
