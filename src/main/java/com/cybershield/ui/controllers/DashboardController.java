package com.cybershield.ui.controllers;

import com.cybershield.security.SessionManager;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

public class DashboardController {

    @FXML
    private Label welcomeLabel;

    @FXML
    public void initialize() {

        if (SessionManager.isLoggedIn()) {

            welcomeLabel.setText(
                    "Welcome, " +
                    SessionManager.getCurrentUser().getUsername()
            );

        } else {

            welcomeLabel.setText("Guest");

        }

    }

    @FXML
    private void logout() {

        SessionManager.logout();

        System.out.println("User Logged Out");

    }

}