package com.ghosthands.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v3.27 (Everything Train R13 — unattended mode): the
 * foreground service that runs one opted-in check with
 * nobody present. Design authority: R13_SPIKE_REPORT.md
 * §8 — this class is that list, executed:
 *
 *   - Started only by CheckReceiver's RUN judgement (an
 *     exact-alarm firing, grants re-judged at fire time),
 *     or not at all: a start that throws degrades to the
 *     check's reminder (the receiver catches and posts).
 *   - A specialUse foreground service for its whole life:
 *     startForeground is the first thing onStartCommand
 *     does, with the manifest subtype naming the use case
 *     in plain words.
 *   - A partial wake lock held only for the run (the
 *     budget plus slack, acquired with a timeout so it
 *     cannot leak) and released in a finally path.
 *   - The run itself is an ordinary Agent loop under the
 *     READONLY pack — forced here, by construction: this
 *     class loads PolicyPacks.loadPack("readonly") and
 *     nothing else, whatever pack the goal was saved
 *     with — behind a HeadlessUi (silence is a deny, the
 *     widget's stop latch and the ten-minute wall-clock
 *     budget both stop it), with tabs pinned off, no
 *     grant book (a remembered approval is an attended
 *     artefact; unattended holds none), and the run
 *     record stamped "unattended".
 *   - The finish applies the attended matrix's honest
 *     core: the answer card from the trail, the watch
 *     verdict (recorded in the trail, baseline advanced,
 *     briefing appended, a change notified unless quiet
 *     hours hold it, no-change silent), a finish
 *     notification for unwatched endings, and the firing
 *     book's outcome — done, or failed with the reason
 *     carried into the morning briefing. No buzz, beep or
 *     speech: nobody is present to hear them, and the
 *     settings copy has always tied those to presence.
 *   - The run marker and the widget board are written in
 *     the Activity's own discipline (plain marker at
 *     start, deleted at every ending; board started /
 *     finished), so a killed run reads as interrupted at
 *     the next launch exactly like an attended one, and
 *     the widget's Stop is offered against this run.
 *
 * One run at a time: a second start while one is live (or
 * while a fresh run marker witnesses another run, attended
 * or not) refuses — it records the failure, briefs it, and
 * posts the check's reminder so the user can run it by
 * hand. Two 1.3 GB model loads are not a thing this phone
 * survives, and pretending otherwise would be the
 * dishonest failure the spike forbids.
 *
 * The WebView is this service's own, created on the main
 * thread and never attached to a window — the spike's
 * §9 UNVERIFIED item, disclosed in the release notes:
 * whether an unattached view lays pages out like the
 * visible one is a device question. Renderer death is
 * handled the spike's way: one rebuild-and-reload, then
 * an honest failed check.
 */
public class UnattendedService extends Service {

    /** The goal-name extra (the receiver's own key, reused:
     *  a check firing names its goal the same way everywhere). */
    public static final String EXTRA_GOAL_NAME =
            Alerts.EXTRA_GOAL_NAME;

    private static final int FGS_NOTIFICATION_ID = 27413;

    /** How fresh another run's marker must be to count as
     *  "a run is live" (the busy judgement): a live attended
     *  run is unbounded by the unattended budget, so the
     *  window is generous; an older marker is a corpse the
     *  launch pass will account for, not a lock. */
    private static final long BUSY_MARKER_MS =
            2L * 60L * 60L * 1000L;

    private static final AtomicBoolean RUNNING =
            new AtomicBoolean(false);

