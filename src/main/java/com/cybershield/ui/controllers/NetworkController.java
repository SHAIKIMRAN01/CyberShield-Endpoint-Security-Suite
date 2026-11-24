package com.cybershield.ui.controllers;

import com.cybershield.logger.AppLogger;
import com.cybershield.network.NetworkAnalysis;
import com.cybershield.network.NetworkAnalysis.Connection;
import com.cybershield.network.NetworkAnalysis.Scope;
import com.cybershield.network.NetworkReader;
import com.cybershield.network.NetworkReader.Dns;
import com.cybershield.network.NetworkReader.Snapshot;
import com.cybershield.ui.Ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.util.Callback;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Network Analyzer: shows which apps are using the network right now and where each
 * connection goes, with a plain-words explanation for every one, plus the names Windows
 * has looked up recently. It reads a read-only snapshot through {@link NetworkReader} and
 * explains it with {@link NetworkAnalysis}; it needs no vault and changes nothing. No
 * traffic contents are read — only which app is talking to which address on which port.
 */
public class NetworkController implements LockableScreen {

    @FXML private Button refreshButton;
    @FXML private Label summaryLabel;
    @FXML private HBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private VBox dnsBox;
    @FXML private Label dnsHeading;

    @FXML private TableView<ConnRow> connectionsTable;
    @FXML private TableColumn<ConnRow, String> appColumn;
    @FXML private TableColumn<ConnRow, String> scopeColumn;
    @FXML private TableColumn<ConnRow, String> serviceColumn;
    @FXML private TableColumn<ConnRow, String> addressColumn;
    @FXML private TableColumn<ConnRow, String> explanationColumn;

    @FXML private TableView<Dns> dnsTable;
    @FXML private TableColumn<Dns, String> nameColumn;
    @FXML private TableColumn<Dns, String> resolvedColumn;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private volatile boolean reading;

    /** One line in the connections table. */
    public record ConnRow(String app, String scope, String service, String address, String explanation) {
    }

    /** What a finished read hands back to the UI thread. */
    private record Report(List<ConnRow> rows, List<Dns> dns, String summary) {
    }

