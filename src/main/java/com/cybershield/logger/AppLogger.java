package com.cybershield.logger;

import java.time.LocalDateTime;

public final class AppLogger {

    private AppLogger() {
    }

    public static void info(String message) {

        System.out.println(
                "[" +
                LocalDateTime.now() +
                "] INFO : " +
                message
        );

    }

    public static void error(String message) {

        System.err.println(
                "[" +
                LocalDateTime.now() +
                "] ERROR : " +
                message
        );

    }

    public static void success(String message) {

        System.out.println(
                "[" +
                LocalDateTime.now() +
                "] SUCCESS : " +
                message
        );

    }

}