    /** Start the service for one goal's unattended run.
     *  False when the platform refused the start (the
     *  background-start restrictions can still bite when a
     *  grant was revoked between the receiver's judgement
     *  and this call): the caller degrades to the reminder. */
    public static boolean start(Context ctx, String goalName) {
        try {
            Intent i = new Intent(ctx, UnattendedService.class);
            i.putExtra(EXTRA_GOAL_NAME, goalName);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags,
                              final int startId) {
        String name = (intent == null) ? null
                : intent.getStringExtra(EXTRA_GOAL_NAME);
        if (name == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        // The foreground posting comes first, unconditionally:
        // a service started as a foreground service must call
        // startForeground promptly or the platform kills it.
        // (The typed overload exists from API 29; the type
        // itself is named from API 34 — below both, the
        // manifest's foregroundServiceType still declares it.)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FGS_NOTIFICATION_ID,
                    buildNotification(name), fgsType());
        } else {
            startForeground(FGS_NOTIFICATION_ID,
                    buildNotification(name));
        }
        if (!RUNNING.compareAndSet(false, true)) {
            // A second run while one is live: refuse, on the
            // record (the firing book + a reminder), below.
            recordBusyRefusal(name);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String goalName = name;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runCheck(goalName);
                } finally {
                    RUNNING.set(false);
                    stopForeground(true);
                    stopSelf(startId);
                }
            }
        });
        t.setName("ghost-hands-unattended");
        t.start();
        return START_NOT_STICKY;
    }

    private int fgsType() {
        return (Build.VERSION.SDK_INT >= 34)
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                : 0;
    }

    private Notification buildNotification(String goalName) {
        Alerts.ensureChannels(this);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this,
                FGS_NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this,
                        Alerts.CHANNEL_CHECKS)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info);
        b.setContentTitle("Ghost Hands — running your check");
        b.setContentText(goalName
                + " — unattended, read-only. Stop it from the "
                + "widget or the app.");
        b.setContentIntent(pi);
        return b.build();
    }

    // ----------------------------------------------------
    // the run
    // ----------------------------------------------------

    private void runCheck(String name) {
        SharedPreferences prefs = getSharedPreferences(
                "ghosthands", Context.MODE_PRIVATE);
        Settings settings = loadSettings(prefs);
        SavedGoals store = SavedGoals.parse(
                prefs.getString(Alerts.PREF_SAVED_GOALS, null));
        SavedGoals.SavedGoal g = store.find(name);
        if (g == null || !g.unattended) {
            // The goal left (or opted out) between the firing
            // and this start: nothing to run, on the record.
            recordOutcome(prefs, name,
                    UnattendedLog.OUT_FAILED, "",
                    "the goal was deleted or opted out before "
                            + "its unattended run could start");
            return;
        }
        if (otherRunLive()) {
            recordOutcome(prefs, name,
                    UnattendedLog.OUT_FAILED, "",
                    "another run was already in progress");
            Alerts.postCheck(this, g);
            return;
        }

        PowerManager pm = (PowerManager)
                getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = (pm == null) ? null
                : pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "ghosthands:unattended");
        if (lock != null) {
            lock.setReferenceCounted(false);
            // Acquired WITH a timeout — the budget plus its
            // slack — so even a wedged run cannot hold the
            // phone awake past it.
            lock.acquire(Unattended.MAX_RUN_MS
                    + Unattended.WAKELOCK_SLACK_MS);
        }
        ModelEngine engine = new ModelEngine();
        try {
            String stamp = new SimpleDateFormat(
                    "yyyyMMdd-HHmmss", Locale.US)
                    .format(new Date());
            File trailFile = new File(
                    new File(getFilesDir(), "trails"),
                    "trail-" + stamp + ".jsonl");
            Trail trail = new Trail(trailFile);
            writeRunMarker(g.goal, settings.modelId);
            writeWidgetState(WidgetState.started(g.goal,
                    trailFile.getName(),
                    System.currentTimeMillis()));
            GhostWidget.refresh(this);

            ModelStore models = new ModelStore(getFilesDir());
            ModelStore.Spec spec =
                    ModelStore.specFor(settings.modelId);
            if (spec == null || !models.ready(spec)) {
                recordOutcome(prefs, name,
                        UnattendedLog.OUT_FAILED, "",
                        "the model is not downloaded, so the "
                                + "unattended check could not "
                                + "run");
                finishWidget(prefs, "Model not downloaded");
                return;
            }
            engine.setGrammarOn(settings.grammarOn);
            engine.setThreads(settings.threads);
            engine.setPrefixCacheOn(settings.prefixCacheOn);
            String path = models.fileFor(spec)
                    .getAbsolutePath();
            if (!engine.load(path)) {
                recordOutcome(prefs, name,
                        UnattendedLog.OUT_FAILED, "",
                        "the model would not load, so the "
                                + "unattended check could not "
                                + "run");
                finishWidget(prefs, "Model load failed");
                return;
            }

            ServiceHands hands = new ServiceHands(settings);
            try {
                if (settings.homePage.length() > 0) {
                    // The page the app itself opens to — the
                    // closest honest analog of the attended
                    // browser's starting page.
                    hands.gotoUrl(settings.homePage);
                }
                HeadlessUi ui = new HeadlessUi(
                        System.currentTimeMillis());
                PolicyPacks.Pack pack =
                        PolicyPacks.loadPack("readonly");
                Agent agent = new Agent(hands, engine, pack,
                        trail, ui, settings.modelId,
                        settings.grammarOn, settings.maxSteps,
                        settings.copilotOn, settings.autoReadOn,
                        settings.chunkPref, settings.threads,
                        settings.fallbackEngine,
                        settings.desktopMode,
                        settings.prefixCacheOn,
                        settings.liveStatsOn,
                        false, Settings.END_CARD, false,
                        settings.batteryWarnOn,
                        settings.plannerStyle, null, null, null,
                        null, false, null, null, 0, 0, null,
                        null, true);
                agent.run(g.goal);
                finishRun(prefs, settings, store, g, trail,
                        trailFile, ui);
            } finally {
                hands.destroy();
            }
        } catch (Exception e) {
            recordOutcome(prefs, name,
                    UnattendedLog.OUT_FAILED, "",
                    "the unattended check ended in an error ("
                            + String.valueOf(e.getMessage())
                            + ")");
            finishWidget(prefs, "Check error");
        } finally {
            try { engine.close(); } catch (Exception ignored) { }
            deleteRunMarker();
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    /** The finish, mirroring the Activity's matrix (see the
     *  class doc): card, watch verdict, notifications,
     *  briefing, firing book, widget board. */
    private void finishRun(SharedPreferences prefs,
                           Settings settings, SavedGoals store,
                           SavedGoals.SavedGoal g, Trail trail,
                           File trailFile, HeadlessUi ui) {
        List<Map<String, Object>> events = trail.events();
        AnswerCard card = AnswerCard.fromEvents(events, g.goal);
        String outcome = (card == null) ? "" : card.outcome;
        boolean done = "done".equals(outcome);

        WatchCompare.Verdict verdict = null;
        boolean quietDefer = false;
        if (g.watched) {
            verdict = processWatch(prefs, store, g, trail,
                    settings);
            // The same judgement processWatch stamped on the
            // trail event: a change inside quiet hours is
            // recorded, never notified.
            quietDefer = verdict != null && verdict.changed()
                    && QuietHours.shouldDefer(
                            settings.quietHoursOn, true,
                            Calendar.getInstance().get(
                                    Calendar.HOUR_OF_DAY),
                            settings.quietStartHour,
                            settings.quietEndHour);
            if (verdict != null) {
                appendBriefing(prefs, g.name,
                        BriefingLog.KIND_GOAL, verdict.state,
                        verdict.diffLines.isEmpty() ? ""
                                : verdict.diffLines.get(0),
                        quietDefer);
            }
        }

        // The notification matrix, headless form.
        if (verdict != null && verdict.changed()) {
            if (!quietDefer) {
                String first = verdict.diffLines.isEmpty()
                        ? "the answer changed"
                        : verdict.diffLines.get(0);
                Alerts.postWatchChanged(this, g.name, first,
                        trailFile.getAbsolutePath());
            }
        } else if (verdict != null
                && WatchCompare.NO_CHANGE.equals(verdict.state)) {
            // Quiet by design.
        } else if (card != null) {
            // Every other ending posts the finish card's
            // notification: an unattended run ends off-screen
            // by definition.
            Alerts.postRunFinished(this, card,
                    trailFile.getAbsolutePath());
        }

        if (done) {
            recordOutcomeOnly(prefs, g.name,
                    UnattendedLog.OUT_DONE, trailFile.getName());
        } else {
            String why;
            if (ui.budgetTripped()) {
                why = "the unattended run hit its 10-minute "
                        + "budget and was stopped";
            } else if (RunControl.stopRequested()) {
                why = "the unattended run was stopped";
            } else if (outcome.length() > 0) {
                why = "the unattended run ended " + outcome;
            } else {
                why = "the unattended run never started";
            }
            if (!g.watched) {
                // A watched failure already briefs through
                // its verdict; an unwatched one briefs here,
                // so a failed check is never silent (spike
                // §7). A watched change / no-change speaks
                // for itself through the verdict path.
                appendBriefing(prefs, g.name,
                        BriefingLog.KIND_GOAL,
                        WatchCompare.NOT_DONE, why, false);
            }
            recordOutcomeOnly(prefs, g.name,
                    UnattendedLog.OUT_FAILED, trailFile.getName());
        }
        finishWidget(prefs, card == null ? "Check finished"
                : card.notificationLine());
    }

    /** The Activity's processWatch, carried over: judge the
     *  run against the goal's baseline, record the "watch"
     *  event in the trail, advance the baseline, persist.
     *  Returns the verdict (null when the goal is unwatched
     *  or gone). */
    private WatchCompare.Verdict processWatch(
            SharedPreferences prefs, SavedGoals store,
            SavedGoals.SavedGoal g, Trail trail,
            Settings settings) {
        if (trail == null || g == null || !g.watched) {
            return null;
        }
        List<Map<String, Object>> events = trail.events();
        int step = lastStopStep(events);
        WatchCompare.Signature sig =
                WatchCompare.signatureFromEvents(events);
        if (sig == null) {
            trail.record("watch", step, Trail.fields(
                    "goal_name", g.name,
                    "verdict", WatchCompare.NOT_DONE));
            return new WatchCompare.Verdict(
                    WatchCompare.NOT_DONE,
                    new java.util.ArrayList<String>());
        }
        String summary =
                WatchCompare.doneSummaryFromEvents(events);
        WatchCompare.Verdict verdict = WatchCompare.compare(
                g.baseline, g.baselineSummary, sig, summary);
        boolean quietDefer = QuietHours.shouldDefer(
                settings.quietHoursOn, verdict.changed(),
                Calendar.getInstance().get(
                        Calendar.HOUR_OF_DAY),
                settings.quietStartHour, settings.quietEndHour);
        Map<String, Object> fields = Trail.fields(
                "goal_name", g.name,
                "verdict", verdict.state,
                "since_ms", Long.valueOf(
                        g.hasBaseline() ? g.baselineTsMs : 0));
        if (quietDefer) {
            fields.put("quiet", Boolean.TRUE);
        }
        if (!verdict.diffLines.isEmpty()) {
            fields.put("diff",
                    new java.util.ArrayList<Object>(
                            verdict.diffLines));
        }
        trail.record("watch", step, fields);
        g.setBaseline(sig, summary, System.currentTimeMillis(),
                (trail.getPath() == null) ? ""
                        : trail.getPath().getName());
        prefs.edit().putString(Alerts.PREF_SAVED_GOALS,
                store.serialize()).apply();
        return verdict;
    }

    private static int lastStopStep(
            List<Map<String, Object>> events) {
        int step = 0;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            if ("stop".equals(String.valueOf(ev.get("type")))) {
                Object s = ev.get("step");
                if (s instanceof Number) {
                    step = ((Number) s).intValue();
                }
            }
        }
        return step;
    }

    // ----------------------------------------------------
    // the books
    // ----------------------------------------------------

    /** A refusal before any run existed: the firing book
     *  learns the outcome; no briefing entry — the user is
     *  told at fire time by the reminder or the refusal's
     *  own post, and the morning card is for run outcomes. */
    private void recordBusyRefusal(String name) {
        SharedPreferences prefs = getSharedPreferences(
                "ghosthands", Context.MODE_PRIVATE);
        UnattendedLog log = UnattendedLog.parse(prefs.getString(
                UnattendedLog.PREF_UNATTENDED_LOG, ""));
        log.record(name, UnattendedLog.OUT_FAILED,
                System.currentTimeMillis(), "");
        prefs.edit().putString(
                UnattendedLog.PREF_UNATTENDED_LOG,
                log.serialize()).apply();
    }

    /** A failure that produced no run: the firing book AND
     *  a morning-briefing entry — a check that could not
     *  run is a NOT_DONE the briefing must not swallow. */
    private void recordOutcome(SharedPreferences prefs,
                               String name, String outcome,
                               String trailName, String why) {
        appendBriefing(prefs, name, BriefingLog.KIND_GOAL,
                WatchCompare.NOT_DONE, why, false);
        recordOutcomeOnly(prefs, name, outcome, trailName);
    }

    private void recordOutcomeOnly(SharedPreferences prefs,
                                   String name, String outcome,
                                   String trailName) {
        UnattendedLog log = UnattendedLog.parse(prefs.getString(
                UnattendedLog.PREF_UNATTENDED_LOG, ""));
        log.record(name, outcome, System.currentTimeMillis(),
                trailName);
        prefs.edit().putString(
                UnattendedLog.PREF_UNATTENDED_LOG,
                log.serialize()).apply();
    }

    private void appendBriefing(SharedPreferences prefs,
                                String label, String kind,
                                String state, String firstDiff,
                                boolean quietDeferred) {
        BriefingLog log = BriefingLog.parse(prefs.getString(
                BriefingLog.PREF_BRIEFING_LOG, ""));
        log.append(new BriefingLog.Entry(label, kind, state,
                firstDiff, System.currentTimeMillis(),
                quietDeferred));
        prefs.edit().putString(BriefingLog.PREF_BRIEFING_LOG,
                log.serialize()).apply();
        GhostWidget.refresh(this);
    }

    // ----------------------------------------------------
    // marker + widget (the Activity's discipline, mirrored)
    // ----------------------------------------------------

    private File runMarkerFile() {
        return new File(getFilesDir(), "run-in-progress.json");
    }

    private void writeRunMarker(String goal, String modelId) {
        try {
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(runMarkerFile());
            out.write(new RunMarker(goal,
                    System.currentTimeMillis(), modelId)
                    .serialize().getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
        } catch (Exception ignored) {
            // Evidence, not machinery (the Activity's rule).
        }
    }

    private void deleteRunMarker() {
        try {
            File f = runMarkerFile();
            if (f.exists()) f.delete();
        } catch (Exception ignored) { }
    }

    /** Is another run — attended or unattended — live
     *  right now? The marker is the witness (see the class
     *  doc); a marker whose trail already ended is a corpse,
     *  not a lock. */
    private boolean otherRunLive() {
        File f = runMarkerFile();
        if (!f.isFile()) return false;
        try {
            String text = new String(
                    java.nio.file.Files.readAllBytes(f.toPath()),
                    "UTF-8");
            RunMarker m = RunMarker.parse(text);
            if (m == null) return false;
            if (System.currentTimeMillis() - m.startedAtMs
                    > BUSY_MARKER_MS) {
                return false;
            }
            if (m.trailName.length() > 0) {
                File tf = new File(
                        new File(getFilesDir(), "trails"),
                        m.trailName);
                if (tf.isFile()) {
                    String tt = new String(
                            java.nio.file.Files.readAllBytes(
                                    tf.toPath()), "UTF-8");
                    if (RunMarker.trailEnded(tt)) return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeWidgetState(WidgetState state) {
        SharedPreferences prefs = getSharedPreferences(
                "ghosthands", Context.MODE_PRIVATE);
        prefs.edit().putString(WidgetState.PREF_WIDGET_STATE,
                state.serialize()).apply();
    }

    private void finishWidget(SharedPreferences prefs,
                              String outcomeLine) {
        WidgetState cur = WidgetState.parse(prefs.getString(
                WidgetState.PREF_WIDGET_STATE, ""));
        writeWidgetState(cur.finished(outcomeLine,
                System.currentTimeMillis()));
        GhostWidget.refresh(this);
    }

    /** The receiver's settings read, duplicated: the same
     *  ALL_KEYS bridge over the same preferences file. */
    private static Settings loadSettings(
            SharedPreferences prefs) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < Settings.ALL_KEYS.length; i++) {
            String v = prefs.getString(Settings.ALL_KEYS[i],
                    null);
            if (v != null) m.put(Settings.ALL_KEYS[i], v);
        }
        return Settings.fromMap(m);
    }

    // ----------------------------------------------------
    // ServiceHands: the run's body
    // ----------------------------------------------------

    /**
     * The unattended run's hands: a single WebView the
     * service owns, driven through the same Eyes scripts
     * the Activity's WebHands uses, on the main thread's
     * handler instead of the Activity's UI thread.
     *
     * Deliberate simplifications against WebHands, each
     * disclosed in the release notes:
     *   - No self-healing re-find on tap/type: the plain
     *     stash call (the pre-v2.0 behaviour). A stale
     *     element errors honestly, and the loop's race /
     *     stale-stash machinery re-perceives and re-decides
     *     the step, as it does for any failed action.
     *   - Reads use the full-page extractor (the legacy
     *     readTextJs path) with its cursor discipline —
     *     no main-content read sessions, no density settle,
     *     no thin-read scroll pass. The loop's thin-MAP
     *     ritual still applies at perceive time; it is the
     *     read's own retries that are absent.
     *   - No tabs, no documents, no accessibility eyes:
     *     the interface defaults answer for all three
     *     (single tab, empty store, eyes that cannot see —
     *     the eyes read the Activity's window, and there
     *     is no Activity here).
     *   - Renderer death (the spike's §9 risk) is recovered
     *     ONCE: the view is rebuilt and the last page
     *     reloaded; a second death fails the step, and the
     *     run ends honestly rather than guessing at a page
     *     it cannot see.
     */
    private class ServiceHands implements Agent.Hands {

        private final Settings settings;
        private final Handler mainHandler =
                new Handler(Looper.getMainLooper());
        private final Object loadLock = new Object();
        private boolean pageLoading = false;
        private WebView view;
        private String defaultUa;
        private String lastUrl = "";
        private volatile boolean rendererGone = false;
        private int rendererRecoveries = 0;
        private final Eyes.ReadCursor readCursor =
                new Eyes.ReadCursor();

        ServiceHands(Settings settings) throws Exception {
            this.settings = settings;
            this.view = buildView();
        }

        private WebView buildView() throws Exception {
            final WebView[] box = new WebView[1];
            final CountDownLatch latch =
                    new CountDownLatch(1);
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try {
                        WebView wv = new WebView(
                                UnattendedService.this);
                        android.webkit.WebSettings s =
                                wv.getSettings();
                        s.setJavaScriptEnabled(true);
                        s.setDomStorageEnabled(true);
                        s.setLoadWithOverviewMode(true);
                        s.setUseWideViewPort(true);
                        if (defaultUa == null) {
                            defaultUa = s.getUserAgentString();
                        }
                        s.setUserAgentString(
                                Settings.userAgentFor(
                                        settings.desktopMode,
                                        defaultUa));
                        wv.setWebViewClient(new WebViewClient() {
                            @Override public void onPageStarted(
                                    WebView v, String url,
                                    android.graphics.Bitmap fav) {
                                synchronized (loadLock) {
                                    pageLoading = true;
                                }
                            }
                            @Override public void onPageFinished(
                                    WebView v, String url) {
                                synchronized (loadLock) {
                                    pageLoading = false;
                                    loadLock.notifyAll();
                                }
                            }
                            @Override public boolean
                                    onRenderProcessGone(
                                            WebView v,
                                            android.webkit
                                                    .RenderProcessGoneDetail
                                                    detail) {
                                // The spike's §9 case: the
                                // renderer died under the
                                // model. Say so, and let the
                                // next perceive rebuild once.
                                rendererGone = true;
                                return true;
                            }
                        });
                        box[0] = wv;
                    } catch (Exception e) {
                        box[0] = null;
                    }
                    latch.countDown();
                }
            });
            if (!latch.await(15, TimeUnit.SECONDS)
                    || box[0] == null) {
                throw new Exception("could not create the "
                        + "unattended browser view");
            }
            return box[0];
        }

        void destroy() {
            final WebView v = view;
            view = null;
            if (v == null) return;
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try { v.destroy(); } catch (Exception ignored) { }
                }
            });
        }

        private String callJs(final String js,
                                long timeoutMs) throws Exception {
            final WebView v = view;
            if (v == null) {
                throw new Exception("the unattended browser "
                        + "view is gone");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] out = new String[1];
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try {
                        v.evaluateJavascript(js,
                                new ValueCallback<String>() {
                                    @Override public void
                                            onReceiveValue(
                                                    String value) {
                                        out[0] = value;
                                        latch.countDown();
                                    }
                                });
                    } catch (Exception e) {
                        out[0] = null;
                        latch.countDown();
                    }
                }
            });
            if (!latch.await(timeoutMs,
                    TimeUnit.MILLISECONDS)) {
                throw new Exception("page did not answer "
                        + "in time");
            }
            return decodeJs(out[0]);
        }

        private String decodeJs(String raw) {
            if (raw == null) return "";
            try {
                Object o = Json.parse(raw);
                if (o instanceof String) return (String) o;
            } catch (Exception ignored) { }
            return raw;
        }

        /** The Activity's settle wait, mirrored: a moment
         *  for a navigation to start, a bounded wait for it
         *  to finish, a short settle after. */
        private void waitForPageSettled() {
            try {
                Thread.sleep(350);
                synchronized (loadLock) {
                    long deadline =
                            System.currentTimeMillis() + 9000;
                    while (pageLoading) {
                        long remain = deadline
                                - System.currentTimeMillis();
                        if (remain <= 0) break;
                        loadLock.wait(remain);
                    }
                }
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void runOnMainAndWait(final Runnable r)
                throws Exception {
            final CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    try { r.run(); } finally {
                        latch.countDown();
                    }
                }
            });
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new Exception("the browser view did "
                        + "not answer in time");
            }
        }

        @Override public Eyes.Perception perceive()
                throws Exception {
            if (rendererGone) {
                if (rendererRecoveries >= 1) {
                    throw new Exception("the page renderer "
                            + "died twice; the check cannot "
                            + "see the page");
                }
                rendererRecoveries++;
                rendererGone = false;
                String url = lastUrl;
                view = buildView();
                if (url.length() > 0
                        && !url.startsWith("about:")) {
                    gotoUrl(url);
                }
            }
            String raw = callJs(Eyes.MAP_JS, 20000);
            Eyes.Perception p =
                    Eyes.parseMap(Json.quote(raw));
            callJs(Eyes.stashJs(p.elements), 8000);
            int win = Eyes.pickMainContent(p.readCandidates,
                    p.legacyCandidateIndex);
            if (win >= 0) {
                String winnerPreview = Eyes.parsePreviewText(
                        callJs(Eyes.previewAtJs(win), 8000));
                p.textPreview = Eyes.preferredPreview(
                        p.textPreview, winnerPreview);
            }
            if (p.url != null) lastUrl = p.url;
            return p;
        }

        @Override public String tap(int el) throws Exception {
            String r = callJs(Eyes.tapJs(el), 15000);
            if (r.startsWith("ok")) waitForPageSettled();
            return r;
        }

        @Override public String type(int el, String text)
                throws Exception {
            return callJs(Eyes.typeJs(el, text), 15000);
        }

        @Override public String choose(int el, String text)
                throws Exception {
            return callJs(Eyes.chooseJs(el, text), 15000);
        }

        @Override public String toggle(int el, String text)
                throws Exception {
            return callJs(Eyes.toggleJs(el, text), 15000);
        }

        @Override public String gotoUrl(final String url)
                throws Exception {
            final String target = (url.contains("://")
                    ? url : "https://" + url);
            final WebView v = view;
            runOnMainAndWait(new Runnable() {
                @Override public void run() {
                    v.loadUrl(target);
                }
            });
            waitForPageSettled();
            return "ok: navigated to " + target;
        }

        @Override public String scroll(String dir)
                throws Exception {
            String r = callJs(Eyes.scrollJs(dir), 15000);
            try { Thread.sleep(300); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return r;
        }

        @Override public String back() throws Exception {
            final WebView v = view;
            final boolean[] could = new boolean[1];
            runOnMainAndWait(new Runnable() {
                @Override public void run() {
                    could[0] = v.canGoBack();
                    if (could[0]) v.goBack();
                }
            });
            if (!could[0]) {
                return "error: no page to go back to";
            }
            waitForPageSettled();
            return "ok: went back";
        }

        /** WebHands.search's three paths, mirrored: a
         *  Wikipedia host goes straight to Special:Search;
         *  a page with a likely search box types there;
         *  otherwise the fallback engine (the READING
         *  setting) — whose DuckDuckGo form has carried the
         *  region pin since v3.26. */
        @Override public String search(String query)
                throws Exception {
            String current = callJs("location.href", 8000);
            String host = "";
            try {
                String h = new java.net.URL(current).getHost();
                if (h != null) host = h.toLowerCase();
            } catch (Exception ignored) { }
            if (host.equals("wikipedia.org")
                    || host.endsWith(".wikipedia.org")) {
                String url = "https://" + host
                        + "/wiki/Special:Search?search="
                        + java.net.URLEncoder.encode(query,
                                "UTF-8")
                        + "&fulltext=1";
                gotoUrl(url);
                return "ok: searched Wikipedia for \""
                        + query + "\"";
            }
            Eyes.Perception p = perceive();
            ElementInfo target =
                    Eyes.pickSearchTarget(p.elements);
            if (target != null) {
                String typed = type(target.number, query);
                if (typed == null || !typed.startsWith("ok")) {
                    return typed == null
                            ? "error: could not type into "
                                    + "the page search box"
                            : typed;
                }
                String sub = callJs(
                        Eyes.submitSearchJs(target.number),
                        15000);
                if (sub != null && sub.startsWith("ok")) {
                    waitForPageSettled();
                }
                String boxText = Eyes.searchBoxText(target);
                if (boxText != null && boxText.length() > 0) {
                    return Eyes.formatSearchedViaBox(boxText,
                            query);
                }
                return "ok: searched this site for \"" + query
                        + "\" (" + typed + "; " + sub + ")";
            }
            String url = SearchEngines.searchUrl(
                    settings.fallbackEngine,
                    java.net.URLEncoder.encode(query, "UTF-8"));
            gotoUrl(url);
            return SearchEngines.searchedMessage(
                    settings.fallbackEngine, query);
        }

        /** v3.34: the weather action's body — the same
         *  data tool the interactive hands carry
         *  (WeatherTool over its production transport), so
         *  a schedule-fired goal check gets the forecast
         *  the same way a foreground run does: one Agent
         *  loop, one tool, both paths. */
        @Override public String weather(String place)
                throws Exception {
            return WeatherTool.forecastFor(place,
                    new WeatherTool.HttpTransport());
        }

        /** The read action on the full-page extractor: the
         *  cursor walks the text across calls and restarts
         *  at the top past the end, saying so — the v1.2
         *  contract, without the read sessions (see the
         *  class doc). */
        @Override public String read(int chunkChars)
                throws Exception {
            int size = (chunkChars > 0) ? chunkChars
                    : Eyes.READ_CHUNK_CHARS;
            String current = callJs("location.href", 8000);
            readCursor.resetFor(current);
            Eyes.ReadChunk chunk = Eyes.parseRead(callJs(
                    Eyes.readTextJs(readCursor.offset, size),
                    15000));
            boolean wrapped = false;
            if (chunk.text.length() == 0
                    && readCursor.offset > 0) {
                readCursor.offset = 0;
                wrapped = true;
                chunk = Eyes.parseRead(callJs(
                        Eyes.readTextJs(0, size), 15000));
            }
            if (chunk.total == 0
                    || chunk.text.length() == 0) {
                return "error: no readable text found on "
                        + "this page";
            }
            int start = chunk.start;
            readCursor.offset = start + chunk.text.length();
            String out = Eyes.formatReadChunk(start,
                    chunk.text, chunk.total);
            if (wrapped) {
                out = out.replaceFirst("ok: read",
                        "ok: restarted at the top of the "
                                + "page; read");
            }
            return out;
        }

        @Override public String reload(String failedUrl)
                throws Exception {
            if (failedUrl != null && failedUrl.length() > 0) {
                gotoUrl(failedUrl);
                return "ok: reloaded " + failedUrl;
            }
            final WebView v = view;
            runOnMainAndWait(new Runnable() {
                @Override public void run() { v.reload(); }
            });
            waitForPageSettled();
            return "ok: reloaded current page";
        }
    }
}
