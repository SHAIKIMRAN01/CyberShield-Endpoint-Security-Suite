package com.cybershield.vault;

import com.cybershield.dao.SettingsDAO;
import com.cybershield.logger.AppLogger;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;
import javafx.util.Duration;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Locks the vault after a stretch without mouse or keyboard use. The time
 * comes from the settings table (auto_lock_minutes, 5 by default). While
 * something long-running needs the vault, such as a file job or a playing
 * video, it stays open. All methods run on the JavaFX thread.
 */
public final class AutoLock {

    /** Optional system property with the timeout in seconds (used by tests). */
    public static final String SECONDS_PROPERTY = "cybershield.autolock.seconds";

    private static final AutoLock INSTANCE = new AutoLock();

    private final VaultService vaultService = new VaultService();

    private final ReadOnlyIntegerWrapper secondsLeft = new ReadOnlyIntegerWrapper(-1);

    private final Set<BooleanSupplier> keepOpenChecks = new LinkedHashSet<>();

    private final Set<Scene> watched = Collections.newSetFromMap(new WeakHashMap<>());

    private final EventHandler<InputEvent> activity = event -> touch();

    private Timeline timer;

    private long timeoutMillis;

    private long lastActivity = System.currentTimeMillis();

    private Runnable onLocked = () -> { };

    private AutoLock() {
    }

    public static AutoLock getInstance() {
        return INSTANCE;
    }

    /** The configured timeout: the test property if set, otherwise the settings table. */
    public static long configuredTimeoutMillis() {

        String seconds = System.getProperty(SECONDS_PROPERTY);

        if (seconds != null) {

            try {
                return Math.max(1, Long.parseLong(seconds.trim())) * 1000;
            } catch (NumberFormatException e) {
                AppLogger.warn("Ignoring " + SECONDS_PROPERTY + "=" + seconds);
            }

        }

        return new SettingsDAO().autoLockMinutes() * 60_000L;

    }

    /** Starts, or restarts, the countdown. onLocked runs after an automatic lock. */
    public void start(long timeoutMillis, Runnable onLocked) {

        stop();

        this.timeoutMillis = timeoutMillis;

        this.onLocked = onLocked;

        touch();

        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));

        timer.setCycleCount(Animation.INDEFINITE);

        timer.play();

    }

    /** Uses a new timeout from now on, for example after Settings changed it. The countdown starts again. */
    public void setTimeoutMillis(long timeoutMillis) {

        this.timeoutMillis = timeoutMillis;

        touch();

    }

    public void stop() {

        if (timer != null) {
            timer.stop();
            timer = null;
        }

        secondsLeft.set(-1);

    }

    /** Counts mouse, keyboard, scroll and touch use in this window as activity. */
    public void watch(Scene scene) {

        if (scene != null && watched.add(scene)) {
            scene.addEventFilter(InputEvent.ANY, activity);
        }

    }

    public void touch() {
        lastActivity = System.currentTimeMillis();
    }

    /** Keeps the vault open for as long as the check returns true. Adding the same check twice has no effect. */
    public void keepOpenWhile(BooleanSupplier check) {
        keepOpenChecks.add(check);
    }

    public void stopKeepingOpen(BooleanSupplier check) {
        keepOpenChecks.remove(check);
    }

    /** Seconds until the vault locks itself, or -1 while it is locked. */
    public ReadOnlyIntegerProperty secondsLeftProperty() {
        return secondsLeft.getReadOnlyProperty();
    }

    private void tick() {

        if (!vaultService.isUnlocked()) {
            touch();
            secondsLeft.set(-1);
            return;
        }

        for (BooleanSupplier check : keepOpenChecks) {

            if (check.getAsBoolean()) {
                touch();
                break;
            }

        }

        long left = timeoutMillis - (System.currentTimeMillis() - lastActivity);

        if (left > 0) {
            secondsLeft.set((int) ((left + 999) / 1000));
            return;
        }

        vaultService.lockVault(true);

        secondsLeft.set(-1);

        AppLogger.info("Vault locked after " + timeoutMillis / 1000 + " seconds without activity.");

        onLocked.run();

    }

}
