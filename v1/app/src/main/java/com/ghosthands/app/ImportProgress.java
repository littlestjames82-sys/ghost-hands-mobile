package com.ghosthands.app;

/** v3.33: the trained-model import's progress, as the
 *  BRAIN section shows it. Through v3.32 the import
 *  worker reported only to the home screen's model card
 *  (statusText + progressBar), so the very screen the
 *  import was started from — Settings > BRAIN — sat
 *  silent for the whole copy. The import button there
 *  now carries the live figure itself, driven from
 *  state (the importing flag + the last percent) rather
 *  than a one-off setText, so a refreshModelUi that
 *  rebuilds the section mid-import redraws the progress
 *  instead of wiping it back to the invitation.
 *
 *  percent() is the worker callback's arithmetic: the
 *  share copied, clamped to 0..100, or -1 when the
 *  stream reports no total (no honest figure to show).
 *  label() is the button's whole text: the standing
 *  invitation when idle (any stale percent ignored),
 *  the short name with the figure while a copy runs,
 *  and the short name alone while it runs figureless.
 *  Pure and static: the host harness pins both. */
public final class ImportProgress {

    private ImportProgress() { }

    public static int percent(long done, long total) {
        if (total <= 0) return -1;
        long p = 100L * done / total;
        if (p > 100) p = 100;
        if (p < 0) p = 0;
        return (int) p;
    }

    public static String label(boolean importing, int percent,
                               String shortName) {
        if (!importing) return "Import trained model";
        if (percent >= 0) {
            return "Importing " + shortName + "… " + percent + "%";
        }
        return "Importing " + shortName + "…";
    }
}
