package com.ghosthands.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v3.22 (Everything Train R11 — share intake): the
 * share-sheet target declared in the manifest (exported,
 * exactly one filter: SEND + text/plain, dialog theme,
 * excluded from recents). NEW SURFACE, named plainly in
 * the manifest comment and the release notes: any app on
 * the phone can offer the user a share into Ghost Hands.
 *
 * Its blast radius is bounded by design. It shows what
 * arrived (the ShareIntake parse, rendered into platform
 * text views only — nothing here renders HTML), and it
 * offers exactly:
 *
 *  - Use as goal: the text rides a launch extra to
 *    MainActivity, which loads it into the goal field for
 *    review. The intake starts no run.
 *  - Watch this page (when the text carried a URL): a
 *    PageWatches record is written directly, baseline
 *    PENDING and unscheduled — creation needs no WebView
 *    precisely because the baseline is pending; the first
 *    completed check sets it (the R8 discipline). Then
 *    MainActivity opens on the Saved Goals screen.
 *  - Cancel: nothing stored, nothing loaded.
 *
 * The intake itself fetches nothing, runs nothing, and
 * schedules nothing. Shared text is untrusted: a goal made
 * from it waits in the field for the user's own Start
 * under the ordinary governor, and a watch made from it is
 * fetched only by the ordinary readonly check machinery.
 *
 * The UI is built in code, the app's way (the theme is the
 * manifest's dialog theme).
 */
public class ShareIntakeActivity extends Activity {

    // The skin's constants, duplicated (MainActivity's are
    // its own): near-black navy, the card fill, the text
    // colours.
    private static final int COL_BG = Color.rgb(4, 7, 15);
    private static final int COL_TEXT = Color.rgb(234, 246, 255);
    private static final int COL_MUTED = Color.rgb(143, 168, 191);

    private ShareIntake.Parsed parsed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildFrom(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        buildFrom(intent);
    }

    private void buildFrom(Intent intent) {
        String text = (intent == null) ? null
                : intent.getStringExtra(Intent.EXTRA_TEXT);
        parsed = ShareIntake.parse(text);
        if (parsed.kind == ShareIntake.EMPTY) {
            Toast.makeText(this, "Nothing usable in what was "
                    + "shared.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        buildUi();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics()
                .density);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COL_BG);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        scroll.addView(box);

        TextView head = new TextView(this);
        head.setText("Shared with Ghost Hands");
        head.setTextSize(16);
        head.setTextColor(COL_TEXT);
        box.addView(head);

        TextView body = new TextView(this);
        body.setText(parsed.goalText);
        body.setTextSize(14);
        body.setTextColor(COL_TEXT);
        body.setTextIsSelectable(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        box.addView(body, blp);

        if (parsed.url != null) {
            TextView urlLine = new TextView(this);
            urlLine.setText("Page: " + parsed.url);
            urlLine.setTextSize(12);
            urlLine.setTextColor(COL_MUTED);
            LinearLayout.LayoutParams ulp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            ulp.topMargin = dp(8);
            box.addView(urlLine, ulp);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        box.addView(row, rlp);

        Button goalBtn = new Button(this);
        goalBtn.setText("Use as goal");
        goalBtn.setAllCaps(false);
        goalBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { useAsGoal(); }
        });
        row.addView(goalBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (parsed.url != null) {
            Button watchBtn = new Button(this);
            watchBtn.setText("Watch this page");
            watchBtn.setAllCaps(false);
            watchBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    watchThisPage();
                }
            });
            LinearLayout.LayoutParams wlp =
                    new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1);
            wlp.leftMargin = dp(8);
            row.addView(watchBtn, wlp);
        }

        Button cancelBtn = new Button(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setAllCaps(false);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        clp.leftMargin = dp(8);
        row.addView(cancelBtn, clp);

        setContentView(scroll);
    }

    /** Prefill only: the goal waits in MainActivity's field
     *  for the user's review and their own Start. */
    private void useAsGoal() {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_PREFILL_GOAL,
                parsed.goalText);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
        finish();
    }

    /** Write the pending watch (the shared §3.3 path: the
     *  parse already validated the URL; the store is a pure
     *  prefs store), say what happens next in words, and
     *  land on the Saved Goals screen where the watch
     *  lives. */
    private void watchThisPage() {
        String url = parsed.url;
        SharedPreferences prefs =
                getSharedPreferences("ghosthands", MODE_PRIVATE);
        PageWatches store = PageWatches.parse(
                prefs.getString(PageWatches.PREF_PAGE_WATCHES,
                        null));
        if (store.find(url) != null) {
            Toast.makeText(this, "Already watching this page",
                    Toast.LENGTH_SHORT).show();
        } else {
            PageWatches.PageWatch w =
                    new PageWatches.PageWatch(url);
            // Baseline stays null (pending), schedule stays
            // NO_SCHEDULE: the template discipline, verbatim.
            store.put(w);
            prefs.edit().putString(
                    PageWatches.PREF_PAGE_WATCHES,
                    store.serialize()).apply();
            String host = SiteGrants.hostOf(url);
            Toast.makeText(this, "Watching "
                    + ((host != null && host.length() > 0)
                            ? host : url)
                    + " — its baseline is set on its first "
                    + "check. Set a check time in Saved Goals.",
                    Toast.LENGTH_LONG).show();
        }
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_OPEN_GOALS, true);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
        finish();
    }
}
