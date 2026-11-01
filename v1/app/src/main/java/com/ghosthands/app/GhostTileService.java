package com.ghosthands.app;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * v3.22 (Everything Train R11 — the QS tile): the "Ghost
 * goal" Quick Settings tile. NEW SURFACE, named plainly in
 * the manifest comment: exported because the platform
 * requires it, protected by BIND_QUICK_SETTINGS_TILE (only
 * the system may bind it — the service's own protection,
 * not a uses-permission; the standing four are unchanged).
 *
 * The tile is a SHORTCUT, not a status surface, and it is
 * kept honest and minimal: its state is ALWAYS the same
 * label, subtitle, and STATE_ACTIVE, because it can know
 * neither of the things a status tile would claim —
 * background clipboard reads are platform-blocked
 * (Android 10+), and run state lives in the Activity. A
 * tile that pretended to show either would be lying; the
 * live-status surface is R12's widget, not this tile.
 *
 * The tap: unlockAndRun (a locked phone asks for the
 * user's unlock first — the tile never bypasses the
 * keyguard), then MainActivity comes forward carrying the
 * clipboard extra; MainActivity, foreground where
 * clipboard reads are legal, reads the clip and prefills
 * the goal for review. Nothing auto-starts.
 *
 * The tile exists only when the user adds it from the
 * Quick Settings edit panel — the app cannot add, promote,
 * or default it, and nothing in the app nags about it.
 * Its long-press is the platform's (app info), untouched.
 */
public class GhostTileService extends TileService {

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    /** The tile's one honest state, set whenever the panel
     *  listens (ACTIVE_TILE meta-data makes that the moment
     *  it is set, not only when the service binds). */
    private void updateTile() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Ghost goal");
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle("Goal from clipboard");
        }
        t.setState(Tile.STATE_ACTIVE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        final Intent open = new Intent(this, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_CLIPBOARD_GOAL, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        unlockAndRun(new Runnable() {
            @Override public void run() {
                startActivityAndCollapse(open);
            }
        });
    }
}
