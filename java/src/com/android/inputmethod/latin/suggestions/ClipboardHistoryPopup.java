/*
 * SPDX-License-Identifier: Apache-2.0
 * A small popup listing recent clipboard entries; tap one to paste. Shown on long-press of the
 * clipboard paste chip. Uses the suggestion strip's window token so it is valid inside the IME.
 * Image entries are shown as downscaled thumbnails and pasted via the Commit Content API.
 */
package com.android.inputmethod.latin.suggestions;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.inputmethod.latin.ClipboardHistoryManager;
import com.android.inputmethod.latin.R;

import java.util.List;

final class ClipboardHistoryPopup {
    interface OnPaste { void paste(ClipboardHistoryManager.Entry entry); }

    private final View mAnchor;
    private final ClipboardHistoryManager mManager;
    private final OnPaste mOnPaste;
    private PopupWindow mPopup;

    ClipboardHistoryPopup(final View anchor, final ClipboardHistoryManager manager,
            final OnPaste onPaste) {
        mAnchor = anchor;
        mManager = manager;
        mOnPaste = onPaste;
    }

    boolean show() {
        final List<ClipboardHistoryManager.Entry> history = mManager.getHistory();
        final Context ctx = mAnchor.getContext();
        final int pad = dp(ctx, 12);

        final LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xF0202124);
        col.setPadding(pad, pad, pad, pad);

        final TextView header = new TextView(ctx);
        header.setText(R.string.clipboard_history_title);
        header.setTextColor(0xFFBBBBBB);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.setPadding(dp(ctx, 4), 0, 0, dp(ctx, 6));
        col.addView(header);

        int rows = 0;
        final String imgErr = mManager.lastImageError();
        if (imgErr != null) {
            // Surface capture failures instead of silently showing nothing for a copied image.
            final TextView err = new TextView(ctx);
            err.setText(ctx.getString(R.string.clipboard_image_failed, imgErr));
            err.setTextColor(0xFFE0A060);
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            err.setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6));
            col.addView(err);
        }
        for (final ClipboardHistoryManager.Entry e : history) {
            final View row = e.isImage() ? imageRow(ctx, e) : textRow(ctx, e);
            if (row != null) { col.addView(row); rows++; }
        }
        if (rows == 0) {
            final TextView empty = new TextView(ctx);
            empty.setText(R.string.clipboard_empty);
            empty.setTextColor(0xFF888888);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            empty.setPadding(dp(ctx, 6), dp(ctx, 12), dp(ctx, 6), dp(ctx, 12));
            col.addView(empty);
        }

        final Button clear = new Button(ctx);
        clear.setText(R.string.clipboard_clear);
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> {
            mManager.clearAll();
            dismiss();
        });
        col.addView(clear);

        final ScrollView scroll = new ScrollView(ctx);
        scroll.addView(col);

        final int width = Math.max(mAnchor.getWidth(), dp(ctx, 220));
        // Cap the height so a long history never grows up behind the status bar (was cropping the
        // header + newest row). Measure the content and clamp to the space above the keyboard,
        // minus the status bar inset; the ScrollView scrolls the rest.
        final android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        final int liftPx = mAnchor.getHeight() + dp(ctx, 48);
        int statusBar = 0;
        final int sbId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbId > 0) statusBar = ctx.getResources().getDimensionPixelSize(sbId);
        final int available = dm.heightPixels - liftPx - statusBar - dp(ctx, 8);
        final int maxH = Math.max(dp(ctx, 160), Math.min(available, (int) (dm.heightPixels * 0.55f)));
        scroll.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        final int height = Math.min(scroll.getMeasuredHeight(), maxH);

        mPopup = new PopupWindow(scroll, width, height, true);
        mPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mPopup.setClippingEnabled(true);
        try {
            mPopup.showAtLocation(mAnchor, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, liftPx);
        } catch (Exception ex) {
            return false;
        }
        return true;
    }

    private View textRow(final Context ctx, final ClipboardHistoryManager.Entry e) {
        final String oneLine = e.text.toString().replaceAll("\\s+", " ").trim();
        if (TextUtils.isEmpty(oneLine)) return null;
        final TextView row = new TextView(ctx);
        row.setText(oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine);
        row.setTextColor(Color.WHITE);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setMaxLines(2);
        row.setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10));
        row.setClickable(true);
        row.setOnClickListener(v -> { dismiss(); mOnPaste.paste(e); });
        return row;
    }

    private View imageRow(final Context ctx, final ClipboardHistoryManager.Entry e) {
        final ImageView iv = new ImageView(ctx);
        final int maxH = dp(ctx, 96);
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(e.imagePath, bounds);
            final BitmapFactory.Options opt = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outHeight / sample > maxH * 2) sample *= 2;
            opt.inSampleSize = sample;
            iv.setImageBitmap(BitmapFactory.decodeFile(e.imagePath, opt));
        } catch (Throwable t) {
            return null;
        }
        if (iv.getDrawable() == null) return null;
        iv.setAdjustViewBounds(true);
        iv.setMaxHeight(maxH);
        iv.setScaleType(ImageView.ScaleType.FIT_START);
        final int m = dp(ctx, 6);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(m, dp(ctx, 8), m, dp(ctx, 8));
        iv.setLayoutParams(lp);
        iv.setClickable(true);
        iv.setOnClickListener(v -> { dismiss(); mOnPaste.paste(e); });
        return iv;
    }

    private void dismiss() {
        if (mPopup != null && mPopup.isShowing()) {
            mPopup.dismiss();
        }
        mPopup = null;
    }

    private static int dp(final Context c, final int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}
