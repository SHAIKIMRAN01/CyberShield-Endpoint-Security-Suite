package com.cybershield.ui.controllers;

/** A screen that needs the unlocked vault and has a Lock Vault button. */
public interface LockableScreen {

    /** Called after the user locks the vault from this screen. */
    void setOnLocked(Runnable onLocked);

    /**
     * Called when the screen is closed: by locking (also automatic), leaving it,
     * or logging out. Drops decrypted data, stops playback, closes extra windows.
     * May be called more than once.
     */
    default void dispose() {
    }

}
