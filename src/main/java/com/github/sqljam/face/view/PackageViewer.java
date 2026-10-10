/*
 * Copyright 2023-2026 Fred Feng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.sqljam.face.view;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.sqljam.face.service.TransferService;
import com.github.sqljam.impexp.ExportManifest;
import com.github.sqljam.impexp.ParquetExporter;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * @Description: PackageViewer shows the files of an export package (sql scripts or Parquet files) with sizes and
 *               modified times, and previews the selected file: text of sql and json files (manifest.json formatted), columns and first rows of
 *               Parquet files, text or hex of LOB files. Large files are previewed by their first part.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
public class PackageViewer {

    /**
     * Bytes of a text preview
     */
    static final int TEXT_PREVIEW_BYTES = 256 * 1024;
    /**
     * Bytes of a hex preview of binary files
     */
    static final int HEX_PREVIEW_BYTES = 4 * 1024;
    /**
     * Rows of a Parquet preview
     */
    static final int PARQUET_PREVIEW_ROWS = 100;

    private final AppContext context;
    private final File directory;
    private final Stage stage = new Stage();
    private static final DateTimeFormatter MODIFIED_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TreeTableView<File> fileTree = new TreeTableView<>();
    private final Label summaryLabel = new Label();
    private final Label fileLabel = new Label();
    private final TextArea textPreview = new TextArea();
    private final TableView<List<String>> tablePreview = new TableView<>();
    private final StackPane previewPane = new StackPane(textPreview, tablePreview);

    public PackageViewer(Window owner, AppContext context, File directory) {
        this.context = context;
        this.directory = directory;
        stage.initOwner(owner);
        Branding.applyIcons(stage);
        stage.setTitle(Messages.format("package.title", directory.getName()));

        fileTree.setId("packageFileTree");
        summaryLabel.setId("packageSummary");
        fileLabel.setId("packageFileLabel");
        textPreview.setId("packageTextPreview");
        tablePreview.setId("packageTablePreview");
        summaryLabel.getStyleClass().add("muted");
        summaryLabel.setWrapText(true);
        fileLabel.getStyleClass().add("section-title");
        textPreview.setEditable(false);
        textPreview.getStyleClass().add("mono");
        tablePreview.setPlaceholder(new Label(Messages.get("package.noRows")));
        showText("");

        fileTree.setRoot(createItem(directory));
        fileTree.getRoot().setExpanded(true);
        setupColumns();
        fileTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, item) -> {
            if (item != null && item.getValue().isFile()) {
                preview(item.getValue());
            }
        });
        summaryLabel.setText(describe(directory));

        VBox left = new VBox(8, summaryLabel, fileTree);
        VBox.setVgrow(fileTree, Priority.ALWAYS);
        left.setPadding(new Insets(8));
        VBox right = new VBox(8, fileLabel, previewPane);
        VBox.setVgrow(previewPane, Priority.ALWAYS);
        right.setPadding(new Insets(8));
        SplitPane splitPane = new SplitPane(left, right);
        splitPane.setDividerPositions(0.32);
        BorderPane root = new BorderPane(splitPane);
        root.setId("packageViewer");
        Scene scene = new Scene(root, 1100, 680);
        scene.getStylesheets().add(PackageViewer.class.getResource("/com/github/sqljam/face/app.css")
                .toExternalForm());
        stage.setScene(scene);
    }

    /**
     * Name (with icon), size and modified time of files
     */
    private void setupColumns() {
        TreeTableColumn<File, File> nameColumn = new TreeTableColumn<>(Messages.get("package.column.name"));
        nameColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().getValue()));
        nameColumn.setCellFactory(column -> new TreeTableCell<>() {
            @Override
            protected void updateItem(File item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getName());
                    setGraphic(Icons.of(item.isDirectory() ? Icons.FOLDER
                            : isParquet(item) ? Icons.TABLE : "fth-file-text"));
                }
            }
        });
        nameColumn.setPrefWidth(200);
        TreeTableColumn<File, String> sizeColumn = new TreeTableColumn<>(Messages.get("package.column.size"));
        sizeColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(formatSize(cell.getValue().getValue())));
        sizeColumn.setPrefWidth(80);
        sizeColumn.setStyle("-fx-alignment: CENTER-RIGHT;");
        TreeTableColumn<File, String> modifiedColumn = new TreeTableColumn<>(
                Messages.get("package.column.modified"));
        modifiedColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(
                formatModified(cell.getValue().getValue())));
        modifiedColumn.setPrefWidth(140);
        fileTree.getColumns().setAll(List.of(nameColumn, sizeColumn, modifiedColumn));
        fileTree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY);
    }

    /**
     * Size of a file, or of all files of a directory
     */
    static String formatSize(File file) {
        long size = file.isDirectory() ? FileUtils.sizeOfDirectory(file) : file.length();
        return FileUtils.byteCountToDisplaySize(size);
    }

    static String formatModified(File file) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(file.lastModified()),
                ZoneId.systemDefault()).format(MODIFIED_FORMATTER);
    }

    public void show() {
        stage.show();
        // manifest.json is previewed first
        selectFile(new File(directory, ExportManifest.FILE_NAME));
    }

    public Stage getStage() {
        return stage;
    }

    /**
     * Selects the file in the tree and previews it
     */
    public boolean selectFile(File file) {
        TreeItem<File> item = findItem(fileTree.getRoot(), file);
        if (item != null) {
            for (TreeItem<File> parent = item.getParent(); parent != null; parent = parent.getParent()) {
                parent.setExpanded(true);
            }
            fileTree.getSelectionModel().select(item);
            return true;
        }
        return false;
    }

    private static TreeItem<File> findItem(TreeItem<File> item, File file) {
        if (item.getValue().equals(file)) {
            return item;
        }
        for (TreeItem<File> child : item.getChildren()) {
            TreeItem<File> found = findItem(child, file);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Files of the package, directories first, temporary workspaces are skipped
     */
    static TreeItem<File> createItem(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles(child -> !child.getName().startsWith(".sqljam-workspace-")
                    && !child.isHidden());
            if (children != null) {
                Arrays.stream(children).sorted(Comparator.comparing((File child) -> !child.isDirectory())
                        .thenComparing(File::getName)).forEach(child -> item.getChildren().add(createItem(child)));
            }
        }
        return item;
    }

    /**
     * Summary of the package by its manifest: data format, source, target, tables and rows
     */
    static String describe(File directory) {
        try {
            ExportManifest manifest = ExportManifest.read(directory);
            if (manifest == null) {
                return Messages.get("package.noManifest");
            }
            long rows = manifest.getTables().stream().mapToLong(ExportManifest.TableEntry::getRows).sum();
            return Messages.format("package.summary", manifest.getDataFormat(), manifest.getStatus(),
                    manifest.getSource().getDbType(), manifest.getTarget().getDbType(), manifest.getTables().size(),
                    rows);
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private void preview(File file) {
        fileLabel.setText(String.format("%s  (%s, %s)", file.getName(), formatSize(file), formatModified(file)));
        if (isParquet(file)) {
            showText(Messages.get("package.loading"));
            TaskRunner.run(() -> context.getTransferService().previewParquet(List.of(file), PARQUET_PREVIEW_ROWS),
                    this::showParquet, e -> showText(e.getMessage()));
            return;
        }
        try {
            showText(readPreview(file));
        } catch (IOException e) {
            showText(e.getMessage());
        }
    }

    private void showText(String text) {
        textPreview.setText(text);
        textPreview.setVisible(true);
        tablePreview.setVisible(false);
    }

    private void showParquet(TransferService.ParquetPreview preview) {
        tablePreview.getColumns().clear();
        List<String[]> columns = preview.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            int index = i;
            TableColumn<List<String>, String> column = new TableColumn<>(columns.get(i)[0] + "\n" + columns.get(i)[1]);
            column.setCellValueFactory(cell -> new SimpleStringProperty(index < cell.getValue().size()
                    ? cell.getValue().get(index) : null));
            column.setPrefWidth(140);
            tablePreview.getColumns().add(column);
        }
        tablePreview.getItems().setAll(preview.getFirstRows());
        fileLabel.setText(fileLabel.getText() + "  " + Messages.format("package.parquetRows", preview.getRows(),
                preview.getFirstRows().size()));
        textPreview.setVisible(false);
        tablePreview.setVisible(true);
    }

    static boolean isParquet(File file) {
        return file.getName().toLowerCase(Locale.ENGLISH).endsWith(ParquetExporter.PARQUET_EXTENSION);
    }

    /**
     * Preview of a non Parquet file: manifest json formatted, text files, hex of binary files
     */
    static String readPreview(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ENGLISH);
        if (name.endsWith(".json") && file.length() <= TEXT_PREVIEW_BYTES) {
            ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            try {
                return mapper.writeValueAsString(mapper.readTree(file));
            } catch (IOException e) {
                return readText(file, TEXT_PREVIEW_BYTES);
            }
        }
        if (name.endsWith(".blob")) {
            return hexPreview(file, HEX_PREVIEW_BYTES);
        }
        return readText(file, TEXT_PREVIEW_BYTES);
    }

    /**
     * Text of the first bytes of a file, a notice is appended if the file is larger
     */
    static String readText(File file, int maxBytes) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            bytes = in.readNBytes(maxBytes);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (file.length() > maxBytes) {
            text += "\n\n" + Messages.format("package.truncated", FileUtils.byteCountToDisplaySize(maxBytes),
                    FileUtils.byteCountToDisplaySize(file.length()));
        }
        return text;
    }

    /**
     * Hex dump of the first bytes of a binary file, 16 bytes per line
     */
    static String hexPreview(File file, int maxBytes) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            bytes = in.readNBytes(maxBytes);
        }
        StringBuilder hex = new StringBuilder();
        for (int offset = 0; offset < bytes.length; offset += 16) {
            hex.append(String.format("%08x  ", offset));
            List<String> parts = new ArrayList<>();
            for (int i = offset; i < Math.min(offset + 16, bytes.length); i++) {
                parts.add(String.format("%02x", bytes[i]));
            }
            hex.append(StringUtils.join(parts, ' ')).append('\n');
        }
        if (file.length() > maxBytes) {
            hex.append('\n').append(Messages.format("package.truncated", FileUtils.byteCountToDisplaySize(maxBytes),
                    FileUtils.byteCountToDisplaySize(file.length())));
        }
        return hex.toString();
    }
}
