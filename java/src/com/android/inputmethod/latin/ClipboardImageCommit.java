/*
 * SPDX-License-Identifier: Apache-2.0
 * Commits a locally-saved clipboard image into the focused field using the Commit Content API,
 * the same mechanism Gboard uses. The image is served to the target app through our FileProvider
 * with a one-shot read grant. Returns false (so the caller can toast) when the field does not
 * advertise support for the image's MIME type.
 */
package com.android.inputmethod.latin;

import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.core.content.FileProvider;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import java.io.File;

final class ClipboardImageCommit {
    private static final String AUTHORITY = "com.android.inputmethod.latin.clipimages";

    private ClipboardImageCommit() { }

    static boolean commit(final Context context, final InputConnection ic, final EditorInfo editorInfo,
            final String imagePath, final String mimeType) {
        if (ic == null || editorInfo == null || imagePath == null) return false;
        final File file = new File(imagePath);
        if (!file.exists()) return false;

        final String[] accepted = EditorInfoCompat.getContentMimeTypes(editorInfo);
        boolean supported = false;
        for (final String a : accepted) {
            if (ClipDescription.compareMimeTypes(mimeType, a)) { supported = true; break; }
        }
        if (!supported) return false;

        final Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, AUTHORITY, file);
        } catch (IllegalArgumentException e) {
            return false;
        }
        final InputContentInfoCompat content = new InputContentInfoCompat(
                uri, new ClipDescription("clipboard image", new String[] { mimeType }), null);
        return InputConnectionCompat.commitContent(ic, editorInfo, content,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null);
    }
}
