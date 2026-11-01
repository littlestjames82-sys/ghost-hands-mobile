package com.ghosthands.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v3.22 (Everything Train R11 — run reports): a finished run,
 * rendered as ONE self-contained HTML file the user can share
 * out of the app — the answer card, the watch verdict, a plain
 * timeline of the run, and its screenshots, in a document that
 * renders identically offline, in a mail client, in any
 * browser.
 *
 * The discipline, pinned by the harness:
 *
 *  - NO JavaScript, no external resources of any kind. Inline
 *    <style> only. The document is generated text, never page
 *    JS (the no-JS rule is about what the app EMITS too).
 *  - EVERY trail-derived string passes through esc() — the
 *    HTML five — in text and attribute positions alike. A
 *    trail is page content: the seatbelt pack exists because
 *    pages lie, and a page that can put markup into a fact, a
 *    diff line, or a title must find it inert here. There is
 *    no raw-HTML path anywhere in this class.
 *  - An <a href> is emitted only for a URL that PARSES with
 *    scheme exactly http or https and a host; anything else
 *    (javascript:, file:, scheme-less, host-less) renders as
 *    escaped plain text.
 *  - Size is a policy, not an accident: images enter at most
 *    MAX_IMAGES strong (end shot first, earliest steps win),
 *    re-encoded to IMG_MAX_WIDTH / JPEG_QUALITY by the
 *    Activity, and the whole file is capped at
 *    MAX_REPORT_BYTES by a degradation ladder whose every
 *    fired rung PRINTS its omission in the report.
 *
 * Pure: no Android, no I/O. The Activity owns the impure half
 * (reading the trail, decoding / scaling / encoding the
 * JPEGs, enforcing the byte caps with this class's constants
 * and plan, writing the file, firing the share intent) the
 * way Shots' javadoc names the same seam. A report is a
 * derived artifact, never a record: it is generated from the
 * trail on demand, is regenerable, and its generation writes
 * nothing back into the trail (no EVENT_TYPES change — see
 * the R11 notes in BUILD_NOTES).
 */
public final class RunReport {

    /** The reports store's directory under the files dir. */
    public static final String DIR_NAME = "reports";

    /** How many reports are kept, newest first (Shots'
     *  retention discipline, its own store). */
    public static final int MAX_KEPT_REPORTS = 20;

    /** Stored shots are re-encoded to at most this width,
     *  at this quality, before base64. */
    public static final int IMG_MAX_WIDTH = 720;
    public static final int JPEG_QUALITY = 70;

    /** Ladder rung 2's smaller re-encode. */
    public static final int SMALL_WIDTH = 540;
    public static final int SMALL_QUALITY = 60;

    /** The end shot plus at most this many minus one step
     *  shots; the earliest step shots win. */
    public static final int MAX_IMAGES = 8;

    /** The final file's hard cap, base64 inflation included. */
    public static final long MAX_REPORT_BYTES = 5_000_000L;

    private RunReport() { }

    // ---------------- naming + retention ----------------

