package com.cybershield.ui.controllers;

import com.cybershield.encryption.FileDecryptor;
import com.cybershield.files.CloudFiles;
import com.cybershield.files.FileSelection;
import com.cybershield.files.FileVaultService;
import com.cybershield.files.ProtectedItem;
import com.cybershield.ui.viewer.FilePreview;
import com.cybershield.ui.viewer.PreviewKind;
import com.cybershield.ui.viewer.Previews;
import com.cybershield.utils.DisplayFormat;
import com.cybershield.vault.AutoLock;
import com.cybershield.vault.VaultService;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * File Security: browse to files and folders, tick them (from as many places as
 * needed), review, then encrypt and hide them in the vault. Protected items can
 * be restored to where they were.
 */
public class FileSecurityController implements LockableScreen {

    /** One entry in the folder listing. */
    public static final class FileRow {

        private final Path path;
        private final String name;
        private final boolean folder;
        private final long size;
        private final long modified;
        private boolean selected;
        private boolean includedByParent;

        FileRow(Path path, boolean folder, long size, long modified) {
            this.path = path;
            this.name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            this.folder = folder;
            this.size = size;
            this.modified = modified;
        }

        public Path getPath() {
            return path;
        }

        public String getName() {
            return name;
        }

        public boolean isFolder() {
            return folder;
        }

        /** Ticked itself. */
        public boolean isSelected() {
            return selected;
        }

        /** Inside a folder that is ticked, so it's included already. */
        public boolean isIncludedByParent() {
            return includedByParent;
        }

    }

    /** A background job whose progress, text and stop request reach the screen. */
    private abstract class JobTask<T> extends Task<T> {

        final FileVaultService.JobListener listener = new FileVaultService.JobListener() {

            @Override
            public void progress(long done, long total) {
                updateProgress(done, total);
            }

            @Override
            public void message(String text) {
                updateMessage(text);
            }

            @Override
            public boolean isCancelled() {
                return stopRequested.get();
            }

        };

        JobTask(String firstMessage) {
            updateMessage(firstMessage);
        }

    }

    // ---- browser
    @FXML private ComboBox<Path> driveBox;
    @FXML private TextField pathField;
    @FXML private TableView<FileRow> fileTable;
    @FXML private TableColumn<FileRow, FileRow> selectColumn;
    @FXML private TableColumn<FileRow, String> nameColumn;
    @FXML private TableColumn<FileRow, String> typeColumn;
    @FXML private TableColumn<FileRow, String> sizeColumn;
    @FXML private TableColumn<FileRow, String> modifiedColumn;
    @FXML private Button addFilesButton;
    @FXML private Button addFolderButton;

    // ---- selection tray
    @FXML private Label trayTitle;
    @FXML private Label traySummary;
    @FXML private ListView<Path> trayList;
    @FXML private Button clearSelectionButton;
    @FXML private Button encryptButton;

    // ---- review and progress
    @FXML private VBox reviewBox;
    @FXML private Label reviewLabel;
    @FXML private Button confirmButton;
    @FXML private HBox progressBox;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;

    // ---- protected items: tree on the left, the chosen file on the right
    @FXML private Tab protectedTab;
    @FXML private TreeView<VaultNode> vaultTree;
    @FXML private Button restoreButton;
    @FXML private Label previewTitle;
    @FXML private Label previewDetails;
    @FXML private Button fullScreenButton;
    @FXML private Button exportButton;
    @FXML private Button restoreFileButton;
    @FXML private StackPane previewPane;
    @FXML private HBox viewerControls;

    @FXML private Label statusLabel;
    @FXML private Label autoLockLabel;

    private final FileVaultService service = new FileVaultService();
    private final VaultService vaultService = new VaultService();
    private final FileSelection selection = new FileSelection();
    private final Map<Path, Boolean> selectedIsFolder = new HashMap<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean();

    private Path currentFolder;
    private FileVaultService.ScanResult pendingScan;
    private boolean busy;
    private boolean updatingDrive;
    private Runnable onLocked = () -> { };

    // ---- viewer state
    private FilePreview preview;
    private VaultNode previewNode;
    private TreeItem<VaultNode> previewTreeItem;
    private long previewGeneration;
    private final List<Stage> fullScreenWindows = new ArrayList<>();

    @FXML
    public void initialize() {

        setUpBrowser();

        setUpTray();

        setUpVaultTree();

        bindAutoLockCountdown();

        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            driveBox.getItems().add(root);
        }

        service.removeLeftoverTempFiles();

        openFolder(Path.of(System.getProperty("user.home")));

        refreshTray();

        loadProtectedItems();

