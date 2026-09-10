/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.inputmethod.latin;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * "Your email addresses" chips for email fields, where dictionary suggestions are off.
 *
 * Sources, in order: the addresses typed into the keyboard's own setting, the names of the
 * device accounts that look like addresses (visible to us because we hold READ_CONTACTS and
 * those account types manage contacts), and the "Me" profile's email rows. The last two are
 * skipped silently when the Contacts permission has not been granted.
 */
public final class EmailSuggestions {
    private static final String TAG = "EmailSuggestions";
    public static final String PREF_KEY = "pref_email_addresses";
    private static final int MAX_SHOWN = 6;

    private final Context mContext;
    private volatile List<String> mAddresses = new ArrayList<>();

    public EmailSuggestions(final Context context) {
        mContext = context;
    }

    /** Re-reads every source. Cheap; called from a background thread when an email field starts. */
    public void refresh() {
        final LinkedHashSet<String> out = new LinkedHashSet<>();
        final SharedPreferences prefs =
                android.preference.PreferenceManager.getDefaultSharedPreferences(mContext);
        final String manual = prefs.getString(PREF_KEY, "");
        if (!TextUtils.isEmpty(manual)) {
            for (final String s : manual.split("[\\s,;]+")) {
                if (s.indexOf('@') > 0) out.add(s);
            }
        }
        try {
            for (final Account a : AccountManager.get(mContext).getAccounts()) {
                if (a.name != null && a.name.indexOf('@') > 0) out.add(a.name);
            }
        } catch (Exception e) {
            Log.w(TAG, "accounts unavailable: " + e);
        }
        try {
            final Uri uri = Uri.withAppendedPath(ContactsContract.Profile.CONTENT_URI,
                    ContactsContract.Contacts.Data.CONTENT_DIRECTORY);
            try (Cursor c = mContext.getContentResolver().query(uri,
                    new String[] { ContactsContract.CommonDataKinds.Email.ADDRESS },
                    ContactsContract.Data.MIMETYPE + "=?",
                    new String[] { ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE },
                    null)) {
                while (c != null && c.moveToNext()) {
                    final String s = c.getString(0);
                    if (s != null && s.indexOf('@') > 0) out.add(s);
                }
            }
        } catch (Exception e) {
            // No Contacts permission (or no profile): accounts + manual list still work.
        }
        mAddresses = new ArrayList<>(out);
    }

    /** The word-ish token the cursor is on: whatever follows the last separator. */
    public static String tokenBeforeCursor(final CharSequence before) {
        if (before == null) return "";
        int i = before.length();
        while (i > 0) {
            final char ch = before.charAt(i - 1);
            if (Character.isWhitespace(ch) || ch == ',' || ch == ';' || ch == '<' || ch == '(') {
                break;
            }
            i--;
        }
        return before.subSequence(i, before.length()).toString();
    }

    /**
     * Addresses that start with {@code token} (case-insensitive), the token itself excluded so
     * a fully typed address gets no chip. An empty token lists everything.
     */
    public SuggestedWords match(final String token) {
        final List<String> addresses = mAddresses;
        if (addresses.isEmpty()) return null;
        final String prefix = token.toLowerCase(Locale.ROOT);
        final ArrayList<SuggestedWordInfo> infos = new ArrayList<>();
        for (final String address : addresses) {
            final String lower = address.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(prefix) || lower.equals(prefix)) continue;
            infos.add(new SuggestedWordInfo(address, "" /* prevWordsContext */,
                    SuggestedWordInfo.MAX_SCORE - infos.size(), SuggestedWordInfo.KIND_HARDCODED,
                    Dictionary.DICTIONARY_HARDCODED, SuggestedWordInfo.NOT_AN_INDEX,
                    SuggestedWordInfo.NOT_A_CONFIDENCE));
            if (infos.size() >= MAX_SHOWN) break;
        }
        if (infos.isEmpty()) return null;
        return new SuggestedWords(infos, null /* rawSuggestions */, null /* typedWord */,
                false /* typedWordValid */, false /* willAutoCorrect */,
                false /* isObsoleteSuggestions */, SuggestedWords.INPUT_STYLE_NONE,
                SuggestedWords.NOT_A_SEQUENCE_NUMBER);
    }
}