    /**
     * The report file name for a trail file name:
     * "report-" + the trail's base + ".html" — so a report
     * sorts beside its run and name order is time order.
     * Null when there is no usable base name.
     */
    public static String fileNameForTrail(String trailFileName) {
        if (trailFileName == null) return null;
        String base = trailFileName;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.endsWith(".jsonl")) {
            base = base.substring(0,
                    base.length() - ".jsonl".length());
        }
        if (base.length() == 0) return null;
        return "report-" + base + ".html";
    }

    /** The names to delete so only the newest
     *  MAX_KEPT_REPORTS remain (Shots.excess, this store's
     *  cap). */
    public static List<String> excess(List<String> names) {
        return Shots.excess(names, MAX_KEPT_REPORTS);
    }

    // ---------------- escaping + hrefs ----------------

    /** The HTML five, for text and attribute positions
     *  alike. Null reads as empty. */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c == '\'') sb.append("&#39;");
            else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Whether a URL may be emitted as an <a href>: it must
     * PARSE (not prefix-sniff), its scheme must be exactly
     * http or https, and it must carry a host. Anything else
     * renders as escaped plain text.
     */
    public static boolean linkable(String url) {
        if (url == null || url.length() == 0) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            String p = u.getProtocol();
            if (!"http".equalsIgnoreCase(p)
                    && !"https".equalsIgnoreCase(p)) {
                return false;
            }
            String h = u.getHost();
            return h != null && h.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- the image plan ----------------

    /** One image the Activity found on disk and encoded:
     *  its store file name, its step (0 = the end shot), and
     *  its base64 JPEG (generated data — never trail text, so
     *  it is the one thing render() does not escape). */
    public static final class Image {
        public final String file;
        public final int step;
        public final boolean end;
        public final String base64;

        public Image(String file, int step, boolean end,
                     String base64) {
            this.file = file;
            this.step = step;
            this.end = end;
            this.base64 = base64;
        }

        public String caption() {
            return end ? "End of run" : "Step " + step;
        }
    }

    /** The ladder's verdict: which rung fired last, how many
     *  of the candidates (a prefix — the end shot and the
     *  earliest steps) are included, and the omission notes
     *  to print, in firing order. */
    public static final class ImagePlan {
        public int rung = 0;
        public int includedCount = 0;
        public final List<String> notes =
                new ArrayList<String>();
    }

    /** The base64 length of n raw bytes (the accounting the
     *  cap is judged on — the file carries the encoding, not
     *  the JPEG). */
    public static long base64Length(long rawBytes) {
        if (rawBytes <= 0) return 0;
        return 4L * ((rawBytes + 2) / 3);
    }

    /**
     * The degradation ladder, pure over the candidate count
     * (candidates are ordered end-shot-first, then step shots
     * in step order), the candidates' base64 lengths at the
     * full encode and at the smaller encode (either may be
     * null when the Activity has not produced it), and the
     * document's own byte overhead. Rungs fire in order:
     *  1. drop step shots beyond MAX_IMAGES,
     *  2. re-encode all images smaller,
     *  3. drop all step shots,
     *  4. drop the end shot — after which the document is
     *     text-only and ships regardless: the report must
     *     never fail to exist because its photos were heavy.
     * Every fired rung contributes its note.
     */
    public static ImagePlan planImages(int candidateCount,
                                       long[] sizesFull,
                                       long[] sizesSmall,
                                       long overheadBytes) {
        ImagePlan plan = new ImagePlan();
        if (candidateCount <= 0) return plan;
        int included = Math.min(candidateCount, MAX_IMAGES);
        if (candidateCount > MAX_IMAGES) {
            plan.rung = 1;
            plan.notes.add((candidateCount - MAX_IMAGES)
                    + " step screenshots omitted to keep this "
                    + "file shareable");
        }
        if (total(sizesFull, included) + overheadBytes
                <= MAX_REPORT_BYTES) {
            plan.includedCount = included;
            return plan;
        }
        long[] sizes = sizesFull;
        if (sizesSmall != null) {
            plan.rung = 2;
            plan.notes.add("Photos were shrunk to keep this "
                    + "file shareable");
            sizes = sizesSmall;
            if (total(sizes, included) + overheadBytes
                    <= MAX_REPORT_BYTES) {
                plan.includedCount = included;
                return plan;
            }
        }
        if (included > 1) {
            plan.rung = 3;
            plan.notes.add("Step screenshots omitted to keep "
                    + "this file shareable");
            if (total(sizes, 1) + overheadBytes
                    <= MAX_REPORT_BYTES) {
                plan.includedCount = 1;
                return plan;
            }
        }
        plan.rung = 4;
        plan.notes.add("Screenshots omitted to keep this "
                + "file shareable");
        plan.includedCount = 0;
        return plan;
    }

    private static long total(long[] sizes, int n) {
        if (sizes == null) return 0;
        long t = 0;
        int m = Math.min(n, sizes.length);
        for (int i = 0; i < m; i++) t += sizes[i];
        return t;
    }

    // ---------------- rendering ----------------

    /**
     * The complete document. Inputs are what AnswerCard
     * already assembles: the card, the trail's events (the
     * card's own sourceEvents discipline), the images the
     * plan kept (already encoded by the Activity), the
     * plan's notes, and the generation time. A shot the
     * trail names but the image list does not carry (pruned,
     * or dropped by the ladder) is simply absent — no broken
     * image, no error.
     */
    public static String render(AnswerCard card,
                                List<Map<String, Object>> events,
                                List<Image> images,
                                List<String> imageNotes,
                                long generatedMs) {
        if (card == null) return "";
        if (events == null) {
            events = new ArrayList<Map<String, Object>>();
        }
        if (images == null) images = new ArrayList<Image>();
        if (imageNotes == null) {
            imageNotes = new ArrayList<String>();
        }
        Set<String> embedded = new HashSet<String>();
        for (int i = 0; i < images.size(); i++) {
            embedded.add(images.get(i).file);
        }
        Map<String, Object> runEv = firstOfType(events, "run");
        long runMs = eventTsMs(events);
        if (runMs <= 0) runMs = generatedMs;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" "
                + "content=\"width=device-width, "
                + "initial-scale=1\">\n");
        sb.append("<title>Ghost Hands — run report</title>\n");
        sb.append("<style>\n")
                .append("body{background:#04070F;color:#EAF6FF;"
                        + "font-family:Georgia,serif;"
                        + "margin:24px;line-height:1.5}\n")
                .append("h1{font-size:22px;margin:0 0 4px}\n")
                .append("h2{font-size:16px;color:#38C6FF;"
                        + "margin:22px 0 6px}\n")
                .append(".muted{color:#8FA8BF;font-size:13px}\n")
                .append(".goal{font-size:17px}\n")
                .append("ul{margin:6px 0;padding-left:20px}\n")
                .append(".timeline{font-family:monospace;"
                        + "font-size:13px;color:#9FD8F2;"
                        + "white-space:pre-wrap}\n")
                .append("figure{margin:14px 0}\n")
                .append("img{max-width:100%;height:auto;"
                        + "border:1px solid #24506E;"
                        + "border-radius:8px}\n")
                .append("figcaption{color:#8FA8BF;"
                        + "font-size:13px;margin-top:4px}\n")
                .append("a{color:#38C6FF}\n")
                .append("</style>\n</head>\n<body>\n");

        // ---- header ----
        sb.append("<h1>Ghost Hands — run report</h1>\n");
        if (card.goal.length() > 0) {
            sb.append("<p class=\"goal\">").append(esc(card.goal))
                    .append("</p>\n");
        }
        sb.append("<p class=\"muted\">")
                .append(esc(dateLabel(runMs))).append("</p>\n");
        sb.append("<p>").append("done".equals(card.outcome)
                ? "Answer: " : "Outcome: ")
                .append(esc(card.outcomeLine)).append("</p>\n");
        String stats = statsLine(card, events);
        if (stats.length() > 0) {
            sb.append("<p class=\"muted\">").append(esc(stats))
                    .append("</p>\n");
        }
        if (runEv != null) {
            String pack = str(runEv.get("pack"));
            String model = str(runEv.get("model"));
            if (pack.length() > 0 || model.length() > 0) {
                sb.append("<p class=\"muted\">");
                if (pack.length() > 0) {
                    sb.append("Policy: ").append(esc(pack));
                }
                if (model.length() > 0) {
                    if (pack.length() > 0) sb.append(" · ");
                    sb.append("Model: ").append(esc(model));
                }
                sb.append("</p>\n");
            }
        }

        // ---- answer ----
        sb.append("<h2>Answer</h2>\n");
        if (card.findings.length() > 0) {
            sb.append("<p>What it found (excerpt from the "
                    + "source page):</p>\n<p>")
                    .append(esc(card.findings)).append("</p>\n");
        }
        if (!card.facts.isEmpty()) {
            sb.append("<p>Facts noted:</p>\n<ul>\n");
            for (int i = 0; i < card.facts.size(); i++) {
                sb.append("<li>")
                        .append(esc(stripDash(card.facts.get(i))))
                        .append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        if (!card.seededFacts.isEmpty()) {
            sb.append("<p>From the previous step:</p>\n<ul>\n");
            for (int i = 0; i < card.seededFacts.size(); i++) {
                sb.append("<li>")
                        .append(esc(card.seededFacts.get(i)))
                        .append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        if (card.sourceUrl.length() > 0) {
            sb.append("<p>Source: ");
            if (card.sourceTitle.length() > 0) {
                sb.append(esc(card.sourceTitle)).append(" — ");
            }
            if (linkable(card.sourceUrl)) {
                sb.append("<a href=\"")
                        .append(esc(card.sourceUrl)).append("\">")
                        .append(esc(card.sourceUrl))
                        .append("</a>");
            } else {
                sb.append(esc(card.sourceUrl));
            }
            sb.append("</p>\n");
        }

        // ---- watch verdict (the card's banner, in full) ----
        String banner = card.watchBanner();
        if (banner.length() > 0) {
            sb.append("<h2>Watch</h2>\n<p>");
            String[] lines = banner.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append("<br>\n");
                sb.append(esc(lines[i]));
            }
            sb.append("</p>\n");
        }

        // ---- timeline ----
        List<String> tl = timeline(events, embedded);
        if (!tl.isEmpty()) {
            sb.append("<h2>Timeline</h2>\n"
                    + "<div class=\"timeline\">");
            for (int i = 0; i < tl.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(esc(tl.get(i)));
            }
            sb.append("</div>\n");
        }

        // ---- screenshots ----
        if (!images.isEmpty() || !imageNotes.isEmpty()) {
            sb.append("<h2>Screenshots</h2>\n");
            for (int i = 0; i < imageNotes.size(); i++) {
                sb.append("<p class=\"muted\">")
                        .append(esc(imageNotes.get(i)))
                        .append("</p>\n");
            }
            for (int i = 0; i < images.size(); i++) {
                Image im = images.get(i);
                sb.append("<figure><img src=\"data:image/jpeg;"
                        + "base64,").append(im.base64)
                        .append("\" alt=\"")
                        .append(esc(im.caption())).append("\">")
                        .append("<figcaption>")
                        .append(esc(im.caption()))
                        .append("</figcaption></figure>\n");
            }
        }

        sb.append("<p class=\"muted\">Report generated ")
                .append(esc(dateLabel(generatedMs)))
                .append(" from this run's trail.</p>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /** The header stats line: steps and duration from the
     *  card's own stats, then the counts a reader asks about
     *  — approvals asked, downloads — each only when nonzero. */
    private static String statsLine(AnswerCard card,
                                    List<Map<String, Object>> events) {
        StringBuilder sb = new StringBuilder();
        if (card.hasStats) {
            sb.append(card.steps).append(" steps · ")
                    .append(card.seconds).append(" s");
        }
        int approvals = 0;
        int downloads = 0;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            String type = str(ev.get("type"));
            if ("govern".equals(type)
                    && Governor.ASK.equals(str(ev.get("outcome")))) {
                approvals++;
            } else if ("download".equals(type)) {
                downloads++;
            }
        }
        if (approvals > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(approvals).append(approvals == 1
                    ? " approval asked" : " approvals asked");
        }
        if (downloads > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(downloads).append(downloads == 1
                    ? " download" : " downloads");
        }
        return sb.toString();
    }

    /**
     * One plain line per event a reader acts on, in trail
     * order. Types not on the list (fit, map_block, guard
     * internals, and any type a newer trail knows that this
     * build does not) are skipped, never an error — a report
     * must not fail because the record outgrew it. Tab
     * events fold into one counts line at the first tab
     * event's position.
     */
    private static List<String> timeline(
            List<Map<String, Object>> events,
            Set<String> embedded) {
        int tabOpened = 0, tabSwitched = 0, tabClosed = 0;
        for (int i = 0; i < events.size(); i++) {
            String t = str(events.get(i).get("type"));
            if ("tab_opened".equals(t)) tabOpened++;
            else if ("tab_switched".equals(t)) tabSwitched++;
            else if ("tab_closed".equals(t)) tabClosed++;
        }
        boolean tabLineDone = false;
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            String type = str(ev.get("type"));
            long step = num(ev.get("step"));
            if ("run".equals(type)) {
                StringBuilder line = new StringBuilder("Run started");
                String goal = str(ev.get("goal"));
                if (goal.length() > 0) {
                    line.append(" — goal: ").append(goal);
                }
                String pack = str(ev.get("pack"));
                if (pack.length() > 0) {
                    line.append(" · policy ").append(pack);
                }
                out.add(line.toString());
            } else if ("decide".equals(type)) {
                Object a = ev.get("action");
                if (a instanceof Map) {
                    out.add("Step " + step + " — decided: "
                            + actionShort((Map<?, ?>) a));
                }
            } else if ("govern".equals(type)) {
                StringBuilder line = new StringBuilder();
                line.append("Step ").append(step)
                        .append(" — policy: ")
                        .append(str(ev.get("classification")))
                        .append(" → ")
                        .append(str(ev.get("outcome")));
                String reason = str(ev.get("reason"));
                if (reason.length() > 0) {
                    line.append(" — ").append(reason);
                }
                out.add(line.toString());
            } else if ("approval".equals(type)) {
                if (ev.get("approved") instanceof Boolean) {
                    out.add("Step " + step + " — you "
                            + (Boolean.TRUE.equals(ev.get("approved"))
                                    ? "approved" : "declined")
                            + " this step");
                }
            } else if ("ask".equals(type)) {
                out.add("Step " + step + " — asked you: "
                        + str(ev.get("question")));
            } else if ("answer".equals(type)) {
                out.add("You answered: " + str(ev.get("answer")));
            } else if ("note".equals(type)) {
                String line = "Step " + step + " — noted: "
                        + str(ev.get("fact"));
                String status = str(ev.get("status"));
                if (status.length() > 0
                        && !Scratchpad.ADDED.equals(status)) {
                    line += " (" + status + ")";
                }
                out.add(line);
            } else if ("execute".equals(type)) {
                // read_file is the one execute a reader acts
                // on (the rest is the decide/govern pair's).
                Object a = ev.get("action");
                if (a instanceof Map) {
                    Map<?, ?> am = (Map<?, ?>) a;
                    if ("read_file".equals(str(am.get("action")))) {
                        out.add("Step " + step + " — read file \""
                                + str(am.get("name")) + "\"");
                    }
                }
            } else if ("download".equals(type)) {
                StringBuilder line = new StringBuilder();
                line.append("Download: ")
                        .append(str(ev.get("filename")));
                long bytes = num(ev.get("bytes"));
                if (bytes > 0) {
                    line.append(" (").append(Downloads.sizeLabel(bytes))
                            .append(")");
                }
                String mime = str(ev.get("mime"));
                if (mime.length() > 0) {
                    line.append(" · ").append(mime);
                }
                String status = str(ev.get("status"));
                if (status.length() > 0) {
                    line.append(" — ").append(status);
                }
                out.add(line.toString());
            } else if ("page_watch".equals(type)) {
                out.add("Watch created for "
                        + str(ev.get("url")) + " (by you)");
            } else if ("tab_opened".equals(type)
                    || "tab_switched".equals(type)
                    || "tab_closed".equals(type)) {
                if (!tabLineDone) {
                    tabLineDone = true;
                    out.add("Tabs: " + tabOpened + " opened, "
                            + tabSwitched + " switched, "
                            + tabClosed + " closed");
                }
            } else if ("assist".equals(type)) {
                String phase = str(ev.get("phase"));
                if ("offered".equals(phase)) {
                    out.add("Step " + step
                            + " — co-pilot offered");
                } else if ("tapped".equals(phase)) {
                    String name = str(ev.get("name"));
                    out.add("Step " + step + " — you tapped"
                            + (name.length() > 0
                                    ? " \"" + name + "\"" : "")
                            + " to help past a stuck point");
                } else if ("declined".equals(phase)) {
                    out.add("Step " + step
                            + " — co-pilot declined");
                }
            } else if ("screenshot".equals(type)) {
                String f = str(ev.get("file"));
                out.add("End-of-run screenshot taken"
                        + (embedded.contains(f)
                                ? " (embedded below)"
                                : " (not in this report)"));
            } else if ("step_screenshot".equals(type)) {
                String f = str(ev.get("file"));
                out.add("Step " + step + " screenshot taken"
                        + (embedded.contains(f)
                                ? " (embedded below)"
                                : " (not in this report)"));
            } else if ("stop".equals(type)) {
                out.add("Run ended: " + AnswerCard.outcomeLine(
                        str(ev.get("reason")),
                        str(ev.get("summary"))));
            }
            // Everything else — perceive, result, guard, fit,
            // read, recover, race, detour, dialog, route,
            // run_summary, stale_stash, watch (its own
            // section), chain_seed (the answer's group), and
            // any unknown future type — prints nothing.
        }
        return out;
    }

    /** A decided action in the feed's own short form,
     *  rebuilt from its trail map (the element's name lives
     *  in the execute record's descriptor, not here — the
     *  short form is the action and its arguments). */
    private static String actionShort(Map<?, ?> a) {
        String kind = str(a.get("action"));
        StringBuilder sb = new StringBuilder(kind);
        Object el = a.get("el");
        if (el instanceof Number) {
            sb.append(" #").append(((Number) el).longValue());
        }
        String url = str(a.get("url"));
        if (url.length() > 0) sb.append(" ").append(url);
        String query = str(a.get("query"));
        if (query.length() > 0) {
            sb.append(" \"").append(query).append("\"");
        }
        String text = str(a.get("text"));
        if (text.length() > 0) {
            if (text.length() > 60) text = text.substring(0, 60) + "…";
            sb.append(" : \"").append(text).append("\"");
        }
        String name = str(a.get("name"));
        if (name.length() > 0) {
            sb.append(" \"").append(name).append("\"");
        }
        Object tab = a.get("tab");
        if (tab instanceof Number) {
            sb.append(" tab ").append(((Number) tab).longValue());
        }
        return sb.toString();
    }

    // ---------------- small helpers ----------------

    private static Map<String, Object> firstOfType(
            List<Map<String, Object>> events, String type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.equals(str(events.get(i).get("type")))) {
                return events.get(i);
            }
        }
        return null;
    }

    /** The run's start, from its first event's timestamp
     *  (seconds, the trail's own stamp); 0 when none. */
    private static long eventTsMs(List<Map<String, Object>> events) {
        for (int i = 0; i < events.size(); i++) {
            Object ts = events.get(i).get("ts");
            if (ts instanceof Number) {
                return (long) (((Number) ts).doubleValue() * 1000.0);
            }
        }
        return 0;
    }

    /** "Sun Oct 26, 2026, 2:14 PM" — the card's time label
     *  with the date, for a document that travels. */
    public static String dateLabel(long tsMs) {
        return new SimpleDateFormat("EEE MMM d, yyyy, h:mm a",
                Locale.US).format(new Date(tsMs));
    }

    private static String stripDash(String s) {
        if (s == null) return "";
        return s.startsWith("- ") ? s.substring(2) : s;
    }

    private static String str(Object o) {
        return (o == null) ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : 0;
    }
}
