package com.cybershield.ui.controllers;

import com.cybershield.dao.SettingsDAO;
import com.cybershield.logger.AppLogger;
import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordHealth;
import com.cybershield.password.PasswordService;
import com.cybershield.services.ActivityLog;
import com.cybershield.ui.Ui;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.utils.PasswordGenerator;
import com.cybershield.utils.SearchUtil;
import com.cybershield.utils.ValidationUtil;
import com.cybershield.vault.VaultService;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The Password Vault: a form for one login and the list of saved logins, with search,
 * a category filter, sorting, favourites and a health check. Passwords and every other
 * field are encrypted with the vault key by PasswordService; this screen holds them
 * decrypted only in memory, and drops them when the vault locks or the screen closes.
 */
public class PasswordVaultController implements LockableScreen {

    private static final String ALL_CATEGORIES = "All categories";

    private static final String NEEDS_ATTENTION = "Needs attention";

    private static final List<String> SORTS = List.of("Favourites first", "Website A–Z", "Recently changed", "Weakest first");

    @FXML private Label healthLabel;
    @FXML private Label formTitle;
    @FXML private CheckBox favouriteBox;
    @FXML private TextField websiteField;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private TextField visiblePasswordField;
    @FXML private Button showPasswordButton;
    @FXML private ProgressBar strengthBar;
    @FXML private Label strengthLabel;
    @FXML private Spinner<Integer> lengthSpinner;
    @FXML private CheckBox digitsBox;
    @FXML private CheckBox symbolsBox;
    @FXML private ComboBox<String> categoryBox;
    @FXML private TextArea notesArea;
    @FXML private HBox confirmBox;
    @FXML private Label confirmLabel;
    @FXML private Button updateButton;
    @FXML private Button deleteButton;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> filterBox;
    @FXML private ComboBox<String> sortBox;
    @FXML private TableView<PasswordEntry> passwordTable;
    @FXML private TableColumn<PasswordEntry, String> favouriteColumn;
    @FXML private TableColumn<PasswordEntry, String> websiteColumn;
    @FXML private TableColumn<PasswordEntry, String> usernameColumn;
    @FXML private TableColumn<PasswordEntry, String> categoryColumn;
    @FXML private TableColumn<PasswordEntry, String> healthColumn;
    @FXML private TableColumn<PasswordEntry, String> updatedColumn;
    @FXML private Label statusLabel;

    private final PasswordService service = new PasswordService();

    private final VaultService vaultService = new VaultService();

    private final SettingsDAO settings = new SettingsDAO();

    // Every saved login of the open vault, decrypted. Dropped on dispose.
    private List<PasswordEntry> entries = new ArrayList<>();

    private Map<Integer, EnumSet<PasswordHealth.Issue>> health = Map.of();

    private PasswordEntry selected;

    private boolean passwordVisible;

    // Set by the dashboard so it can show the unlock screen after locking.
    private Runnable onLocked = () -> { };