        if (FileVaultService.isJobRunning()) {
            status("A file job from earlier is still running. Its results appear under Protected items when it ends.");
        }

    }

    @Override
    public void setOnLocked(Runnable onLocked) {
        this.onLocked = onLocked;
    }

    /** Drops the open file and closes full-screen windows. A running file job carries on. */
    @Override
    public void dispose() {
        closePreview();
    }

    /** Shows how long until the vault locks itself. */
    private void bindAutoLockCountdown() {

        var secondsLeft = AutoLock.getInstance().secondsLeftProperty();

        autoLockLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            int left = secondsLeft.get();
            return left < 0 ? "" : String.format(Locale.ROOT, "Locks itself in %d:%02d", left / 60, left % 60);
        }, secondsLeft));

    }

    // =====================================================
    // BROWSER
    // =====================================================

    private void setUpBrowser() {

        selectColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));

        selectColumn.setCellFactory(column -> new TableCell<>() {

            private final CheckBox box = new CheckBox();

            {
                box.setOnAction(event -> {
                    FileRow row = getItem();
                    if (row != null) {
                        toggle(row, box.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(FileRow row, boolean empty) {

                super.updateItem(row, empty);

                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }

                box.setSelected(row.selected || row.includedByParent);
                box.setDisable(row.includedByParent || busy);
                setGraphic(box);

            }

        });

        nameColumn.setCellValueFactory(cell ->
                new SimpleStringProperty((cell.getValue().folder ? "📁  " : "📄  ") + cell.getValue().name));

        typeColumn.setCellValueFactory(cell -> new SimpleStringProperty(typeOf(cell.getValue())));

        sizeColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(cell.getValue().folder ? "" : DisplayFormat.size(cell.getValue().size)));

        modifiedColumn.setCellValueFactory(cell ->
                new SimpleStringProperty(DisplayFormat.dateTime(cell.getValue().modified)));

        // Double-click a folder to open it.
        fileTable.setRowFactory(table -> {

            TableRow<FileRow> row = new TableRow<>();

            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                        && !row.isEmpty() && row.getItem().folder) {
                    openFolder(row.getItem().path);
                }
            });

            return row;

        });

        acceptDrops(fileTable);

    }

    private void openFolder(Path folder) {

        Path target = folder.toAbsolutePath().normalize();

        List<FileRow> rows = new ArrayList<>();

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(target)) {

            for (Path entry : entries) {

                FileRow row = readRow(entry);

                if (row != null) {
                    rows.add(row);
                }

            }

        } catch (NotDirectoryException | NoSuchFileException e) {
            status("That folder doesn't exist.");
            return;
        } catch (AccessDeniedException e) {
            status("Windows doesn't allow opening that folder.");
            return;
        } catch (IOException e) {
            status("Couldn't open that folder: " + e.getMessage());
            return;
        }

        rows.sort(Comparator.comparing((FileRow row) -> !row.folder)
                .thenComparing(row -> row.name.toLowerCase(Locale.ROOT)));

        currentFolder = target;

        pathField.setText(target.toString());

        updatingDrive = true;
        driveBox.setValue(driveBox.getItems().stream()
                .filter(root -> root.equals(target.getRoot()))
                .findFirst()
                .orElse(null));
        updatingDrive = false;

        fileTable.getItems().setAll(rows);

        refreshRows();

        Path cover = selection.includedBy(target);

        if (cover != null) {
            status("Everything in this folder is already selected through " + displayName(cover) + ".");
        }

    }

    /** Hidden and system items, and links, are left out of the listing. */
    private static FileRow readRow(Path entry) {

        try {

            BasicFileAttributes attributes;

            try {

                DosFileAttributes dos = Files.readAttributes(entry, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

                if (dos.isHidden() || dos.isSystem()) {
                    return null;
                }

                attributes = dos;

            } catch (UnsupportedOperationException e) {

                attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            }

            if (attributes.isSymbolicLink()) {
                return null;
            }

            return new FileRow(
                    entry,
                    attributes.isDirectory(),
                    attributes.isDirectory() ? 0 : attributes.size(),
                    attributes.lastModifiedTime().toMillis());

        } catch (IOException e) {

            return null;

        }

    }

    private static String typeOf(FileRow row) {

        if (row.folder) {
            return "Folder";
        }

        int dot = row.name.lastIndexOf('.');

        return dot > 0 && dot < row.name.length() - 1
                ? row.name.substring(dot + 1).toUpperCase(Locale.ROOT) + " file"
                : "File";

    }

    @FXML
    private void goUp() {

        if (currentFolder != null && currentFolder.getParent() != null) {
            openFolder(currentFolder.getParent());
        }

    }

    @FXML
    private void goToPath() {

        try {
            openFolder(Path.of(pathField.getText().trim()));
        } catch (InvalidPathException e) {
            status("That isn't a valid folder path.");
        }

    }

    @FXML
    private void changeDrive() {

        if (!updatingDrive && driveBox.getValue() != null) {
            openFolder(driveBox.getValue());
        }

    }

    @FXML
    private void addFiles() {

        FileChooser chooser = new FileChooser();

        chooser.setTitle("Choose files to protect");

        if (currentFolder != null && Files.isDirectory(currentFolder)) {
            chooser.setInitialDirectory(currentFolder.toFile());
        }

        List<File> files = chooser.showOpenMultipleDialog(window());

        if (files != null) {
            addToSelection(files.stream().map(File::toPath).toList());
        }

    }

    @FXML
    private void addFolder() {

        DirectoryChooser chooser = new DirectoryChooser();

        chooser.setTitle("Choose a folder to protect");

        if (currentFolder != null && Files.isDirectory(currentFolder)) {
            chooser.setInitialDirectory(currentFolder.toFile());
        }

        File folder = chooser.showDialog(window());

        if (folder != null) {
            addToSelection(List.of(folder.toPath()));
        }

    }

    /** Files and folders dragged in from Explorer are added to the selection. */
    private void acceptDrops(Node target) {

        target.setOnDragOver(event -> {

            if (!busy && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }

            event.consume();

        });

        target.setOnDragDropped(event -> {

            boolean done = false;

            if (!busy && event.getDragboard().hasFiles()) {
                addToSelection(event.getDragboard().getFiles().stream().map(File::toPath).toList());
                done = true;
            }

            event.setDropCompleted(done);

            event.consume();

        });

    }

    // =====================================================
    // SELECTION TRAY
    // =====================================================

    private void setUpTray() {

        trayList.setCellFactory(list -> new ListCell<>() {

            private final Label icon = new Label();
            private final Label name = new Label();
            private final Label where = new Label();
            private final Button remove = new Button("✕");
            private final HBox box;

            {
                name.getStyleClass().add("tray-name");
                where.getStyleClass().add("tray-path");
                remove.getStyleClass().add("tray-remove");
                remove.setTooltip(new Tooltip("Remove from the selection"));
                remove.setOnAction(event -> {
                    Path path = getItem();
                    if (path != null) {
                        removeFromSelection(path);
                    }
                });

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                box = new HBox(8, icon, new VBox(1, name, where), spacer, remove);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Path path, boolean empty) {

                super.updateItem(path, empty);

                if (empty || path == null) {
                    setGraphic(null);
                    return;
                }

                icon.setText(Boolean.TRUE.equals(selectedIsFolder.get(path)) ? "📁" : "📄");
                name.setText(displayName(path));
                where.setText(path.getParent() == null ? "" : path.getParent().toString());
                remove.setDisable(busy);
                setGraphic(box);

            }

        });

        acceptDrops(trayList);

    }

    private void toggle(FileRow row, boolean ticked) {

        if (ticked) {

            addToSelection(List.of(row.path));

        } else {

            removeFromSelection(row.path);

        }

    }

    /** Adds picked paths to the tray: called by drag and drop, the Add dialogs and the file list (and the tests). */
    void addToSelection(List<Path> paths) {

        int added = 0;

        String note = null;

        for (Path raw : paths) {

            Path path = raw.toAbsolutePath().normalize();

            String name = displayName(path);

            String denied = service.checkAllowed(path);

            if (denied != null) {
                note = name + ": " + denied;
                continue;
            }

            FileSelection.AddResult result = selection.add(path);

            switch (result.change()) {

                case ADDED -> {
                    added++;
                    selectedIsFolder.put(path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
                }

                case ADDED_AND_MERGED -> {
                    added++;
                    selectedIsFolder.put(path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
                    note = name + " already held " + count(result.merged(), "selected item")
                            + ", so they're now included through it.";
                }

                case ALREADY_INCLUDED -> note = result.coveredBy().equals(path)
                        ? name + " is already selected."
                        : name + " is already included through " + displayName(result.coveredBy()) + ".";

            }

        }

        selectedIsFolder.keySet().retainAll(selection.entries());

        if (note != null) {
            status(note);
        } else if (added > 0) {
            status("Added " + count(added, "item") + " to the selection.");
        }

        selectionChanged();

    }

    private void removeFromSelection(Path path) {

        selection.remove(path);

        selectedIsFolder.remove(path.toAbsolutePath().normalize());

        status("Removed " + displayName(path) + " from the selection.");

        selectionChanged();

    }

    @FXML
    private void clearSelection() {

        selection.clear();

        selectedIsFolder.clear();

        status("Selection cleared.");

        selectionChanged();

    }

    private void selectionChanged() {

        hideReview();

        refreshRows();

        refreshTray();

    }

    private void refreshRows() {

        for (FileRow row : fileTable.getItems()) {
            row.selected = selection.contains(row.path);
            row.includedByParent = !row.selected && selection.includedBy(row.path) != null;
        }

        fileTable.refresh();

    }

    private void refreshTray() {

        List<Path> entries = selection.entries();

        trayList.getItems().setAll(entries);

        long folders = entries.stream().filter(path -> Boolean.TRUE.equals(selectedIsFolder.get(path))).count();

        trayTitle.setText("Selected (" + entries.size() + ")");

        traySummary.setText(entries.isEmpty()
                ? "Tick files or folders above, or drop them here. Newest picks appear on top."
                : count(folders, "folder") + " and " + count(entries.size() - folders, "file") + " · newest on top");

        encryptButton.setDisable(entries.isEmpty() || busy);

        clearSelectionButton.setDisable(entries.isEmpty() || busy);

    }

    // =====================================================
    // REVIEW AND PROTECT
    // =====================================================

    /** Scans the selection first, so the user sees exactly what will happen before anything changes. */
    @FXML
    private void reviewSelection() {

        if (busy) {
            return;
        }

        if (selection.isEmpty()) {
            status("Tick files or folders first.");
            return;
        }

        if (FileVaultService.isJobRunning()) {
            status("Another file job is still running. Wait for it to finish.");
            return;
        }

        List<Path> paths = selection.entries();

        runJob(new JobTask<FileVaultService.ScanResult>("Checking the selection…") {

            @Override
            protected FileVaultService.ScanResult call() throws Exception {
                return service.scan(paths, listener);
            }

        }, this::showReview);

    }

    private void showReview(FileVaultService.ScanResult scan) {

        pendingScan = scan;

        if (scan.items().isEmpty()) {
            status(scan.skipped().isEmpty()
                    ? "There's nothing to protect in the selection."
                    : "Nothing in the selection can be protected. " + scan.skipped().get(0));
            return;
        }

        StringBuilder text = new StringBuilder()
                .append("Ready to encrypt and hide ")
                .append(count(scan.fileCount(), "file"))
                .append(" (").append(DisplayFormat.size(scan.totalBytes())).append(") from ")
                .append(count(scan.items().size(), "selected item")).append(".\n")
                .append("The originals will be erased from their folders. ")
                .append("You can bring them back at any time from Protected items.");

        if (!scan.skipped().isEmpty()) {
            text.append("\nWill be skipped: ").append(count(scan.skipped().size(), "item"))
                    .append(", for example ").append(scan.skipped().get(0));
        }

        // OneDrive syncs deletions: protected files leave OneDrive everywhere, not just this PC.
        CloudFiles.Summary cloud = scan.cloud();

        if (cloud.any()) {

            text.append("\nOneDrive: ").append(count(cloud.files(), "file")).append(cloud.files() == 1 ? " is" : " are")
                    .append(" kept in OneDrive. Protecting removes ").append(cloud.files() == 1 ? "it" : "them")
                    .append(" from OneDrive on all your devices and online; the encrypted copy stays only on this PC.");

            if (cloud.onlineOnly() > 0) {
                text.append(" ").append(count(cloud.onlineOnly(), "file")).append(cloud.onlineOnly() == 1 ? " is" : " are")
                        .append(" online-only and will be downloaded first (")
                        .append(DisplayFormat.size(cloud.onlineOnlyBytes())).append(").");
            }

        }

        boolean room = scan.enoughSpace();

        if (!room) {
            text.append("\nNot enough free space for the encrypted copies: ")
                    .append(DisplayFormat.size(scan.bytesNeeded())).append(" needed, ")
                    .append(DisplayFormat.size(scan.bytesFree())).append(" free.");
        }

        reviewLabel.setText(text.toString());

        confirmButton.setDisable(!room);

        reviewBox.setVisible(true);
        reviewBox.setManaged(true);

        status(room ? "Check the summary, then confirm." : "Free up some space, then try again.");

    }

    @FXML
    private void cancelReview() {

        hideReview();

        status("Cancelled. Nothing was changed.");

    }

    private void hideReview() {

        pendingScan = null;

        reviewBox.setVisible(false);
        reviewBox.setManaged(false);

    }

    @FXML
    private void confirmProtect() {

        FileVaultService.ScanResult scan = pendingScan;

        if (scan == null || busy) {
            return;
        }

        hideReview();

        runJob(new JobTask<FileVaultService.ProtectResult>("Encrypting…") {

            @Override
            protected FileVaultService.ProtectResult call() {
                return service.protect(scan, listener);
            }

        }, this::finishProtect);

    }

    private void finishProtect(FileVaultService.ProtectResult result) {

        selection.clear();

        selectedIsFolder.clear();

        refreshTray();

        reopenCurrentFolder();

        loadProtectedItems();

        StringBuilder text = new StringBuilder("Protected ").append(count(result.protectedFiles(), "file")).append('.');

        if (result.skippedInUse() > 0) {
            text.append(" Left alone because another program has them open: ")
                    .append(count(result.skippedInUse(), "file")).append('.');
        }

        if (result.skippedChanged() > 0) {
            text.append(" Left alone because they changed during encryption: ")
                    .append(count(result.skippedChanged(), "file")).append('.');
        }

        appendEnding(text, result.stoppedEarly(), result.problems());

        status(text.toString());

    }

    // =====================================================
    // PROTECTED ITEMS: THE TREE
    // =====================================================

    /** One entry in the tree: a protected item, a folder inside one, or a file. */
    public static final class VaultNode {

        public enum Kind { ITEM, FOLDER, FILE, LOADING }

        private final Kind kind;
        private final ProtectedItem item;
        private final String name;
        private FileVaultService.VaultFile file;
        private boolean loaded;

        private VaultNode(Kind kind, ProtectedItem item, String name, FileVaultService.VaultFile file) {
            this.kind = kind;
            this.item = item;
            this.name = name;
            this.file = file;
        }

        static VaultNode item(ProtectedItem item) {
            return new VaultNode(Kind.ITEM, item, item.name(), null);
        }

        static VaultNode folder(ProtectedItem item, String path) {
            return new VaultNode(Kind.FOLDER, item, Path.of(path).getFileName().toString(), null);
        }

        static VaultNode file(ProtectedItem item, FileVaultService.VaultFile file) {
            return new VaultNode(Kind.FILE, item, file.name(), file);
        }

        static VaultNode loading(ProtectedItem item) {
            return new VaultNode(Kind.LOADING, item, "Opening…", null);
        }

        public Kind getKind() {
            return kind;
        }

        public String getName() {
            return name;
        }

        public ProtectedItem getItem() {
            return item;
        }

        public FileVaultService.VaultFile getFile() {
            return file;
        }

    }

    // Folders first, then files, by name.
    private static final Comparator<TreeItem<VaultNode>> TREE_ORDER =
            Comparator.comparing((TreeItem<VaultNode> entry) -> entry.getValue().kind == VaultNode.Kind.FILE)
                    .thenComparing(entry -> entry.getValue().name.toLowerCase(Locale.ROOT));

    private void setUpVaultTree() {

        vaultTree.setRoot(new TreeItem<>());
        vaultTree.setShowRoot(false);
        vaultTree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        vaultTree.setCellFactory(tree -> new TreeCell<>() {

            @Override
            protected void updateItem(VaultNode node, boolean empty) {

                super.updateItem(node, empty);

                if (empty || node == null) {
                    setText(null);
                    return;
                }

                boolean folder = node.kind == VaultNode.Kind.FOLDER
                        || (node.kind == VaultNode.Kind.ITEM && node.item.type() == ProtectedItem.Type.FOLDER);

                setText(node.kind == VaultNode.Kind.LOADING ? node.name : (folder ? "📁  " : "📄  ") + node.name);

            }

        });

        vaultTree.getSelectionModel().selectedItemProperty()
                .addListener((observable, before, selected) -> showSelection(selected));

    }

    private void loadProtectedItems() {

        closePreview();

        try {

            List<ProtectedItem> items = service.listItems();

            TreeItem<VaultNode> root = new TreeItem<>();

            for (ProtectedItem item : items) {
                root.getChildren().add(itemEntry(item));
            }

            vaultTree.setRoot(root);

            protectedTab.setText("Protected items (" + items.size() + ")");

            showNothingSelected();

        } catch (SQLException e) {

            status("Couldn't load the protected items: " + e.getMessage());

        } catch (IllegalStateException e) {

            status("Unlock the vault to see the protected items.");

        }

    }

    private TreeItem<VaultNode> itemEntry(ProtectedItem item) {

        TreeItem<VaultNode> entry = new TreeItem<>(VaultNode.item(item));

        if (item.type() == ProtectedItem.Type.FOLDER) {

            // A placeholder gives the folder its arrow; its files are read when it's opened.
            entry.getChildren().add(new TreeItem<>(VaultNode.loading(item)));

            entry.expandedProperty().addListener((observable, before, expanded) -> {
                if (expanded) {
                    loadItemFiles(entry);
                }
            });

        }

        return entry;

    }

    /** Reads what's inside a protected folder: only the encrypted details, not the contents. */
    private void loadItemFiles(TreeItem<VaultNode> entry) {

        VaultNode node = entry.getValue();

        if (node.loaded) {
            return;
        }

        node.loaded = true;

        Task<List<FileVaultService.VaultFile>> task = new Task<>() {

            @Override
            protected List<FileVaultService.VaultFile> call() throws Exception {
                return service.listFiles(node.item);
            }

        };

        task.setOnSucceeded(event -> fillItem(entry, task.getValue()));

        task.setOnFailed(event -> {
            node.loaded = false;
            entry.getChildren().clear();
            status("Couldn't open " + node.name + ": " + task.getException().getMessage());
        });

        startInBackground(task);

    }

    private void fillItem(TreeItem<VaultNode> entry, List<FileVaultService.VaultFile> files) {

        ProtectedItem item = entry.getValue().item;

        Map<String, TreeItem<VaultNode>> folders = new HashMap<>();

        folders.put("", entry);

        entry.getChildren().clear();

        // Empty folders are kept in the item's details, so they show up too.
        for (String directory : item.info().directories()) {
            folderEntry(item, directory, folders);
        }

        for (FileVaultService.VaultFile file : files) {

            Path parent = Path.of(file.relativePath()).getParent();

            folderEntry(item, parent == null ? "" : parent.toString(), folders)
                    .getChildren().add(new TreeItem<>(VaultNode.file(item, file)));

        }

        for (TreeItem<VaultNode> folder : folders.values()) {
            folder.getChildren().sort(TREE_ORDER);
        }

    }

    private static TreeItem<VaultNode> folderEntry(
            ProtectedItem item, String path, Map<String, TreeItem<VaultNode>> folders) {

        TreeItem<VaultNode> existing = folders.get(path);

        if (existing != null) {
            return existing;
        }

        Path parent = Path.of(path).getParent();

        TreeItem<VaultNode> folder = new TreeItem<>(VaultNode.folder(item, path));

        folderEntry(item, parent == null ? "" : parent.toString(), folders).getChildren().add(folder);

        folders.put(path, folder);

        return folder;

    }

    @FXML
    private void refreshProtectedItems() {

        loadProtectedItems();

        status("Protected items refreshed.");

    }

    // =====================================================
    // PROTECTED ITEMS: THE PREVIEW
    // =====================================================

    private void showSelection(TreeItem<VaultNode> selected) {

        closePreview();

        if (selected == null || selected.getValue() == null) {
            showNothingSelected();
            return;
        }

        VaultNode node = selected.getValue();

        switch (node.kind) {

            case LOADING -> showMessage(node.item.name(), "", "Opening…");

            case FOLDER -> showMessage(node.name,
                    "Folder in " + node.item.name() + " · " + count(countFiles(selected), "file"),
                    "Pick a file to view it, or press Restore to put this folder back.");

            case FILE -> previewFile(node, selected);

            case ITEM -> {

                if (node.item.type() == ProtectedItem.Type.FILE) {
                    previewSingleFileItem(node, selected);
                } else {
                    showMessage(node.name, itemDetails(node.item),
                            "Open the folder to see its files, or press Restore to put all of it back.");
                }

            }

        }

    }

    /** A protected single file: its details are read first, then it opens like any file. */
    private void previewSingleFileItem(VaultNode node, TreeItem<VaultNode> selected) {

        if (node.file != null) {
            previewFile(node, selected);
            return;
        }

        showMessage(node.name, itemDetails(node.item), "Opening…");

        long generation = previewGeneration;

        Task<List<FileVaultService.VaultFile>> task = new Task<>() {

            @Override
            protected List<FileVaultService.VaultFile> call() throws Exception {
                return service.listFiles(node.item);
            }

        };

        task.setOnSucceeded(event -> {

            if (task.getValue().isEmpty()) {
                setPreviewMessage("Its encrypted copy is missing or damaged.");
                return;
            }

            node.file = task.getValue().get(0);

            if (generation == previewGeneration) {
                previewFile(node, selected);
            }

        });

        task.setOnFailed(event -> setPreviewMessage(explain(task.getException())));

        startInBackground(task);

    }

    private void previewFile(VaultNode node, TreeItem<VaultNode> treeItem) {

        FileVaultService.VaultFile file = node.file;

        previewNode = node;

        previewTreeItem = treeItem;

        previewTitle.setText(file.name());

        previewDetails.setText(DisplayFormat.size(file.size())
                + " · modified " + DisplayFormat.dateTime(file.lastModifiedMillis())
                + " · from " + parentOf(file.originalPath()));

        updatePreviewButtons();

        PreviewKind kind = PreviewKind.of(file.extension());

        if (kind == PreviewKind.NONE) {
            setPreviewMessage("There's no viewer for this kind of file. You can export a copy or restore it.");
            return;
        }

        if (file.size() > kind.sizeLimit()) {
            setPreviewMessage("This file is too large to open here (" + DisplayFormat.size(file.size())
                    + "). You can export a copy or restore it.");
            return;
        }

        setPreviewMessage("Opening…");

        long generation = ++previewGeneration;

        Task<Previews.Prepared> task = new Task<>() {

            @Override
            protected Previews.Prepared call() throws Exception {
                return Previews.prepare(kind, service, file);
            }

        };

        task.setOnSucceeded(event -> {

            Previews.Prepared prepared = task.getValue();

            // The user may have moved on while it was decrypting.
            if (generation != previewGeneration) {
                prepared.discard();
                return;
            }

            try {

                showPreview(prepared.create());

            } catch (IOException | RuntimeException e) {

                prepared.discard();
                setPreviewMessage(explain(e));

            }

        });

        task.setOnFailed(event -> {
            if (generation == previewGeneration) {
                setPreviewMessage(explain(task.getException()));
            }
        });

        startInBackground(task);

    }

    private void showPreview(FilePreview opened) {

        preview = opened;

        previewPane.getChildren().setAll(opened.view());

        viewerControls.getChildren().setAll(opened.controls());

        addPreviousAndNext();

        updatePreviewButtons();

    }

    /** "◀ Previous" and "Next ▶" step through the files in the same folder. */
    private void addPreviousAndNext() {

        if (previewTreeItem == null || previewTreeItem.getParent() == null) {
            return;
        }

        List<TreeItem<VaultNode>> files = previewTreeItem.getParent().getChildren().stream()
                .filter(entry -> entry.getValue().kind == VaultNode.Kind.FILE)
                .toList();

        int index = files.indexOf(previewTreeItem);

        if (index < 0 || files.size() < 2) {
            return;
        }

        Button previous = new Button("◀ Previous");
        previous.setDisable(index == 0);
        previous.setOnAction(event -> selectOnly(files.get(index - 1)));

        Button next = new Button("Next ▶");
        next.setDisable(index == files.size() - 1);
        next.setOnAction(event -> selectOnly(files.get(index + 1)));

        Region left = new Region();
        Region right = new Region();
        HBox.setHgrow(left, Priority.SOMETIMES);
        HBox.setHgrow(right, Priority.SOMETIMES);

        viewerControls.getChildren().add(0, previous);
        viewerControls.getChildren().add(1, left);
        viewerControls.getChildren().add(right);
        viewerControls.getChildren().add(next);

    }

    private void selectOnly(TreeItem<VaultNode> entry) {

        vaultTree.getSelectionModel().clearSelection();

        vaultTree.getSelectionModel().select(entry);

        int row = vaultTree.getRow(entry);

        if (row >= 0) {
            vaultTree.scrollTo(row);
        }

    }

    /** Disposes the open preview (wiping its decrypted data) and its full-screen windows. */
    private void closePreview() {

        // A preview still decrypting is discarded when it arrives.
        previewGeneration++;

        for (Stage window : List.copyOf(fullScreenWindows)) {
            window.close();
        }

        fullScreenWindows.clear();

        if (preview != null) {
            preview.dispose();
            preview = null;
        }

        previewNode = null;

        previewTreeItem = null;

        previewPane.getChildren().clear();

        viewerControls.getChildren().clear();

        updatePreviewButtons();

    }

    private void showNothingSelected() {

        showMessage("Nothing selected", "",
                "Select a protected item on the left. Files open here, decrypted in memory only.");

    }

    private void showMessage(String title, String details, String message) {

        previewTitle.setText(title);

        previewDetails.setText(details);

        setPreviewMessage(message);

    }

    private void setPreviewMessage(String message) {

        Label label = new Label(message);

        label.setWrapText(true);

        label.getStyleClass().add("preview-message");

        previewPane.getChildren().setAll(label);

        viewerControls.getChildren().clear();

    }

    private void updatePreviewButtons() {

        boolean file = previewNode != null && previewNode.file != null;

        exportButton.setDisable(busy || !file);

        restoreFileButton.setDisable(busy || !file);

        fullScreenButton.setDisable(preview == null);

    }

    private static String itemDetails(ProtectedItem item) {

        return (item.type() == ProtectedItem.Type.FOLDER ? "Folder · " : "File · ")
                + count(item.storedFiles(), "file") + " · " + DisplayFormat.size(item.info().totalBytes())
                + " · protected " + DisplayFormat.timestamp(item.createdAt())
                + " · from " + item.location();

    }

    private static int countFiles(TreeItem<VaultNode> entry) {

        int files = 0;

        for (TreeItem<VaultNode> child : entry.getChildren()) {
            files += child.getValue().kind == VaultNode.Kind.FILE ? 1 : countFiles(child);
        }

        return files;

    }

    private static String parentOf(String path) {

        Path parent = Path.of(path).getParent();

        return parent == null ? path : parent.toString();

    }

    private static String explain(Throwable error) {

        if (error instanceof FileDecryptor.TooLargeException) {
            return "This file is too large to open here. You can export a copy or restore it.";
        }

        if (error instanceof IllegalStateException) {
            return "The vault is locked.";
        }

        return error.getMessage() != null ? error.getMessage() : "This file couldn't be opened.";

    }

    // =====================================================
    // PROTECTED ITEMS: ACTIONS
    // =====================================================

    /** Opens the current file in its own full-screen window. Esc closes it. */
    @FXML
    private void openFullScreen() {

        if (preview == null) {
            return;
        }

        FilePreview copy = preview.fullScreenCopy();

        if (copy == null) {
            status("This file can't be shown full screen.");
            return;
        }

        Stage window = new Stage();

        window.initOwner(window());

        window.setTitle(previewTitle.getText() + " - CyberShield");

        Button close = new Button("Close (Esc)");
        close.setOnAction(event -> window.close());

        HBox bar = new HBox(8);
        bar.getStyleClass().add("viewer-controls");
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(10));
        bar.getChildren().addAll(copy.controls());
        bar.getChildren().add(close);

        BorderPane root = new BorderPane(copy.view());
        root.setBottom(bar);
        root.getStyleClass().addAll("file-security", "fullscreen-viewer");
        root.getStylesheets().add(getClass().getResource("/css/theme.css").toExternalForm());
        root.getStylesheets().add(getClass().getResource("/css/file_security.css").toExternalForm());

        Scene scene = new Scene(root, 1000, 700);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                window.close();
            }
        });

        window.setScene(scene);
        window.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        window.setFullScreenExitHint("Press Esc to close.");
        window.setOnHidden(event -> {
            copy.dispose();
            fullScreenWindows.remove(window);
        });

        AutoLock.getInstance().watch(scene);

        fullScreenWindows.add(window);

        window.setFullScreen(true);

        window.show();

    }

    /** Saves a decrypted copy wherever the user picks. The file stays protected. */
    @FXML
    private void exportCopy() {

        if (busy || previewNode == null || previewNode.file == null) {
            return;
        }

        FileVaultService.VaultFile file = previewNode.file;

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save a decrypted copy");
        chooser.setInitialFileName(file.name());

        File chosen = chooser.showSaveDialog(window());

        if (chosen == null) {
            return;
        }

        Path target = chosen.toPath().toAbsolutePath().normalize();

        if (target.startsWith(service.storeDir())) {
            status("Pick a folder outside CyberShield's hidden store.");
            return;
        }

        runJob(new JobTask<Path>("Saving a decrypted copy…") {

            @Override
            protected Path call() throws Exception {
                service.exportCopy(file, target);
                return target;
            }

        }, saved -> status("Saved a decrypted copy to " + saved + ". That copy is not protected."));

    }

    /** Puts the file in the preview back where it came from. */
    @FXML
    private void restorePreviewedFile() {

        if (busy || previewNode == null || previewNode.file == null) {
            return;
        }

        if (FileVaultService.isJobRunning()) {
            status("Another file job is still running. Wait for it to finish.");
            return;
        }

        VaultNode node = previewNode;

        closePreview();

        runJob(new JobTask<FileVaultService.RestoreResult>("Restoring…") {

            @Override
            protected FileVaultService.RestoreResult call() {
                return node.kind == VaultNode.Kind.ITEM
                        ? service.restore(List.of(node.item), listener)
                        : service.restoreFiles(node.item, List.of(node.file), listener);
            }

        }, this::finishRestore);

    }

    /**
     * Restores everything selected in the tree: whole items, folders inside
     * them, or single files.
     */
    @FXML
    private void restoreSelected() {

        if (busy) {
            return;
        }

        List<TreeItem<VaultNode>> selected = List.copyOf(vaultTree.getSelectionModel().getSelectedItems());

        if (selected.isEmpty()) {
            status("Select protected items, folders or files first.");
            return;
        }

        if (FileVaultService.isJobRunning()) {
            status("Another file job is still running. Wait for it to finish.");
            return;
        }

        List<ProtectedItem> wholeItems = new ArrayList<>();

        Set<String> wholeIds = new HashSet<>();

        for (TreeItem<VaultNode> entry : selected) {

            VaultNode node = entry.getValue();

            if (node != null && node.kind == VaultNode.Kind.ITEM) {
                wholeItems.add(node.item);
                wholeIds.add(node.item.id());
            }

        }

        // Files and folders inside items that aren't being restored whole. Keyed by
        // file id, so picking a folder and a file inside it doesn't count it twice.
        Map<ProtectedItem, Map<String, FileVaultService.VaultFile>> someFiles = new LinkedHashMap<>();

        for (TreeItem<VaultNode> entry : selected) {

            VaultNode node = entry.getValue();

            if (node == null || node.kind == VaultNode.Kind.ITEM || wholeIds.contains(node.item.id())) {
                continue;
            }

            collectFiles(entry, someFiles.computeIfAbsent(node.item, item -> new LinkedHashMap<>()));

        }

        closePreview();

        runJob(new JobTask<FileVaultService.RestoreResult>("Restoring…") {

            @Override
            protected FileVaultService.RestoreResult call() {

                FileVaultService.RestoreResult total = wholeItems.isEmpty()
                        ? new FileVaultService.RestoreResult(0, 0, 0, List.of(), false)
                        : service.restore(wholeItems, listener);

                for (Map.Entry<ProtectedItem, Map<String, FileVaultService.VaultFile>> part : someFiles.entrySet()) {

                    if (total.stoppedEarly() || part.getValue().isEmpty()) {
                        continue;
                    }

                    total = combine(total, service.restoreFiles(
                            part.getKey(), List.copyOf(part.getValue().values()), listener));

                }

                return total;

            }

        }, this::finishRestore);

    }

    private static void collectFiles(TreeItem<VaultNode> entry, Map<String, FileVaultService.VaultFile> files) {

        VaultNode node = entry.getValue();

        if (node.kind == VaultNode.Kind.FILE) {
            files.put(node.file.id(), node.file);
            return;
        }

        for (TreeItem<VaultNode> child : entry.getChildren()) {
            collectFiles(child, files);
        }

    }

    private static FileVaultService.RestoreResult combine(
            FileVaultService.RestoreResult a, FileVaultService.RestoreResult b) {

        List<String> problems = new ArrayList<>(a.problems());

        problems.addAll(b.problems());

        return new FileVaultService.RestoreResult(
                a.restoredFiles() + b.restoredFiles(),
                a.restoredItems() + b.restoredItems(),
                a.renamed() + b.renamed(),
                List.copyOf(problems),
                a.stoppedEarly() || b.stoppedEarly());

    }

    private static void startInBackground(Task<?> task) {

        Thread thread = new Thread(task, "cybershield-viewer");

        thread.setDaemon(true);

        thread.start();

    }

    private void finishRestore(FileVaultService.RestoreResult result) {

        loadProtectedItems();

        reopenCurrentFolder();

        StringBuilder text = new StringBuilder("Restored ")
                .append(count(result.restoredFiles(), "file"))
                .append(" to where they were.");

        if (result.renamed() > 0) {
            text.append(" Given a new name ending in \"(restored)\" because the old name was taken: ")
                    .append(count(result.renamed(), "file")).append('.');
        }

        appendEnding(text, result.stoppedEarly(), result.problems());

        status(text.toString());

    }

    // =====================================================
    // JOBS
    // =====================================================

    private <T> void runJob(JobTask<T> task, Consumer<T> whenDone) {

        stopRequested.set(false);

        setBusy(true);

        progressBar.progressProperty().bind(task.progressProperty());

        progressLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(event -> {
            endJob();
            whenDone.accept(task.getValue());
        });

        task.setOnFailed(event -> {
            endJob();
            Throwable error = task.getException();
            status(error instanceof CancellationException
                    ? "Stopped. Nothing was changed."
                    : "Something went wrong: " + error.getMessage());
        });

        Thread thread = new Thread(task, "cybershield-file-vault");

        // Don't keep the app running after its window closes.
        thread.setDaemon(true);

        thread.start();

    }

    private void endJob() {

        progressBar.progressProperty().unbind();

        progressLabel.textProperty().unbind();

        setBusy(false);

    }

    @FXML
    private void stopJob() {

        stopRequested.set(true);

        status("Stopping after the current step…");

    }

    private void setBusy(boolean value) {

        busy = value;

        progressBox.setVisible(value);
        progressBox.setManaged(value);

        addFilesButton.setDisable(value);
        addFolderButton.setDisable(value);
        restoreButton.setDisable(value);
        confirmButton.setDisable(value);

        updatePreviewButtons();

        refreshTray();

        fileTable.refresh();

        trayList.refresh();

    }

    // =====================================================
    // LOCK
    // =====================================================

    @FXML
    private void lockVault() {

        if (busy || FileVaultService.isJobRunning()) {
            status("Wait for the current job to finish, or stop it, before locking.");
            return;
        }

        dispose();

        vaultService.lockVault();

        onLocked.run();

    }

    // =====================================================
    // HELPERS
    // =====================================================

    /** Reopens the current folder, or its nearest parent if it was protected. */
    private void reopenCurrentFolder() {

        Path folder = currentFolder;

        while (folder != null && !Files.isDirectory(folder)) {
            folder = folder.getParent();
        }

        if (folder != null) {
            openFolder(folder);
        }

    }

    private static void appendEnding(StringBuilder text, boolean stoppedEarly, List<String> problems) {

        if (stoppedEarly) {
            text.append(" Stopped early.");
        }

        if (!problems.isEmpty()) {
            text.append(' ').append(problems.get(0));
            if (problems.size() > 1) {
                text.append(" (and ").append(problems.size() - 1).append(" more)");
            }
        }

    }

    private Window window() {
        return fileTable.getScene() == null ? null : fileTable.getScene().getWindow();
    }

    private void status(String text) {
        statusLabel.setText(text);
    }

    private static String displayName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

}
