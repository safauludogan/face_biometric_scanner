package com.company.facelogin.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {

    private static final String PREFS_NAME     = "facelogin_session";
    private static final String KEY_USER_ID    = "user_id";
    private static final String KEY_FIRST_NAME = "first_name";
    private static final String KEY_LAST_NAME  = "last_name";
    private static final long   NO_SESSION     = -1L;

    private final SharedPreferences prefs;

    public SessionManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveSession(long userId, String firstName, String lastName) {
        prefs.edit()
                .putLong(KEY_USER_ID, userId)
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_LAST_NAME, lastName)
                .apply();
    }

    public long getLoggedInUserId() {
        return prefs.getLong(KEY_USER_ID, NO_SESSION);
    }

    public String getFirstName() {
        return prefs.getString(KEY_FIRST_NAME, "");
    }

    public String getLastName() {
        return prefs.getString(KEY_LAST_NAME, "");
    }

    public boolean isLoggedIn() {
        return getLoggedInUserId() != NO_SESSION;
    }

    public void clearSession() {
        prefs.edit().clear().apply();
    }
}
