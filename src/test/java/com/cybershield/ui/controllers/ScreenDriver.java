package com.cybershield.ui.controllers;

import com.cybershield.privacy.TraceCleaner;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Lets the screenshot test (in com.cybershield.ui) use the screens' test hooks, which are
 * package-private here: a picture instead of the open dialog, made-up Windows folders, and so on.
 */
public final class ScreenDriver {

    private ScreenDriver() {
    }

    public static void stegoCover(DashboardController dashboard, Path picture) {
        ((SteganographyController) dashboard.currentScreen()).useCover(picture);
    }

    public static void stegoHideTo(DashboardController dashboard, Path target) {
        ((SteganographyController) dashboard.currentScreen()).hideTo(target);
    }

    public static void privacyPhotos(DashboardController dashboard, List<Path> photos) {
        ((PrivacyController) dashboard.currentScreen()).addPhotos(photos);
    }

    public static void privacyTraces(DashboardController dashboard, TraceCleaner cleaner) {
        PrivacyController privacy = (PrivacyController) dashboard.currentScreen();
        privacy.useTraceCleaner(cleaner);
        privacy.scanTraces();
    }

    public static void reportDays(DashboardController dashboard, LocalDate from, LocalDate to) {
        ((ReportsController) dashboard.currentScreen()).chooseDays(from, to);
    }

    public static void securityScan(DashboardController dashboard, List<ScanController.Row> rows, String summary) {
        ((ScanController) dashboard.currentScreen()).showForDemo(rows, summary);
    }

    public static void network(DashboardController dashboard, List<NetworkController.ConnRow> rows,
                               List<com.cybershield.network.NetworkReader.Dns> dns, String summary) {
        ((NetworkController) dashboard.currentScreen()).showForDemo(rows, dns, summary);
    }

}
