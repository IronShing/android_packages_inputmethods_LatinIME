/*
 * SPDX-License-Identifier: Apache-2.0
 * Clipboard history + latest-clip suggestion for LatinIME. An IME is allowed to read the
 * clipboard while it is the active input method, so we keep a short rolling history and expose
 * the newest clip as a one-tap paste chip on the suggestion strip (this is also how a copied
 * one-time code shows up for pasting).
 *
 * Images are supported too (Gboard-style paste): when a clip carries an image/* URI we copy the
 * bytes into our own files dir so we can later hand a granted URI to the target app via a
 * FileProvider. See {@link ClipboardImageCommit}.
 */
package com.android.inputmethod.latin;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ClipboardHistoryManager
        implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardHistory";
    public static final int MAX_ENTRIES = 12;
    // A clip older than this is not offered as a paste chip (history still keeps it).
    private static final long CHIP_FRESH_MS = 10 * 60 * 1000L;
    private static final int MAX_LEN = 4000;
    // Don't copy giant images into our sandbox.
    private static final long MAX_IMAGE_BYTES = 16L * 1024 * 1024;
    private static final String IMAGE_DIR = "clipimg";
    private static final String PREFS = "clipboard_history";
    private static final String KEY_CONSUMED = "consumed_time";
    private static final String KEY_TEXT = "t";
    private static final String KEY_TIME = "ts";
    private static final String KEY_IMG = "img";   // absolute image path, or absent for text
    private static final String KEY_MIME = "mime";

    public static final class Entry {
        public final CharSequence text;   // null for an image entry
        public final long timeMs;
        public final String imagePath;    // absolute path in our files dir, null for text
        public final String mimeType;     // e.g. "image/png", null for text

        Entry(CharSequence text, long timeMs) {
            this(text, timeMs, null, null);
        }
        Entry(CharSequence text, long timeMs, String imagePath, String mimeType) {
            this.text = text;
            this.timeMs = timeMs;
            this.imagePath = imagePath;
            this.mimeType = mimeType;
        }
        public boolean isImage() { return imagePath != null; }

        /** A stable key used for de-dup and lookup: the text, or the image path. */
        String id() { return isImage() ? imagePath : (text == null ? "" : text.toString()); }
    }

    private final Context mContext;
    private final ClipboardManager mClipboardManager;
    private final ArrayList<Entry> mHistory = new ArrayList<>();
    private long mConsumedTime = 0;   // newest clip time the user already handled -> no chip
    // Why the last image clip could not be captured (shown in the history panel), or null.
    private volatile String mLastImageError;
    // Source uri + clip timestamp of the last image we copied: fetchCurrent() runs on every
    // keyboard open, and without this each open re-captured the same clip as a new file.
    private String mLastImageSource;

    public ClipboardHistoryManager(final Context context) {
        mContext = context.getApplicationContext();
        mClipboardManager = mContext.getSystemService(ClipboardManager.class);
        load();
    }

    public void onCreate() {
        if (mClipboardManager != null) {
            mClipboardManager.addPrimaryClipChangedListener(this);
            fetchCurrent();
        }
    }

    public void onDestroy() {
        if (mClipboardManager != null) {
            mClipboardManager.removePrimaryClipChangedListener(this);
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        fetchCurrent();
    }

    /** Re-read the current clip. Call when the keyboard becomes visible: an IME may only read the
     *  clipboard while focused, so an image copied with no keyboard up is caught only here. */
    public void refresh() {
        if (mClipboardManager != null) fetchCurrent();
    }

    private void fetchCurrent() {
        if (mClipboardManager == null || !mClipboardManager.hasPrimaryClip()) return;
        final ClipData clip = mClipboardManager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        final ClipDescription desc = clip.getDescription();
        final ClipData.Item item = clip.getItemAt(0);
        Log.i(TAG, "clip: mimes=" + (desc == null ? "null" : java.util.Arrays.toString(desc.filterMimeTypes("*/*")))
                + " uri=" + item.getUri() + " hasText=" + (item.getText() != null));

        // Image clip: copy the bytes so we can re-serve them via our FileProvider.
        if (desc != null && item.getUri() != null && hasImageMime(desc)) {
            final String source = item.getUri() + "@" + desc.getTimestamp();
            if (source.equals(mLastImageSource)) return;
            mLastImageSource = source;
            captureImageAsync(item.getUri(), pickImageMime(desc));
            return;
        }

        final CharSequence cs = item.coerceToText(mContext);
        if (TextUtils.isEmpty(cs)) return;
        CharSequence text = cs.length() > MAX_LEN ? cs.subSequence(0, MAX_LEN) : cs;
        push(new Entry(text, System.currentTimeMillis()));
    }

    private static boolean hasImageMime(final ClipDescription desc) {
        for (int i = 0; i < desc.getMimeTypeCount(); i++) {
            if (desc.getMimeType(i).startsWith("image/")) return true;
        }
        return false;
    }

    private static String pickImageMime(final ClipDescription desc) {
        for (int i = 0; i < desc.getMimeTypeCount(); i++) {
            final String m = desc.getMimeType(i);
            if (m.startsWith("image/")) return m;
        }
        return "image/*";
    }

    private void captureImageAsync(final Uri uri, final String mime) {
        // Reading the clip URI + file I/O off the UI thread; push back on completion.
        new Thread(() -> {
            final File f = copyImage(uri, mime);
            if (f != null) {
                Log.i(TAG, "captured image " + f + " (" + f.length() + " B, " + mime + ")");
                mLastImageError = null;
                push(new Entry(null, System.currentTimeMillis(), f.getAbsolutePath(), mime));
            } else if (mLastImageError == null) {
                mLastImageError = "empty or oversized image (" + mime + ")";
            }
        }, "clip-image-copy").start();
    }

    private File copyImage(final Uri uri, final String mime) {
        InputStream in = null;
        OutputStream out = null;
        File dst = null;
        try {
            final File dir = imagesDir();
            if (!dir.exists() && !dir.mkdirs()) return null;
            dst = new File(dir, System.currentTimeMillis() + extForMime(mime));
            in = mContext.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            out = new FileOutputStream(dst);
            final byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_IMAGE_BYTES) { closeQuietly(out); dst.delete(); return null; }
                out.write(buf, 0, n);
            }
            out.flush();
            return dst;
        } catch (Exception e) {
            // SecurityException if the clip URI is no longer grantable to us, IOException, etc.
            Log.w(TAG, "image copy failed for " + uri, e);
            mLastImageError = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (dst != null) dst.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static String extForMime(final String mime) {
        if (mime == null) return ".img";
        switch (mime) {
            case "image/png":  return ".png";
            case "image/jpeg": return ".jpg";
            case "image/webp": return ".webp";
            case "image/gif":  return ".gif";
            default: return ".img";
        }
    }

    private File imagesDir() { return new File(mContext.getFilesDir(), IMAGE_DIR); }

    private static void closeQuietly(final java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) { }
    }

    private synchronized void push(final Entry entry) {
        final String id = entry.id();
        for (Iterator<Entry> it = mHistory.iterator(); it.hasNext(); ) {
            final Entry old = it.next();
            if (old.id().equals(id)) {
                it.remove();
                if (old.isImage() && entry.isImage() && !old.imagePath.equals(entry.imagePath)) {
                    new File(old.imagePath).delete();
                }
                break;
            }
        }
        mHistory.add(0, entry);
        while (mHistory.size() > MAX_ENTRIES) {
            final Entry dropped = mHistory.remove(mHistory.size() - 1);
            if (dropped.isImage()) new File(dropped.imagePath).delete();
        }
        save();
    }

    /** The freshest clip to offer as a paste chip, or null (empty/old/already consumed). */
    public synchronized Entry suggestionEntry() {
        if (mHistory.isEmpty()) return null;
        final Entry e = mHistory.get(0);
        if (e.timeMs <= mConsumedTime) return null;
        if (System.currentTimeMillis() - e.timeMs > CHIP_FRESH_MS) return null;
        if (e.isImage() && !new File(e.imagePath).exists()) return null;
        return e;
    }

    /** Mark the current newest clip as handled so the chip stops showing. */
    public synchronized void consumeSuggestion() {
        if (mHistory.isEmpty()) return;
        mConsumedTime = mHistory.get(0).timeMs;
        // Persisted: the keyboard process is restarted often enough that an in-memory flag
        // brought a dismissed chip straight back.
        prefs().edit().putLong(KEY_CONSUMED, mConsumedTime).apply();
    }

    /** How much of a clip's tail we compare against the field to see if it was pasted already. */
    public static final int MATCH_TAIL_CHARS = 120;

    /** True when the text before the cursor already ends with (the tail of) this clip. */
    public static boolean alreadyInField(final Entry clip, final CharSequence beforeCursor) {
        if (clip == null || clip.text == null || beforeCursor == null) return false;
        String needle = clip.text.toString().trim();
        if (needle.isEmpty()) return false;
        if (needle.length() > MATCH_TAIL_CHARS) {
            needle = needle.substring(needle.length() - MATCH_TAIL_CHARS);
        }
        final String hay = beforeCursor.toString().trim();
        return hay.endsWith(needle);
    }

    public synchronized List<Entry> getHistory() { return new ArrayList<>(mHistory); }

    /** Reason the most recent image clip was not captured, or null if it was. */
    public String lastImageError() { return mLastImageError; }

    /** Remove an entry by its stable id (text or image path). */
    public synchronized void remove(final Entry entry) {
        if (entry == null) return;
        final String id = entry.id();
        for (Iterator<Entry> it = mHistory.iterator(); it.hasNext(); ) {
            final Entry e = it.next();
            if (e.id().equals(id)) {
                it.remove();
                if (e.isImage()) new File(e.imagePath).delete();
                break;
            }
        }
        save();
    }

    public synchronized void clearAll() {
        for (final Entry e : mHistory) {
            if (e.isImage()) new File(e.imagePath).delete();
        }
        mHistory.clear();
        save();
    }

    // ---- lightweight persistence (survives keyboard process restarts) ----
    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void save() {
        final SharedPreferences.Editor e = prefs().edit().clear();
        for (int i = 0; i < mHistory.size(); i++) {
            final Entry entry = mHistory.get(i);
            e.putLong(KEY_TIME + i, entry.timeMs);
            if (entry.isImage()) {
                e.putString(KEY_IMG + i, entry.imagePath);
                e.putString(KEY_MIME + i, entry.mimeType);
            } else {
                e.putString(KEY_TEXT + i, entry.text.toString());
            }
        }
        e.putLong(KEY_CONSUMED, mConsumedTime);
        e.putInt("n", mHistory.size()).apply();
    }

    private void load() {
        final SharedPreferences p = prefs();
        mConsumedTime = p.getLong(KEY_CONSUMED, 0);
        final int n = p.getInt("n", 0);
        for (int i = 0; i < n && i < MAX_ENTRIES; i++) {
            final long time = p.getLong(KEY_TIME + i, 0);
            final String img = p.getString(KEY_IMG + i, null);
            if (img != null) {
                if (!new File(img).exists()) continue;   // file pruned externally
                mHistory.add(new Entry(null, time, img, p.getString(KEY_MIME + i, "image/*")));
            } else {
                final String t = p.getString(KEY_TEXT + i, null);
                if (t == null) continue;
                mHistory.add(new Entry(t, time));
            }
        }
    }
}
