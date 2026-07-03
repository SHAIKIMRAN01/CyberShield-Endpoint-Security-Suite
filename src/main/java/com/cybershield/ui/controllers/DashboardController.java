package com.cybershield.ui.controllers;
import com.cybershield.models.User;
import com.cybershield.services.AuthenticationService;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
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
                    "Welcome " +
                    SessionManager.getCurrentUser().getUsername()
            );

        } else {

            welcomeLabel.setText("Guest");

        }

    }

}