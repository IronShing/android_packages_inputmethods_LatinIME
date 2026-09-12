/*
 * SPDX-License-Identifier: Apache-2.0
 * A small popup listing recent clipboard entries; tap one to paste. Shown on long-press of the
 * clipboard paste chip. Uses the suggestion strip's window token so it is valid inside the IME.
 * Image entries are shown as downscaled thumbnails and pasted via the Commit Content API.
 * Entries are laid out in a 4-column grid (each cell 1/4 width), newest first.
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
    private static final int COLS = 4;   // clipboard entries per grid row
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
        LinearLayout gridRow = null;
        int colIdx = 0;
        for (final ClipboardHistoryManager.Entry e : history) {
            final View content = e.isImage() ? imageContent(ctx, e) : textContent(ctx, e);
            if (content == null) continue;
            if (colIdx == 0) {
                gridRow = new LinearLayout(ctx);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                gridRow.setWeightSum(COLS);   // keeps each cell 1/4 width even on a partial row
                col.addView(gridRow);
            }
            final int gap = dp(ctx, 3);
            final LinearLayout.LayoutParams clp =
                    new LinearLayout.LayoutParams(0, dp(ctx, 84), 1f);
            clp.setMargins(gap, gap, gap, gap);
            final LinearLayout cell = new LinearLayout(ctx);
            cell.setLayoutParams(clp);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundColor(0xFF303134);
            cell.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
            cell.setClickable(true);
            cell.setOnClickListener(v -> { dismiss(); mOnPaste.paste(e); });
            cell.addView(content);
            gridRow.addView(cell);
            rows++;
            colIdx = (colIdx + 1) % COLS;
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

    private View textContent(final Context ctx, final ClipboardHistoryManager.Entry e) {
        final String oneLine = e.text.toString().replaceAll("\\s+", " ").trim();
        if (TextUtils.isEmpty(oneLine)) return null;
        final TextView tv = new TextView(ctx);
        tv.setText(oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);   // smaller: cells are ~1/4 width
        tv.setMaxLines(4);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    private View imageContent(final Context ctx, final ClipboardHistoryManager.Entry e) {
        final ImageView iv = new ImageView(ctx);
        final int maxH = dp(ctx, 72);
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
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
