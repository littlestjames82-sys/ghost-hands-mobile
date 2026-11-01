package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.23 (Everything Train R12 — the home-screen widget): what
 * the widget SAYS, composed pure over the two stores it reads
 * (WidgetState for the run line, BriefingLog for the verdict
 * lines) so the harness pins every wording and GhostWidget
 * only renders.
 *
 * Relative times are computed at RENDER time from the stored
 * timestamps — which is also the widget's staleness
 * confession: an "ago" only advances when something pushes a
 * refresh, and the board is "as of the last transition" by
 * design (updatePeriodMillis is 0; the widget never polls).
 *
 * Verdict lines read the newest entries of the briefing log,
 * goal watches and page watches alike, and IGNORE the
 * entries' briefed flags: the widget is a status board, not a
 * delivery channel — the briefing's exactly-once semantics
 * are R9's and are untouched by anything here.
 *
 * Pure: no Android, no I/O.
 */
public final class WidgetText {

    /** How many verdict lines the board shows. */
    public static final int MAX_VERDICTS = 3;

    private WidgetText() { }

    /** The run line for a JUDGED phase (WidgetState.judge has
     *  already had its say — this composes, never judges). */
    public static String runLine(String judgedPhase, WidgetState s,
                                 long nowMs) {
        String goal = (s == null) ? "" : s.goal;
        if (WidgetState.PHASE_RUNNING.equals(judgedPhase)) {
            String line = "● Running: " + goal;
            if (s != null && s.step > 0) line += " — step " + s.step;
            return line;
        }
        if (WidgetState.PHASE_WAITING_APPROVAL.equals(judgedPhase)) {
            return "● Waiting: approval needed";
        }
        if (WidgetState.PHASE_WAITING_ASK.equals(judgedPhase)) {
            return "● Waiting: answer needed";
        }
        if (WidgetState.PHASE_STOPPING.equals(judgedPhase)) {
            return "■ Stopping…";
        }
        if (WidgetState.PHASE_INTERRUPTED.equals(judgedPhase)) {
            String line = "■ Run interrupted (app closed)";
            if (goal.length() > 0) line += ": " + goal;
            return line;
        }
        if (WidgetState.PHASE_FINISHED.equals(judgedPhase)) {
            return lastLine(s, nowMs);
        }
        // idle: a last finish may still be on the store (an
        // older shape); otherwise the honest empty line.
        if (s != null && s.outcomeLine.length() > 0) {
            return lastLine(s, nowMs);
        }
        return "No runs yet";
    }

    private static String lastLine(WidgetState s, long nowMs) {
        String line = "Last: " + ((s == null) ? "" : s.outcomeLine);
        long ts = (s == null) ? 0 : s.updatedMs;
        if (ts > 0) line += " · " + relTime(ts, nowMs);
        return line;
    }

    /** One verdict line: the state's wording, the first diff
     *  carried for a change only, and the relative time. A
     *  state the board does not know (a future WatchCompare)
     *  renders as its own word rather than a wrong one. */
    public static String verdictLine(BriefingLog.Entry e, long nowMs) {
        if (e == null) return "";
        String body;
        if (WatchCompare.CHANGED.equals(e.state)) {
            body = e.label + ": changed";
            if (e.firstDiff != null && e.firstDiff.length() > 0) {
                body += " — " + e.firstDiff;
            }
        } else if (WatchCompare.NOT_DONE.equals(e.state)) {
            body = e.label + ": couldn't check";
        } else if (WatchCompare.BASELINE.equals(e.state)) {
            body = e.label + ": now watching";
        } else if (WatchCompare.NO_CHANGE.equals(e.state)) {
            body = e.label + ": no change";
        } else {
            body = e.label + ": " + e.state;
        }
        if (e.tsMs > 0) body += " · " + relTime(e.tsMs, nowMs);
        return body;
    }

    /** The verdict block: the newest MAX_VERDICTS entries,
     *  newest first, or the one quiet empty line. */
    public static List<String> verdictLines(BriefingLog log, long nowMs) {
        List<String> out = new ArrayList<String>();
        if (log == null || log.size() == 0) {
            out.add("No watched checks yet");
            return out;
        }
        List<BriefingLog.Entry> all = log.all();
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < MAX_VERDICTS; i--) {
            out.add(verdictLine(all.get(i), nowMs));
            shown++;
        }
        return out;
    }

    /** The whole verdict block as one multi-line string (the
     *  widget layout's single verdicts view). */
    public static String verdictBlock(BriefingLog log, long nowMs) {
        List<String> lines = verdictLines(log, nowMs);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** A stored timestamp against now: "just now" under a
     *  minute, then minutes, hours, days — always "… ago"
     *  past the first rung. A timestamp in the future (clock
     *  moved) reads "just now": the board never claims a
     *  negative age. */
    public static String relTime(long tsMs, long nowMs) {
        long d = nowMs - tsMs;
        if (d < 60L * 1000L) return "just now";
        long mins = d / (60L * 1000L);
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }

    /** The Stop button's pure decision (the provider renders
     *  it): live judged phase → the button carries the stop
     *  intent at full strength; anything else → it renders
     *  dimmed and carries the open-app intent (RemoteViews
     *  cannot truly disable across all launchers, so a tap
     *  does something honest instead). */
    public static boolean stopArmed(String judgedPhase) {
        return WidgetState.isLivePhase(judgedPhase);
    }
}