    @FXML
    public void initialize() {

        visiblePasswordField.textProperty().bindBidirectional(passwordField.textProperty());

        passwordField.textProperty().addListener((observable, before, text) -> Ui.strength(strengthBar, strengthLabel, text));

        favouriteColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().isFavourite() ? "★" : "☆"));
        favouriteColumn.setCellFactory(column -> new FavouriteCell());
        websiteColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getWebsite()));
        usernameColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getUsername()));
        categoryColumn.setCellValueFactory(cell -> new SimpleStringProperty(Objects.requireNonNullElse(cell.getValue().getCategory(), "")));
        healthColumn.setCellValueFactory(cell -> new SimpleStringProperty(PasswordHealth.describe(health.get(cell.getValue().getId()))));
        healthColumn.setCellFactory(column -> new HealthCell());
        updatedColumn.setCellValueFactory(cell -> new SimpleStringProperty(DisplayFormat.timestamp(cell.getValue().getUpdatedAt())));

        passwordTable.getSelectionModel().selectedItemProperty().addListener((observable, before, entry) -> {
            if (entry != null) {
                fillForm(entry);
            }
        });

        sortBox.setItems(FXCollections.observableArrayList(SORTS));
        sortBox.getSelectionModel().select(0);
        sortBox.valueProperty().addListener((observable, before, sort) -> showList());

        filterBox.valueProperty().addListener((observable, before, filter) -> showList());

        searchField.textProperty().addListener((observable, before, text) -> showList());

        loadPasswords();

        clearForm();

    }

    // =====================================================
    // THE LIST
    // =====================================================

    private void loadPasswords() {

        try {

            entries = new ArrayList<>(service.getAllPasswords());

        } catch (RuntimeException e) {

            AppLogger.error("Couldn't read the saved logins: " + e.getMessage());

            entries = new ArrayList<>();

            status("The saved logins couldn't be read. See the console for details.", "bad");

        }

        health = PasswordHealth.check(entries);

        showHealth();

        refreshCategories();

        showList();

    }

    private void showHealth() {

        PasswordHealth.Summary summary = PasswordHealth.summarize(health);

        healthLabel.getStyleClass().removeAll("plain", "good", "warn");

        if (summary.total() == 0) {
            healthLabel.setText("No saved logins yet");
            healthLabel.getStyleClass().add("plain");
        } else if (summary.allGood()) {
            healthLabel.setText("All " + summary.total() + " passwords look healthy");
            healthLabel.getStyleClass().add("good");
        } else {
            healthLabel.setText("Health: " + summary.weak() + " weak · " + summary.reused() + " reused · " + summary.old() + " old");
            healthLabel.getStyleClass().add("warn");
        }

    }

    private void refreshCategories() {

        TreeSet<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (PasswordEntry entry : entries) {
            if (entry.getCategory() != null && !entry.getCategory().isBlank()) {
                categories.add(entry.getCategory().strip());
            }
        }

        String filter = filterBox.getValue();

        List<String> filters = new ArrayList<>(List.of(ALL_CATEGORIES, NEEDS_ATTENTION));
        filters.addAll(categories);

        filterBox.setItems(FXCollections.observableArrayList(filters));
        filterBox.setValue(filter != null && filters.contains(filter) ? filter : ALL_CATEGORIES);

        String typed = categoryBox.getEditor().getText();
        categoryBox.setItems(FXCollections.observableArrayList(categories));
        categoryBox.getEditor().setText(typed);

    }

    private void showList() {

        List<PasswordEntry> visible = new ArrayList<>(SearchUtil.filter(entries, searchField.getText()));

        // The shared search covers website and username; the category counts too.
        String query = searchField.getText() == null ? "" : searchField.getText().strip().toLowerCase();
        if (!query.isEmpty()) {
            for (PasswordEntry entry : entries) {
                if (!visible.contains(entry) && entry.getCategory() != null && entry.getCategory().toLowerCase().contains(query)) {
                    visible.add(entry);
                }
            }
        }

        String filter = filterBox.getValue();

        if (NEEDS_ATTENTION.equals(filter)) {
            visible.removeIf(entry -> health.getOrDefault(entry.getId(), EnumSet.noneOf(PasswordHealth.Issue.class)).isEmpty());
        } else if (filter != null && !ALL_CATEGORIES.equals(filter)) {
            visible.removeIf(entry -> entry.getCategory() == null || !entry.getCategory().strip().equalsIgnoreCase(filter));
        }

        visible.sort(comparator());

        PasswordEntry keep = selected;

        passwordTable.setItems(FXCollections.observableArrayList(visible));

        if (keep != null) {
            visible.stream().filter(entry -> entry.getId() == keep.getId()).findFirst()
                    .ifPresent(entry -> passwordTable.getSelectionModel().select(entry));
        }

    }

    private Comparator<PasswordEntry> comparator() {

        Comparator<PasswordEntry> byWebsite = Comparator.comparing(entry -> entry.getWebsite() == null ? "" : entry.getWebsite().toLowerCase());

        Comparator<PasswordEntry> recent = Comparator.comparing((PasswordEntry entry) ->
                entry.getUpdatedAt() == null ? "" : entry.getUpdatedAt()).reversed();

        return switch (sortBox.getValue() == null ? SORTS.get(0) : sortBox.getValue()) {
            case "Website A–Z" -> byWebsite;
            case "Recently changed" -> recent;
            case "Weakest first" -> Comparator.comparing((PasswordEntry entry) ->
                    -health.getOrDefault(entry.getId(), EnumSet.noneOf(PasswordHealth.Issue.class)).size()).thenComparing(byWebsite);
            default -> Comparator.comparing((PasswordEntry entry) -> !entry.isFavourite()).thenComparing(byWebsite);
        };

    }

    @FXML
    private void refreshTable() {

        loadPasswords();

    }

    // =====================================================
    // THE FORM
    // =====================================================

    private void fillForm(PasswordEntry entry) {

        hideConfirm();

        selected = entry;

        formTitle.setText("Edit login");

        websiteField.setText(entry.getWebsite());
        usernameField.setText(entry.getUsername());
        passwordField.setText(entry.getPassword());
        notesArea.setText(entry.getNotes());
        categoryBox.getEditor().setText(entry.getCategory() == null ? "" : entry.getCategory());
        favouriteBox.setSelected(entry.isFavourite());

        setPasswordVisible(false);

        updateButton.setDisable(false);
        deleteButton.setDisable(false);

        EnumSet<PasswordHealth.Issue> issues = health.get(entry.getId());

        status(issues == null || issues.isEmpty() ? "" : advice(issues), issues == null || issues.isEmpty() ? null : "warn");

    }

    private static String advice(EnumSet<PasswordHealth.Issue> issues) {

        List<String> tips = new ArrayList<>();

        if (issues.contains(PasswordHealth.Issue.WEAK)) {
            tips.add("this password is weak: Generate a long one");
        }

        if (issues.contains(PasswordHealth.Issue.REUSED)) {
            tips.add("it is used for other logins too: give each site its own");
        }

        if (issues.contains(PasswordHealth.Issue.OLD)) {
            tips.add("it hasn't been changed in over a year");
        }

        String text = String.join("; ", tips);

        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";

    }

    @FXML
    public void clearForm() {

        hideConfirm();

        passwordTable.getSelectionModel().clearSelection();

        selected = null;

        formTitle.setText("New login");

        websiteField.clear();
        usernameField.clear();
        passwordField.clear();
        notesArea.clear();
        categoryBox.getEditor().clear();
        favouriteBox.setSelected(false);

        setPasswordVisible(false);

        updateButton.setDisable(true);
        deleteButton.setDisable(true);

        status("", null);

    }

    private PasswordEntry formEntry() {

        PasswordEntry entry = new PasswordEntry();

        entry.setWebsite(websiteField.getText().strip());
        entry.setUsername(usernameField.getText().strip());
        entry.setPassword(passwordField.getText());
        entry.setNotes(notesArea.getText().isBlank() ? null : notesArea.getText());
        String category = categoryBox.getEditor().getText();
        entry.setCategory(category == null || category.isBlank() ? null : category.strip());
        entry.setFavourite(favouriteBox.isSelected());

        return entry;

    }

    private boolean valid(PasswordEntry entry) {

        if (entry.getWebsite().isEmpty() || entry.getUsername().isEmpty() || entry.getPassword().isEmpty()) {
            status("Fill in the website, username and password.", "bad");
            return false;
        }

        if (!ValidationUtil.isValidWebsite(entry.getWebsite())) {
            status("Enter a website, for example github.com.", "bad");
            return false;
        }

        return true;

    }

    @FXML
    private void savePassword() {

        PasswordEntry entry = formEntry();

        if (!valid(entry)) {
            return;
        }

        if (service.savePassword(entry)) {
            String website = entry.getWebsite();
            clearForm();
            loadPasswords();
            status("Saved " + website + ".", "good");
        } else {
            status("The login couldn't be saved. See the console for details.", "bad");
        }

    }

    @FXML
    private void updatePassword() {

        if (selected == null) {
            status("Pick a login in the list to update it.", "bad");
            return;
        }

        PasswordEntry entry = formEntry();

        if (!valid(entry)) {
            return;
        }

        entry.setId(selected.getId());

        if (service.updatePassword(entry)) {
            loadPasswords();
            status("Updated " + entry.getWebsite() + ".", "good");
        } else {
            status("The login couldn't be updated.", "bad");
        }

    }

    // =====================================================
    // DELETE, WITH CONFIRMATION
    // =====================================================

    @FXML
    private void askDelete() {

        if (selected == null) {
            return;
        }

        confirmLabel.setText("Delete the login for " + selected.getWebsite() + "? This can't be undone.");

        confirmBox.setVisible(true);
        confirmBox.setManaged(true);

    }

    @FXML
    private void cancelDelete() {

        hideConfirm();

    }

    @FXML
    private void confirmDelete() {

        hideConfirm();

        if (selected == null) {
            return;
        }

        PasswordEntry doomed = selected;

        if (service.deletePassword(doomed)) {
            clearForm();
            loadPasswords();
            status("Deleted the login for " + doomed.getWebsite() + ".", "good");
        } else {
            status("The login couldn't be deleted.", "bad");
        }

    }

    private void hideConfirm() {

        confirmBox.setVisible(false);
        confirmBox.setManaged(false);

    }

    // =====================================================
    // SHOW, COPY, GENERATE
    // =====================================================

    @FXML
    private void togglePasswordVisibility() {

        if (passwordField.getText().isEmpty()) {
            status("There's no password to show.", null);
            return;
        }

        setPasswordVisible(!passwordVisible);

        if (passwordVisible && selected != null) {
            ActivityLog.record(ActivityLog.Action.LOGIN_SHOWN, selected.getWebsite());
        }

    }

    private void setPasswordVisible(boolean visible) {

        passwordVisible = visible;

        visiblePasswordField.setVisible(visible);

        passwordField.setVisible(!visible);

        showPasswordButton.setText(visible ? "Hide" : "Show");

    }

    @FXML
    private void copyPassword() {

        if (passwordField.getText().isEmpty()) {
            status("There's no password to copy.", null);
            return;
        }

        int seconds = settings.clipboardClearSeconds();

        Ui.copyAndClearLater(passwordField.getText(), seconds);

        ActivityLog.record(ActivityLog.Action.PASSWORD_COPIED, websiteField.getText().strip());

        status("Password copied. The clipboard clears in " + seconds + " seconds.", "good");

    }

    @FXML
    private void copyUsername() {

        if (usernameField.getText().isBlank()) {
            status("There's no username to copy.", null);
            return;
        }

        Ui.copyAndClearLater(usernameField.getText(), settings.clipboardClearSeconds());

        ActivityLog.record(ActivityLog.Action.USERNAME_COPIED, websiteField.getText().strip());

        status("Username copied.", "good");

    }

    @FXML
    private void generatePassword() {

        int length = lengthSpinner.getValue() == null ? 16 : lengthSpinner.getValue();

        try {
            length = Integer.parseInt(lengthSpinner.getEditor().getText().strip());
        } catch (NumberFormatException ignored) {
            // Keep the spinner's value.
        }

        passwordField.setText(PasswordGenerator.generate(length, digitsBox.isSelected(), symbolsBox.isSelected()));

        setPasswordVisible(true);

        status("A new " + Math.max(8, Math.min(128, length)) + "-character password. Save or Update to keep it.", null);

    }

    // =====================================================
    // LOCKING
    // =====================================================

    @FXML
    private void lockVault() {

        dispose();

        vaultService.lockVault();

        onLocked.run();

    }

    @Override
    public void setOnLocked(Runnable onLocked) {

        this.onLocked = onLocked;

    }

    /** Drops every decrypted login from memory and the screen. */
    @Override
    public void dispose() {

        entries = new ArrayList<>();

        health = Map.of();

        selected = null;

        passwordTable.getItems().clear();

        websiteField.clear();
        usernameField.clear();
        passwordField.clear();
        notesArea.clear();

    }

    private void status(String text, String tone) {

        statusLabel.getStyleClass().removeAll("good", "bad", "warn");

        if (tone != null) {
            statusLabel.getStyleClass().add(tone);
        }

        statusLabel.setText(text);

    }

    // =====================================================
    // CELLS
    // =====================================================

    /** A star that toggles the favourite mark. */
    private final class FavouriteCell extends TableCell<PasswordEntry, String> {

        FavouriteCell() {

            setOnMouseClicked(event -> {

                PasswordEntry entry = getTableRow() == null ? null : getTableRow().getItem();

                if (entry == null || isEmpty()) {
                    return;
                }

                try {

                    service.setFavourite(entry, !entry.isFavourite());

                    entry.setFavourite(!entry.isFavourite());

                    showList();

                } catch (SQLException e) {

                    status("Couldn't change the favourite mark.", "bad");

                }

                event.consume();

            });

        }

        @Override
        protected void updateItem(String star, boolean empty) {

            super.updateItem(star, empty);

            setText(empty ? null : star);

            getStyleClass().remove("favourite");

            if (!empty && "★".equals(star)) {
                getStyleClass().add("favourite");
            }

        }

    }

    /** "OK" in green, problems in amber. */
    private static final class HealthCell extends TableCell<PasswordEntry, String> {

        @Override
        protected void updateItem(String text, boolean empty) {

            super.updateItem(text, empty);

            setText(empty ? null : text);

            getStyleClass().removeAll("health-ok", "health-bad");

            if (!empty && text != null) {
                getStyleClass().add("OK".equals(text) ? "health-ok" : "health-bad");
            }

        }

    }

}
