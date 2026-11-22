package com.cybershield.security;

import com.cybershield.models.User;

/** Who is signed in. It holds no secrets: the vault key lives in EncryptionService while the vault is open. */
public final class SessionManager {

    private static volatile User currentUser;

    // The login_history row of this sign-in, so signing out can record its time. 0 when there is none.
    private static volatile long historyId;

    private SessionManager() {
    }

    public static void login(User user, long historyRowId) {

        currentUser = user;

        historyId = historyRowId;

    }

    public static void login(User user) {

        login(user, 0);

    }

    public static void logout() {

        currentUser = null;

        historyId = 0;

    }

    public static User getCurrentUser() {

        return currentUser;

    }

    public static boolean isLoggedIn() {

        return currentUser != null;

    }

    /** The signed-in account's id, or -1 when nobody is signed in. */
    public static int currentUserId() {

        User user = currentUser;

        return user == null ? -1 : user.getId();

    }

    public static long historyId() {

        return historyId;

    }

}
