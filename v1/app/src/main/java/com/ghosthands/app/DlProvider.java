package com.ghosthands.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * v3.16 (Everything Train R8): the downloads store's door to
 * the rest of the phone.
 *
 * Opening and sharing a stored file hands another app a
 * content:// URI (a file:// URI is refused by the platform
 * at this target SDK), and a content URI needs a provider.
 * The framework-only rule rules out androidx's FileProvider,
 * so this is the smallest honest version of one:
 *
 *  - It serves the app's private downloads directory
 *    (Downloads.DIR_NAME), one file per URI, by bare name.
 *  - v3.22 (R11): it ALSO serves the app's private reports
 *    directory (RunReport.DIR_NAME), under a two-segment
 *    path — …/reports/<name> — so a generated run report
 *    can ride the same share pattern. The widening is named
 *    here and in the manifest comment, in the plain-speaking
 *    tradition: still app-private directories only, still
 *    bare names only, still the canonical-path guard against
 *    the CHOSEN root.
 *  - It is NOT exported; readers reach a file only through
 *    a per-URI read grant the app itself hands out with an
 *    open/share intent (grantUriPermissions).
 *  - It is read-only: insert/update/delete answer with the
 *    platform's refusal, and openFile honours mode "r" only.
 *  - The name is judged before it is ever joined to the
 *    directory: anything carrying a separator or a parent
 *    reference names nothing, and the resolved file's
 *    canonical path must sit directly inside its root.
 *
 * New manifest surface, called out plainly in the release
 * notes (like v3.14's service): a provider declaration. It
 * adds NO uses-permission — the four above it in the
 * manifest are still the whole set.
 */
public class DlProvider extends ContentProvider {

    public static final String AUTHORITY =
            "com.ghosthands.app.files";

    /** The two served roots (the path grammar's verdicts). */
    public static final int ROOT_DOWNLOADS = 0;
    public static final int ROOT_REPORTS = 1;

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * The URI grammar, pure so the harness can pin the whole
     * traversal table without a device: a ONE-segment path
     * names a downloads file by bare name; a TWO-segment
     * path whose first segment is "reports" names a reports
     * file by bare name; every other shape names nothing
     * (-1). A name is then judged by bareName before it is
     * joined to its root.
     */
    public static int rootForSegments(List<String> segments) {
        if (segments == null) return -1;
        if (segments.size() == 1) {
            return bareName(segments.get(0)) ? ROOT_DOWNLOADS : -1;
        }
        if (segments.size() == 2
                && RunReport.DIR_NAME.equals(segments.get(0))) {
            return bareName(segments.get(1)) ? ROOT_REPORTS : -1;
        }
        return -1;
    }

    /** A bare store name: non-empty, no separator, no parent
     *  reference — the only shape either root serves. */
    public static boolean bareName(String name) {
        return name != null && name.length() > 0
                && !name.contains("/") && !name.contains("\\")
                && !name.contains("..");
    }

    /** The root directory a verdict names, or null before
     *  the app has one. */
    private File dirFor(int root) {
        if (getContext() == null) return null;
        return new File(getContext().getFilesDir(),
                root == ROOT_REPORTS ? RunReport.DIR_NAME
                        : Downloads.DIR_NAME);
    }

    /** The file a URI names, or null when the path shape is
     *  not one of the two roots', the name is not a bare
     *  store name, or the file is not there. The
     *  canonical-path check is the seatbelt: a name that
     *  resolves anywhere but directly inside its root names
     *  nothing here, however it was spelled. */
    private File fileFor(Uri uri) {
        if (uri == null) return null;
        List<String> segments = uri.getPathSegments();
        int root = rootForSegments(segments);
        if (root < 0) return null;
        String name = segments.get(segments.size() - 1);
        File dir = dirFor(root);
        if (dir == null) return null;
        try {
            File f = new File(dir, name);
            String canonDir = dir.getCanonicalPath();
            String canonFile = f.getCanonicalPath();
            if (!canonFile.equals(canonDir + File.separator + name)) {
                return null;
            }
            return f.isFile() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection,
                        String selection, String[] selectionArgs,
                        String sortOrder) {
        File f = fileFor(uri);
        if (f == null) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{f.getName(),
                Long.valueOf(f.length())});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String name = (uri == null) ? null
                : uri.getLastPathSegment();
        return Downloads.mimeForName(name == null ? "" : name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)
                && !"rt".equals(mode)) {
            throw new FileNotFoundException(
                    "the file store is read-only");
        }
        File f = fileFor(uri);
        if (f == null) {
            throw new FileNotFoundException(
                    "no such file in the file store");
        }
        return ParcelFileDescriptor.open(f,
                ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection,
                      String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values,
                      String selection, String[] selectionArgs) {
        return 0;
    }
}