    @FXML
    public void initialize() {

        connectionsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        connectionsTable.setPlaceholder(new Label("Press Refresh to see which apps are using the network."));

        appColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().app()));
        scopeColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().scope()));
        serviceColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().service()));
        addressColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().address()));
        explanationColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().explanation()));

        scopeColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("scope-tag", "scope-internet", "scope-local",
                        "scope-computer", "scope-unknown");
                if (empty || value == null) {
                    setText(null);
                } else {
                    setText(value);
                    getStyleClass().addAll("scope-tag", classForScope(value));
                }
            }
        });

        addressColumn.setCellFactory(wrapping());
        explanationColumn.setCellFactory(wrapping());

        dnsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        dnsTable.setPlaceholder(new Label("Nothing has been looked up recently."));
        nameColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().name()));
        resolvedColumn.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().data()));

        show(progressBox, false);
        show(dnsBox, false);
    }

    @FXML
    private void refresh() {

        if (reading) {
            return;
        }
        reading = true;
        cancelled.set(false);

        connectionsTable.getItems().clear();
        summaryLabel.setText("");
        show(progressBox, true);
        progressBar.setProgress(-1);
        statusLabel.setText("Reading the connections in use…");
        refreshButton.setDisable(true);

        Ui.background("network-read", this::readSnapshot, this::showReport, this::showFailure);
    }

    /** Runs off the UI thread. Reads a snapshot and changes nothing. */
    private Report readSnapshot() {

        Snapshot snapshot = NetworkReader.read();

        if (cancelled.get()) {
            return new Report(List.of(), List.of(), "");
        }

        Map<String, String> hosts = snapshot.hostByAddress();

        List<ConnRow> rows = new ArrayList<>();

        // grouped by app, so all of a browser's many per-site connections sit together
        snapshot.connections().stream()
                .sorted(Comparator
                        .comparing((Connection c) -> c.app(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(c -> c.isEstablished() ? 0 : 1)
                        .thenComparingInt(c -> scopeRank(effectiveScope(c)))
                        .thenComparing(Connection::remoteAddress))
                .forEach(c -> rows.add(connRow(c, hostFor(c, hosts))));

        return new Report(rows, snapshot.dnsCache(), summarise(snapshot.connections(), snapshot.dnsCache().size()));
    }

    private static String hostFor(Connection c, Map<String, String> hosts) {
        if (!c.isEstablished() || c.remoteAddress() == null) {
            return null;
        }
        return hosts.get(c.remoteAddress().trim().toLowerCase(Locale.ROOT));
    }

    private void showReport(Report report) {
        reading = false;
        show(progressBox, false);
        refreshButton.setDisable(false);
        connectionsTable.getItems().setAll(report.rows());
        dnsTable.getItems().setAll(report.dns());
        show(dnsBox, !report.dns().isEmpty());
        summaryLabel.setText(report.summary());
    }

    private void showFailure(Throwable error) {
        reading = false;
        show(progressBox, false);
        refreshButton.setDisable(false);
        summaryLabel.setText("Couldn't read the connections. Details are in CyberShield's log file.");
        AppLogger.error("Network Analyzer read failed", error);
    }

    static ConnRow connRow(Connection c) {
        return connRow(c, null);
    }

    static ConnRow connRow(Connection c, String host) {
        boolean established = c.isEstablished();
        int port = established ? c.remotePort() : c.localPort();
        boolean named = host != null && !host.isBlank() && established;
        String endpoint = named ? host : (established ? c.remoteAddress() : c.localAddress());
        Scope scope = effectiveScope(c);
        return new ConnRow(c.app(), scope.label(), NetworkAnalysis.serviceLabel(port),
                endpoint + " : " + port, NetworkAnalysis.explain(c, host));
    }

    static String summarise(List<Connection> connections, int dnsCount) {

        if (connections.isEmpty()) {
            return "No active connections were found.";
        }

        long apps = connections.stream().map(Connection::app).distinct().count();
        long internet = connections.stream()
                .filter(c -> c.isEstablished() && NetworkAnalysis.scopeOf(c.remoteAddress()) == Scope.INTERNET)
                .count();
        long listening = connections.stream().filter(c -> !c.isEstablished()).count();

        StringBuilder text = new StringBuilder();
        text.append(connections.size()).append(connections.size() == 1 ? " connection" : " connections");
        text.append(" across ").append(apps).append(apps == 1 ? " app" : " apps");
        text.append(" · ").append(internet).append(" to the Internet");
        text.append(" · ").append(listening).append(" listening");
        if (dnsCount > 0) {
            text.append(" · ").append(dnsCount).append(" names looked up recently");
        }
        return text.append(".").toString();
    }

    static String classForScope(String scopeLabel) {
        return switch (scopeLabel) {
            case "The Internet" -> "scope-internet";
            case "Local network", "Local network group" -> "scope-local";
            case "Unknown" -> "scope-unknown";
            default -> "scope-computer";
        };
    }

    private static Scope effectiveScope(Connection c) {
        return c.isEstablished() ? NetworkAnalysis.scopeOf(c.remoteAddress()) : NetworkAnalysis.scopeOf(c.localAddress());
    }

    private static int scopeRank(Scope scope) {
        return switch (scope) {
            case INTERNET -> 0;
            case PRIVATE, LINK_LOCAL, MULTICAST -> 1;
            case ANY -> 2;
            case LOOPBACK -> 3;
            case UNKNOWN -> 4;
        };
    }

    /** Test hook: show canned results without reading the machine (for the screenshot walk). */
    void showForDemo(List<ConnRow> rows, List<Dns> dns, String summary) {
        connectionsTable.getItems().setAll(rows);
        dnsTable.getItems().setAll(dns);
        show(dnsBox, !dns.isEmpty());
        summaryLabel.setText(summary);
        show(progressBox, false);
        refreshButton.setDisable(false);
        reading = false;
    }

    private static Callback<TableColumn<ConnRow, String>, TableCell<ConnRow, String>> wrapping() {
        return col -> new TableCell<>() {
            private final Text text = new Text();

            {
                text.getStyleClass().add("cell-text");
                text.wrappingWidthProperty().bind(col.widthProperty().subtract(14));
                setGraphic(text);
                setPrefHeight(Region.USE_COMPUTED_SIZE);
            }

            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                text.setText(empty || value == null ? "" : value);
            }
        };
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    @Override
    public void setOnLocked(Runnable onLocked) {
        // the Network Analyzer works without the vault and has no Lock button; nothing to wire
    }

    @Override
    public void dispose() {
        // leaving the screen stops a read from updating it afterwards
        cancelled.set(true);
        reading = false;
    }

}
