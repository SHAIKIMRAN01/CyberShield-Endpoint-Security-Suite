package com.cybershield.utils;

import com.cybershield.password.PasswordEntry;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

public final class SearchUtil {

    private SearchUtil() {

    }

    public static ObservableList<PasswordEntry> filter(

            List<PasswordEntry> list,

            String keyword

    ) {

        ObservableList<PasswordEntry> filtered =

                FXCollections.observableArrayList();

        if (keyword == null || keyword.isBlank()) {

            filtered.addAll(list);

            return filtered;

        }

        keyword = keyword.toLowerCase();

        for (PasswordEntry entry : list) {

            boolean match =

                    entry.getWebsite()

                            .toLowerCase()

                            .contains(keyword)

                    ||

                    entry.getUsername()

                            .toLowerCase()

                            .contains(keyword)

                    ||

                    (

                            entry.getNotes() != null

                            &&

                            entry.getNotes()

                                    .toLowerCase()

                                    .contains(keyword)

                    );

            if (match) {

                filtered.add(entry);

            }

        }

        return filtered;

    }

}