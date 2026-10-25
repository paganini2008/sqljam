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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.face.service.DatabaseSession;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DataFormat;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportListener;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.IdentifierCase;
import com.github.sqljam.impexp.ImpExpException;
import com.github.sqljam.impexp.TableQuery;
import com.github.sqljam.impexp.WriteMode;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @Description: ExportDialogTest verifies table selection, options, input bounds and the transfer request built
 *               by the export wizard
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ExportDialogTest {

    @TempDir
    File dir;

    private AppContext context;
    private Stage stage;
    private ConnectionProfile profile;
    private ExportDialog dialog;

    @Start
    void start(Stage stage) throws Exception {
        this.stage = stage;
        context = FxTestSupport.createContext(dir);
        profile = UiDatabase.create(dir, "Embedded H2");
        context.getProfileRegistry().saveProfile(profile, true);
        stage.setScene(new Scene(new StackPane(), 300, 200));
        stage.show();
    }

    @AfterEach
    void close() {
        if (dialog != null) {
            FxTestSupport.run(dialog::close);
        }
        context.getSessionManager().closeAll();
    }

    private ExportDialog open(DbNode node) {
        dialog = FxTestSupport.call(() -> {
            ExportDialog exportDialog = new ExportDialog(stage, context, node);
            exportDialog.show();
            exportDialog.getDialogPane().applyCss();
            exportDialog.getDialogPane().layout();
            return exportDialog;
        });
        FxTestSupport.waitUntil(() -> list().getItems().size() == 3);
        return dialog;
    }

    private DbNode schemaNode() {
        return DbNode.schema(profile, UiDatabase.CATALOG, UiDatabase.SCHEMA);
    }

    private DbNode tableNode(String table) {
        return DbNode.table(profile, new TableInfo(UiDatabase.CATALOG, UiDatabase.SCHEMA, table, null, false,
                false));
    }

    @SuppressWarnings("unchecked")
    private <T extends Node> T node(String id) {
        return (T) dialog.getDialogPane().lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    private ListView<Object> list() {
        return (ListView<Object>) dialog.getDialogPane().lookup("#tableList");
    }

    private void setText(String id, String text) {
        FxTestSupport.run(() -> ((TextField) node(id)).setText(text));
    }

    @SuppressWarnings("unchecked")
    private void setSpinnerText(String id, String text) {
        FxTestSupport.run(() -> ((Spinner<Integer>) node(id)).getEditor().setText(text));
    }

    @SuppressWarnings("unchecked")
    private void setVersion(String text) {
        FxTestSupport.run(() -> ((ComboBox<String>) node("scriptVersionCombo")).getEditor().setText(text));
    }

    private boolean startDisabled() {
        return FxTestSupport.call(() -> dialog.getDialogPane().lookupButton(ButtonType.OK).isDisabled());
    }

    private String error() {
        return FxTestSupport.call(() -> ((Label) node("errorLabel")).getText());
    }

    /**
     * Whether the node and all of its parents are visible
     */
    private boolean shown(String id) {
        return FxTestSupport.call(() -> {
            for (javafx.scene.Node node = node(id); node != null; node = node.getParent()) {
                if (!node.isVisible()) {
                    return false;
                }
            }
            return true;
        });
    }

    private TransferRequest build() {
        return FxTestSupport.call(dialog::buildRequest);
    }

    private void fire(String id) {
        FxTestSupport.run(() -> ((Button) node(id)).fire());
    }

    @Test
    void allTablesMeanWholeSchema() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        assertFalse(startDisabled(), error());
        assertEquals("3 / 3 selected", FxTestSupport.call(() -> ((Label) node("tableCountLabel")).getText()));
        TransferRequest request = build();
        assertTrue(request.getTables().isEmpty());
        assertEquals(UiDatabase.CATALOG, request.getSourceCatalog());
        assertEquals(UiDatabase.SCHEMA, request.getSourceSchema());
        assertEquals(profile.getId(), request.getSource().getId());
        assertEquals(TransferRequest.Target.SCRIPT, request.getTarget());
        assertEquals(DbType.H2, request.getScriptDbType());
        assertEquals(ExportMode.DDL_DATA, request.getExportMode());
        assertEquals(10L * 1024 * 1024, request.getMaxFileSize());
    }

    @Test
    void preselectsTable() {
        open(tableNode("T_ONE"));
        assertEquals("1 / 3 selected", FxTestSupport.call(() -> ((Label) node("tableCountLabel")).getText()));
        setText("directoryField", dir.getAbsolutePath());
        assertEquals(List.of("T_ONE"), build().getTables());
    }

    /**
     * Selecting the source starts a loading of all tables, it must not override the preselected table
     */
    @Test
    void keepsPreselectedTableAcrossConcurrentLoadings() {
        for (int i = 0; i < 5; i++) {
            open(tableNode("T_PAGED"));
            FxTestSupport.waitUntil(() -> ((Label) node("tableCountLabel")).getText().equals("1 / 3 selected"));
            // Late results of stale loadings are discarded
            FxTestSupport.run(() -> {
            });
            assertEquals("1 / 3 selected", FxTestSupport.call(() -> ((Label) node("tableCountLabel")).getText()));
            FxTestSupport.run(dialog::close);
        }
    }

    @Test
    void requiresSelectedTables() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        fire("selectNoneButton");
        assertTrue(startDisabled());
        assertEquals("Please select at least one table", error());
        fire("selectAllButton");
        assertFalse(startDisabled());
        assertEquals("", error());
    }

    @Test
    void filtersTables() {
        open(schemaNode());
        setText("tableFilterField", "one");
        assertEquals(1, FxTestSupport.call(() -> list().getItems().size()));
        // Select none applies to the filtered tables only
        fire("selectNoneButton");
        setText("tableFilterField", "");
        assertEquals(3, FxTestSupport.call(() -> list().getItems().size()));
        assertEquals("2 / 3 selected", FxTestSupport.call(() -> ((Label) node("tableCountLabel")).getText()));
        setText("directoryField", dir.getAbsolutePath());
        assertEquals(List.of("T_EMPTY", "T_PAGED"), build().getTables());
    }

    @Test
    void mapsContentModes() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        FxTestSupport.run(() -> ((RadioButton) node("ddlRadio")).setSelected(true));
        assertEquals(ExportMode.DDL, build().getExportMode());
        FxTestSupport.run(() -> ((RadioButton) node("dataRadio")).setSelected(true));
        assertEquals(ExportMode.DATA, build().getExportMode());
    }

    @Test
    void requiresValidDirectory() throws Exception {
        open(schemaNode());
        setText("directoryField", "  ");
        assertTrue(startDisabled());
        assertEquals("Please choose a valid output directory", error());
        File file = new File(dir, "file.txt");
        Files.writeString(file.toPath(), "x");
        setText("directoryField", file.getAbsolutePath());
        assertTrue(startDisabled());
        // A directory which does not exist yet is created by the export
        setText("directoryField", new File(dir, "new/out").getAbsolutePath());
        assertFalse(startDisabled());
    }

    @Test
    void validatesMaxFileSize() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        for (String invalid : new String[]{"-5", "abc", "", "99999999999", "100001", "1.5"}) {
            setSpinnerText("maxFileSizeSpinner", invalid);
            assertTrue(startDisabled(), invalid);
            assertEquals("Max file size must be a number between 0 and 100000 MB", error(), invalid);
        }
        setSpinnerText("maxFileSizeSpinner", "0");
        assertFalse(startDisabled());
        assertEquals(0, build().getMaxFileSize());
        setSpinnerText("maxFileSizeSpinner", "100000");
        assertEquals(100000L * 1024 * 1024, build().getMaxFileSize());
        // Max file size of database target is not validated
        setSpinnerText("maxFileSizeSpinner", "abc");
        FxTestSupport.run(() -> {
            ((RadioButton) node("databaseRadio")).setSelected(true);
            @SuppressWarnings("unchecked")
            ComboBox<ConnectionProfile> targetCombo = (ComboBox<ConnectionProfile>) node("targetCombo");
            targetCombo.setValue(targetCombo.getItems().get(0));
        });
        @SuppressWarnings("unchecked")
        ComboBox<String> targetSchema = (ComboBox<String>) node("targetSchemaCombo");
        FxTestSupport.waitUntil(() -> !targetSchema.getItems().isEmpty());
        FxTestSupport.run(() -> targetSchema.getEditor().setText("COPY"));
        assertFalse(startDisabled(), error());
    }

    @Test
    void validatesPageSize() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        for (String invalid : new String[]{"9", "0", "-1", "100001", "x"}) {
            setSpinnerText("pageSizeSpinner", invalid);
            assertTrue(startDisabled(), invalid);
            assertEquals("Rows per batch must be a number between 10 and 100000", error());
        }
        setSpinnerText("pageSizeSpinner", "10");
        assertEquals(10, build().getPageSize());
        setSpinnerText("pageSizeSpinner", "100000");
        assertEquals(100000, build().getPageSize());
    }

    @Test
    void validatesVersion() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        setVersion("abc");
        assertTrue(startDisabled());
        assertEquals("Invalid target version: abc, e.g. 11.2 or 2019", error());
        setVersion("11.2.0.4");
        assertTrue(startDisabled());
        setVersion("11.2");
        assertFalse(startDisabled());
        assertEquals("11.2", build().getScriptDbVersion());
        setVersion("2019");
        assertEquals("2019", build().getScriptDbVersion());
        setVersion("  ");
        assertNull(build().getScriptDbVersion());
    }

    @Test
    void mapsOptions() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        FxTestSupport.run(() -> {
            for (String id : new String[]{"recreateCheck", "idReusedCheck", "indexCheck", "foreignKeyCheck",
                    "commentCheck", "sequenceCheck", "failFastCheck", "lobSeparatedCheck"}) {
                ((CheckBox) node(id)).setSelected(false);
            }
            ((RadioButton) node("perTableRadio")).setSelected(true);
            @SuppressWarnings("unchecked")
            ComboBox<IdentifierCase> identifierCase = (ComboBox<IdentifierCase>) node("identifierCaseCombo");
            identifierCase.setValue(IdentifierCase.UPPER);
            @SuppressWarnings("unchecked")
            ComboBox<DbType> dbType = (ComboBox<DbType>) node("scriptDbTypeCombo");
            dbType.setValue(DbType.ORACLE);
            ((TextField) node("scriptSchemaField")).setText("APP");
        });
        String hint = FxTestSupport.call(() -> ((Label) node("layoutHint")).getText());
        assertTrue(hint.contains("data/<table>.sql, <table>_2.sql"), hint);
        assertFalse(hint.contains("lob-manifest.json"), hint);
        TransferRequest request = build();
        assertFalse(request.isTableRecreated());
        assertFalse(request.isIdReused());
        assertFalse(request.isIndexIncluded());
        assertFalse(request.isForeignKeyIncluded());
        assertFalse(request.isCommentIncluded());
        assertFalse(request.isSequenceIncluded());
        assertFalse(request.isFailFast());
        assertFalse(request.isLobSeparated());
        assertEquals(DataFileStrategy.FILE_PER_TABLE, request.getDataFileStrategy());
        assertEquals(IdentifierCase.UPPER, request.getIdentifierCase());
        assertEquals(DbType.ORACLE, request.getScriptDbType());
        assertEquals("APP", request.getScriptSchema());
    }

    @Test
    void showsLayoutHintOfSingleFile() {
        open(schemaNode());
        setSpinnerText("maxFileSizeSpinner", "0");
        String hint = FxTestSupport.call(() -> ((Label) node("layoutHint")).getText());
        assertTrue(hint.contains("data.sql"), hint);
        assertTrue(hint.contains("manifest.json"), hint);
    }

    /**
     * Columns are mapped for imports into a database only, the mappings go with the request
     */
    @Test
    void mapsColumnsOfDatabaseTargets() {
        open(schemaNode());
        // Packages are written as they are
        assertFalse(shown("mapColumnsButton"));
        FxTestSupport.run(() -> ((RadioButton) node("databaseRadio")).setSelected(true));
        assertEquals("All columns are imported as they are", FxTestSupport.call(() -> ((Label) node("mappingLabel"))
                .getText()));
        assertTrue(build().getColumnMappings().isEmpty());
        assertTrue(shown("mapColumnsButton"));
        FxTestSupport.fireLater((Button) node("mapColumnsButton"));
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().stream().anyMatch(pane -> pane.lookup(
                "#mappingColumnTable") != null));
        javafx.scene.control.DialogPane mapping = FxTestSupport.getDialogPanes().stream().filter(pane -> pane
                .lookup("#mappingColumnTable") != null).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        javafx.scene.control.TableView<ColumnMappingDialog.Row> columns = (javafx.scene.control.TableView<
                ColumnMappingDialog.Row>) mapping.lookup("#mappingColumnTable");
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> !columns.getItems().isEmpty()));
        FxTestSupport.run(() -> {
            columns.getItems().get(0).targetName.set("MAPPED_FIRST");
            ((Button) mapping.lookupButton(ButtonType.OK)).fire();
        });
        FxTestSupport.waitUntil(() -> FxTestSupport.call(() -> ((Label) node("mappingLabel")).getText()
                .equals("1 columns are mapped")));
        assertTrue(build().getColumnMappings().values().stream().anyMatch(columnsOfTable -> columnsOfTable.values()
                .stream().anyMatch(columnMapping -> "MAPPED_FIRST".equals(columnMapping.getTargetName()))));
    }

    @Test
    void allowsSameDataSourceAsTarget() {
        open(schemaNode());
        FxTestSupport.run(() -> ((RadioButton) node("databaseRadio")).setSelected(true));
        assertTrue(startDisabled());
        assertEquals("Please choose a target data source", error());
        @SuppressWarnings("unchecked")
        ComboBox<ConnectionProfile> targetCombo = (ComboBox<ConnectionProfile>) node("targetCombo");
        // The same list of data sources, the source itself can be the target
        assertEquals(1, FxTestSupport.call(() -> targetCombo.getItems().size()));
        FxTestSupport.run(() -> targetCombo.setValue(targetCombo.getItems().get(0)));
        @SuppressWarnings("unchecked")
        ComboBox<String> targetSchema = (ComboBox<String>) node("targetSchemaCombo");
        FxTestSupport.waitUntil(() -> !targetSchema.getItems().isEmpty());
        FxTestSupport.run(() -> targetSchema.getEditor().setText("COPY"));
        assertFalse(startDisabled());
        TransferRequest request = build();
        assertEquals(TransferRequest.Target.DATABASE, request.getTarget());
        assertEquals(profile.getId(), request.getTargetProfile().getId());
        assertEquals(UiDatabase.CATALOG, request.getTargetCatalog());
        assertEquals("COPY", request.getTargetSchema());
        assertTrue(request.isTargetSchemaCreated());
    }

    @Test
    void persistsSettings() throws Exception {
        open(schemaNode());
        File out = new File(dir, "out");
        setText("directoryField", out.getAbsolutePath());
        setSpinnerText("pageSizeSpinner", "250");
        setSpinnerText("maxFileSizeSpinner", "20");
        build();
        String config = Files.readString(new File(dir, Config.FILE_NAME).toPath());
        assertTrue(config.contains("sqljam.export.page-size=250"), config);
        assertTrue(config.contains("sqljam.export.max-file-size=" + 20L * 1024 * 1024), config);
        assertEquals(out.getAbsolutePath(), context.getSettings().getLastExportDirectory());
    }

    @Test
    void requiresSourceDataSource() throws Exception {
        context.getProfileRegistry().removeProfile(profile.getId());
        dialog = FxTestSupport.call(() -> {
            ExportDialog exportDialog = new ExportDialog(stage, context, null);
            exportDialog.show();
            return exportDialog;
        });
        assertTrue(startDisabled());
        assertEquals("Please choose a source data source", error());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsParquetFormat() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        // SQL is the default format, compression is only for Parquet
        assertTrue(FxTestSupport.call(() -> ((RadioButton) node("sqlFormatRadio")).isSelected()));
        assertTrue(FxTestSupport.call(() -> node("compressionCombo").isDisabled()));
        assertEquals(DataFormat.SQL, build().getDataFormat());
        FxTestSupport.run(() -> {
            ((RadioButton) node("parquetFormatRadio")).setSelected(true);
            ((ComboBox<String>) node("compressionCombo")).setValue("SNAPPY");
        });
        assertFalse(FxTestSupport.call(() -> node("compressionCombo").isDisabled()));
        // Options of sql data files are disabled, large tables are split into Parquet parts
        for (String id : new String[]{"singleFileRadio", "perTableRadio", "lobSeparatedCheck", "csvDelimiterCombo"}) {
            assertTrue(FxTestSupport.call(() -> node(id).isDisabled()), id);
        }
        assertFalse(FxTestSupport.call(() -> node("maxFileSizeSpinner").isDisabled()));
        String hint = FxTestSupport.call(() -> ((Label) node("layoutHint")).getText());
        assertEquals("schema.sql\ndata/<table>.parquet, <table>_2.parquet ...\nconstraints.sql\nmanifest.json", hint);
        assertEquals(List.of("ZSTD", "SNAPPY", "GZIP", "UNCOMPRESSED"),
                FxTestSupport.call(() -> List.copyOf(((ComboBox<String>) node("compressionCombo")).getItems())));
        assertFalse(startDisabled(), error());
        TransferRequest request = build();
        assertEquals(DataFormat.PARQUET, request.getDataFormat());
        assertEquals("SNAPPY", request.getCompression());
        // Back to SQL
        FxTestSupport.run(() -> ((RadioButton) node("sqlFormatRadio")).setSelected(true));
        assertFalse(FxTestSupport.call(() -> node("perTableRadio").isDisabled()));
        assertEquals(DataFormat.SQL, build().getDataFormat());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsCsvFormat() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        assertTrue(FxTestSupport.call(() -> node("csvDelimiterCombo").isDisabled()));
        FxTestSupport.run(() -> ((RadioButton) node("csvFormatRadio")).setSelected(true));
        ComboBox<String> delimiter = node("csvDelimiterCombo");
        assertFalse(FxTestSupport.call(delimiter::isDisabled));
        for (String id : new String[]{"singleFileRadio", "perTableRadio", "lobSeparatedCheck", "compressionCombo"}) {
            assertTrue(FxTestSupport.call(() -> node(id).isDisabled()), id);
        }
        assertFalse(FxTestSupport.call(() -> node("maxFileSizeSpinner").isDisabled()));
        assertEquals("schema.sql\ndata/<table>.csv, <table>_2.csv ...\nconstraints.sql\nmanifest.json",
                FxTestSupport.call(() -> ((Label) node("layoutHint")).getText()));
        // Delimiters by name, a tab has no visible character
        assertEquals(",", FxTestSupport.call(delimiter::getValue));
        assertEquals(List.of("Comma ( , )", "Semicolon ( ; )", "Tab", "Pipe ( | )"), FxTestSupport.call(() ->
                delimiter.getItems().stream().map(item -> delimiter.getConverter().toString(item))
                        .collect(Collectors.toList())));
        assertEquals("", ExportDialog.delimiterName(null));
        assertEquals("#", ExportDialog.delimiterName("#"));
        FxTestSupport.run(() -> delimiter.setValue("\t"));
        assertFalse(startDisabled(), error());
        TransferRequest request = build();
        assertEquals(DataFormat.CSV, request.getDataFormat());
        assertEquals("\t", request.getCsvDelimiter());
        assertEquals(10L * 1024 * 1024, request.getMaxFileSize());
        // The delimiter is the default of the next export
        assertEquals("\t", context.getSettings().getCsvDelimiter());
        ExportDialog next = open(schemaNode());
        assertEquals("\t", FxTestSupport.call(() -> ((ComboBox<String>) node("csvDelimiterCombo")).getValue()));
        FxTestSupport.run(next::close);
    }

    /**
     * Parquet files are split at their own size, sql scripts and CSV files share one
     */
    @Test
    void keepsMaxFileSizeOfEachFormat() {
        open(schemaNode());
        setText("directoryField", dir.getAbsolutePath());
        Spinner<Integer> size = node("maxFileSizeSpinner");
        assertEquals(10, FxTestSupport.call(size::getValue).intValue());
        setSpinnerText("maxFileSizeSpinner", "20");
        FxTestSupport.run(() -> ((RadioButton) node("parquetFormatRadio")).setSelected(true));
        assertEquals(256, FxTestSupport.call(size::getValue).intValue());
        setSpinnerText("maxFileSizeSpinner", "512");
        FxTestSupport.run(() -> ((RadioButton) node("csvFormatRadio")).setSelected(true));
        assertEquals(20, FxTestSupport.call(size::getValue).intValue());
        FxTestSupport.run(() -> ((RadioButton) node("sqlFormatRadio")).setSelected(true));
        assertEquals(20, FxTestSupport.call(size::getValue).intValue());
        FxTestSupport.run(() -> ((RadioButton) node("parquetFormatRadio")).setSelected(true));
        assertEquals(512, FxTestSupport.call(size::getValue).intValue());
        assertEquals(512L * 1024 * 1024, build().getMaxFileSize());
        assertEquals(512L * 1024 * 1024, context.getSettings().getParquetMaxFileSize());
        // Text files keep their size
        assertEquals(10L * 1024 * 1024, context.getSettings().getMaxDataFileSize());
    }

    @SuppressWarnings("unchecked")
    private void chooseTarget() {
        FxTestSupport.run(() -> ((RadioButton) node("databaseRadio")).setSelected(true));
        ComboBox<ConnectionProfile> targetCombo = (ComboBox<ConnectionProfile>) node("targetCombo");
        FxTestSupport.run(() -> targetCombo.setValue(targetCombo.getItems().get(0)));
        ComboBox<String> targetSchema = (ComboBox<String>) node("targetSchemaCombo");
        FxTestSupport.waitUntil(() -> !targetSchema.getItems().isEmpty());
        FxTestSupport.run(() -> targetSchema.getEditor().setText("COPY"));
    }

    @Test
    void importsInOneTransactionByDefault() {
        open(schemaNode());
        chooseTarget();
        RadioButton one = node("oneTransactionRadio");
        RadioButton every = node("commitEveryRadio");
        CheckBox confirm = node("confirmCommitCheck");
        CheckBox skipErrors = node("skipErrorsCheck");
        CheckBox failFast = node("failFastCheck");
        assertTrue(FxTestSupport.call(one::isSelected));
        assertFalse(FxTestSupport.call(confirm::isSelected));
        assertFalse(FxTestSupport.call(confirm::isDisabled));
        // The first error rolls back everything, stop on first error of file exports is hidden
        assertFalse(FxTestSupport.call(skipErrors::isSelected));
        assertFalse(FxTestSupport.call(failFast::isVisible));
        // Commits every N rows follow the page size, rows are committed page by page
        Spinner<Integer> commitRows = node("commitRowsSpinner");
        assertEquals(5000, FxTestSupport.call(commitRows::getValue));
        setSpinnerText("pageSizeSpinner", "1000");
        assertEquals("1000", FxTestSupport.call(() -> commitRows.getEditor().getText()));
        // Text which is not a number keeps the last rows
        setSpinnerText("pageSizeSpinner", "abc");
        assertEquals("1000", FxTestSupport.call(() -> commitRows.getEditor().getText()));
        setSpinnerText("pageSizeSpinner", "1000");
        TransferRequest request = build();
        assertTrue(request.isOneTransaction());
        assertFalse(request.isConfirmCommit());
        assertTrue(request.isFailFast());
        FxTestSupport.run(() -> confirm.setSelected(true));
        assertTrue(build().isConfirmCommit());
        // Rows with errors may be skipped in one transaction
        FxTestSupport.run(() -> skipErrors.setSelected(true));
        assertFalse(build().isFailFast());
        // Commits every N rows have no confirm, rows with errors may still be skipped
        FxTestSupport.run(() -> every.setSelected(true));
        assertTrue(FxTestSupport.call(() -> confirm.isDisabled() && !confirm.isSelected()));
        assertFalse(FxTestSupport.call(skipErrors::isDisabled));
        // Rows per commit typed in the option are the page size
        FxTestSupport.run(() -> commitRows.getValueFactory().setValue(2000));
        assertEquals(2000, FxTestSupport.call(() -> ((Spinner<?>) node("pageSizeSpinner")).getValue()));
        FxTestSupport.run(() -> commitRows.getEditor().setText("300"));
        request = build();
        assertEquals(300, request.getPageSize());
        assertFalse(request.isOneTransaction());
        assertFalse(request.isConfirmCommit());
        assertFalse(request.isFailFast());
        // Back to a script target: stop on first error is shown again
        FxTestSupport.run(() -> ((RadioButton) node("scriptRadio")).setSelected(true));
        assertTrue(FxTestSupport.call(failFast::isVisible));
        assertTrue(build().isFailFast());
    }

    @Test
    void offersNoTransactionWithoutTransactions() {
        ObjectProperty<ConnectionProfile> target = new SimpleObjectProperty<>();
        TransactionOption option = FxTestSupport.call(() -> new TransactionOption(context, target, 5000));
        assertTrue(option.isOneTransaction());
        assertFalse(option.isSkipErrors());
        ConnectionProfile clickHouse = new ConnectionProfile();
        clickHouse.setDbType(DbType.CLICKHOUSE);
        FxTestSupport.run(() -> target.set(clickHouse));
        assertFalse(option.isOneTransaction());
        assertFalse(option.isConfirmCommit());
        Label unsupported = (Label) FxTestSupport.call(() -> option.getNode().lookup("#transactionUnsupportedLabel"));
        assertTrue(unsupported.isVisible());
        assertEquals("ClickHouse has no transactions, rows are committed as they are written", unsupported.getText());
        // The choice comes back with a database with transactions
        ConnectionProfile postgresql = new ConnectionProfile();
        postgresql.setDbType(DbType.POSTGRESQL);
        FxTestSupport.run(() -> target.set(postgresql));
        assertTrue(option.isOneTransaction());
        assertFalse(unsupported.isVisible());
        // Commits every N rows by the settings
        context.getSettings().setOneTransaction(false);
        TransactionOption every = FxTestSupport.call(() -> new TransactionOption(context, target, 100));
        assertFalse(every.isOneTransaction());
    }

    @Test
    void upsertsKeptTablesByKey() {
        open(schemaNode());
        chooseTarget();
        RadioButton insert = node("insertRadio");
        RadioButton upsert = node("upsertRadio");
        CheckBox recreate = node("recreateCheck");
        Button keys = node("keyColumnsButton");
        // Insert is the default
        assertTrue(FxTestSupport.call(insert::isSelected));
        assertTrue(FxTestSupport.call(keys::isDisabled));
        assertEquals(WriteMode.INSERT, build().getWriteMode());
        // Upserts keep the tables
        FxTestSupport.run(() -> upsert.setSelected(true));
        assertTrue(FxTestSupport.call(() -> !recreate.isSelected() && recreate.isDisabled()));
        assertFalse(FxTestSupport.call(keys::isDisabled));
        // Key columns of the selected tables, an empty field is the primary key
        FxTestSupport.fireLater(keys);
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().size() == 2);
        DialogPane pane = FxTestSupport.getDialogPanes().stream().filter(item -> item.lookup("#keyColumnsGrid")
                != null).findFirst().orElseThrow();
        // Chosen columns should be a unique index of the target table
        assertTrue(FxTestSupport.call(() -> ((Label) pane.lookup("#keyColumnsUniqueHint")).getText()).startsWith(
                "Choose the columns of a unique index or the primary key of the target table"));
        TextField one = FxTestSupport.call(() -> (TextField) pane.lookup("#keyColumns-T_ONE"));
        FxTestSupport.waitUntil(() -> one.getPromptText().startsWith("Primary key"));
        assertEquals("Primary key: ID", FxTestSupport.call(one::getPromptText));
        Button ok = FxTestSupport.call(() -> (Button) pane.lookupButton(ButtonType.OK));
        FxTestSupport.run(() -> one.setText("MISSING"));
        assertEquals("MISSING is not a column of T_ONE", FxTestSupport.call(() -> ((Label) pane
                .lookup("#keyColumnsError")).getText()));
        assertTrue(FxTestSupport.call(ok::isDisabled));
        FxTestSupport.run(() -> one.setText(" name , id "));
        assertFalse(FxTestSupport.call(ok::isDisabled));
        FxTestSupport.fireLater(ok);
        FxTestSupport.waitUntil(() -> FxTestSupport.getDialogPanes().size() == 1);
        TransferRequest request = build();
        assertEquals(WriteMode.UPSERT, request.getWriteMode());
        assertFalse(request.isTableRecreated());
        assertEquals(Map.of("T_ONE", List.of("name", "id")), request.getKeyColumnNames());
        // Back to inserts: tables may be recreated again, keys are not used
        FxTestSupport.run(() -> insert.setSelected(true));
        assertFalse(FxTestSupport.call(recreate::isDisabled));
        assertEquals(Map.of(), build().getKeyColumnNames());
        // Existing rows are skipped by the same keys
        FxTestSupport.run(() -> ((RadioButton) node("skipExistingRadio")).setSelected(true));
        assertTrue(FxTestSupport.call(() -> !recreate.isSelected() && recreate.isDisabled()));
        assertFalse(FxTestSupport.call(keys::isDisabled));
        request = build();
        assertEquals(WriteMode.SKIP_EXISTING, request.getWriteMode());
        assertEquals(Map.of("T_ONE", List.of("name", "id")), request.getKeyColumnNames());
    }

    @Test
    void offersNoUpsertForClickHouse() {
        open(schemaNode());
        @SuppressWarnings("unchecked")
        ComboBox<DbType> dbType = (ComboBox<DbType>) node("scriptDbTypeCombo");
        RadioButton upsert = node("upsertRadio");
        RadioButton skip = node("skipExistingRadio");
        FxTestSupport.run(() -> skip.setSelected(true));
        FxTestSupport.run(() -> dbType.setValue(DbType.CLICKHOUSE));
        assertTrue(FxTestSupport.call(() -> upsert.isDisabled() && !upsert.isSelected()));
        assertTrue(FxTestSupport.call(() -> skip.isDisabled() && !skip.isSelected()));
        assertEquals("ClickHouse can not match rows by key", FxTestSupport.call(() -> upsert.getTooltip().getText()));
        FxTestSupport.run(() -> dbType.setValue(DbType.POSTGRESQL));
        assertFalse(FxTestSupport.call(upsert::isDisabled));
        assertFalse(FxTestSupport.call(skip::isDisabled));
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsScriptDatabaseTypesByCategory() {
        open(schemaNode());
        List<DbType> types = FxTestSupport.call(() -> List.copyOf(((ComboBox<DbType>) node("scriptDbTypeCombo"))
                .getItems()));
        assertEquals(DbTypeCells.getDbTypesByCategory(), types);
        // OLAP databases are listed after relational databases
        assertEquals(List.of(DbType.DUCKDB, DbType.CLICKHOUSE), types.subList(types.size() - 2, types.size()));
    }

    @SuppressWarnings("unchecked")
    private void selectSameSchemaAsTarget() {
        FxTestSupport.run(() -> ((RadioButton) node("databaseRadio")).setSelected(true));
        ComboBox<ConnectionProfile> targetCombo = (ComboBox<ConnectionProfile>) node("targetCombo");
        FxTestSupport.run(() -> targetCombo.setValue(targetCombo.getItems().get(0)));
        ComboBox<String> targetSchema = (ComboBox<String>) node("targetSchemaCombo");
        FxTestSupport.waitUntil(() -> !targetSchema.getItems().isEmpty());
        FxTestSupport.run(() -> targetSchema.getEditor().setText(UiDatabase.SCHEMA));
    }

    @Test
    void requiresTableNameToCopyInSameSchema() {
        open(schemaNode());
        selectSameSchemaAsTarget();
        // The tables would be imported into themselves
        assertTrue(startDisabled());
        assertEquals("Source and target are the same tables, please choose another schema or a target table name"
                + " such as {table}_copy", error());
        setText("tableNamePatternField", "{table}");
        assertTrue(startDisabled());
        setText("tableNamePatternField", "{table}_copy");
        assertFalse(startDisabled(), error());
        TransferRequest request = build();
        assertEquals("{table}_copy", request.getTableNamePattern());
        assertEquals(UiDatabase.SCHEMA, request.getTargetSchema());
        // A name without {table} is the name of one table
        setText("tableNamePatternField", "T_BACKUP");
        assertEquals("The target table name must contain {table} when several tables are imported", error());
        // Another schema needs no table name
        setText("tableNamePatternField", "");
        FxTestSupport.run(() -> ((ComboBox<?>) node("targetSchemaCombo")).getEditor().setText("COPY"));
        assertFalse(startDisabled(), error());
        assertNull(build().getTableNamePattern());
    }

    @Test
    void copiesTablesInSameSchema() throws Exception {
        TransferRequest request = new TransferRequest();
        request.setSource(profile);
        request.setSourceCatalog(UiDatabase.CATALOG);
        request.setSourceSchema(UiDatabase.SCHEMA);
        request.setTables(List.of("T_ONE", "T_PAGED"));
        request.setTarget(TransferRequest.Target.DATABASE);
        request.setTargetProfile(profile);
        request.setTargetCatalog(UiDatabase.CATALOG);
        request.setTargetSchema(UiDatabase.SCHEMA);
        // The same tables are rejected, the source tables are kept
        ImpExpException e = Assertions.assertThrows(
                ImpExpException.class, () -> context.getTransferService().transfer(request,
                        ExportListener.NONE));
        assertTrue(e.getMessage().startsWith("Source and target tables are the same"), e.getMessage());
        request.setTableNamePattern("{table}_copy");
        context.getTransferService().transfer(request, ExportListener.NONE);
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            for (String[] table : new String[][]{{"T_ONE", "5"}, {"T_ONE_COPY", "5"},
                    {"T_PAGED", String.valueOf(UiDatabase.PAGED_ROWS)},
                    {"T_PAGED_COPY", String.valueOf(UiDatabase.PAGED_ROWS)}}) {
                try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table[0])) {
                    rs.next();
                    assertEquals(Integer.parseInt(table[1]), rs.getInt(1), table[0]);
                }
            }
            // Keys and indexes of the copy have their own names
            try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES"
                    + " WHERE TABLE_NAME = 'T_PAGED_COPY'")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
            statement.execute("DROP TABLE T_ONE_COPY");
            statement.execute("DROP TABLE T_PAGED_COPY");
        }
    }

    @Test
    void exportsRowsOfQuery() {
        TableQuery query = new TableQuery(List.of("ID", "NAME"), "ID > 200", null, "ID DESC");
        dialog = FxTestSupport.call(() -> {
            ExportDialog exportDialog = new ExportDialog(stage, context, tableNode("T_PAGED"), query);
            exportDialog.show();
            return exportDialog;
        });
        FxTestSupport.waitUntil(() -> list().getItems().size() == 3);
        setText("directoryField", dir.getAbsolutePath());
        // The query of the data viewer is shown for its table
        String label = FxTestSupport.call(() -> ((Label) node("queryLabel")).getText());
        assertEquals("Rows of T_PAGED: SELECT ID, NAME WHERE ID > 200 ORDER BY ID DESC", label);
        assertTrue(FxTestSupport.call(() -> node("queryLabel").isVisible()));
        TransferRequest request = build();
        assertEquals(List.of("T_PAGED"), request.getTables());
        assertEquals(query, request.getTableQueries().get("T_PAGED"));
        // Other tables selected too: the query narrows its own table only
        FxTestSupport.run(() -> ((Button) node("selectAllButton")).fire());
        TransferRequest all = build();
        assertTrue(all.getTables().isEmpty(), "All tables of the schema");
        assertEquals(1, all.getTableQueries().size());
        assertEquals(query, all.getTableQueries().get("T_PAGED"));
        // A query without columns, condition and order narrows nothing
        FxTestSupport.run(dialog::close);
        dialog = FxTestSupport.call(() -> {
            ExportDialog plain = new ExportDialog(stage, context, tableNode("T_PAGED"), new TableQuery());
            plain.show();
            plain.getDialogPane().applyCss();
            plain.getDialogPane().layout();
            return plain;
        });
        assertFalse(FxTestSupport.call(() -> node("queryLabel").isVisible()));
        assertTrue(build().getTableQueries().isEmpty());
    }

    /**
     * Key columns of several tables, a table without primary key is inserted unless columns are chosen
     */
    @Test
    void choosesKeyColumnsOfTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS T_NO_KEY (CODE VARCHAR(10), NAME VARCHAR(20))");
        }
        DatabaseSession session = context.getSessionManager().getSession(profile);
        KeyColumnsDialog keys = FxTestSupport.call(() -> new KeyColumnsDialog(stage, session, UiDatabase.CATALOG,
                UiDatabase.SCHEMA, List.of("T_ONE", "T_NO_KEY"), Map.of("T_ONE", List.of("NAME"))));
        FxTestSupport.run(keys::show);
        TextField one = FxTestSupport.call(() -> (TextField) keys.getDialogPane().lookup("#keyColumns-T_ONE"));
        TextField noKey = FxTestSupport.call(() -> (TextField) keys.getDialogPane().lookup("#keyColumns-T_NO_KEY"));
        FxTestSupport.waitUntil(() -> noKey.getPromptText().startsWith("No"));
        assertEquals("No primary key, rows are inserted", FxTestSupport.call(noKey::getPromptText));
        // The chosen columns of before
        assertEquals("NAME", FxTestSupport.call(one::getText));
        FxTestSupport.run(() -> noKey.setText("code"));
        assertEquals(Map.of("T_ONE", List.of("NAME"), "T_NO_KEY", List.of("code")), FxTestSupport.call(
                keys::getKeyColumns));
        // An empty field is the primary key
        FxTestSupport.run(() -> one.setText("  "));
        assertEquals(Map.of("T_NO_KEY", List.of("code")), FxTestSupport.call(keys::getKeyColumns));
        assertEquals(List.of(), KeyColumnsDialog.parse(null));
        FxTestSupport.run(keys::close);
    }

    /**
     * Rows of a query of the SQL editor are exported as one table: the source is fixed, the table list is the
     * name of the table, options of tables are hidden, the same schema is a valid target
     */
    @Test
    void exportsQueryAsTable() throws Exception {
        dialog = FxTestSupport.call(() -> {
            ExportDialog exportDialog = ExportDialog.forQuery(stage, context, schemaNode(),
                    "  SELECT ID, NAME FROM T_ONE\n  WHERE ID > 2  ");
            exportDialog.show();
            return exportDialog;
        });
        TextField table = node("queryTableField");
        assertEquals("query_result", FxTestSupport.call(table::getText));
        assertEquals("SELECT ID, NAME FROM T_ONE WHERE ID > 2", FxTestSupport.call(() -> ((Label) node(
                "querySqlLabel")).getText()));
        for (String hidden : new String[]{"indexCheck", "foreignKeyCheck", "keyColumnsButton",
                "tableNamePatternField", "tableList"}) {
            Node control = node(hidden);
            assertTrue(control == null || !FxTestSupport.call(control::isVisible), hidden);
        }
        assertTrue(FxTestSupport.call(() -> node("sourceCombo").isMouseTransparent()));
        FxTestSupport.waitUntil(() -> !startDisabled() || !error().isEmpty());
        setText("directoryField", dir.getAbsolutePath());
        // Names of tables only
        for (String invalid : new String[]{"", "1st", "bad name", "a;b"}) {
            setText("queryTableField", invalid);
            assertTrue(startDisabled(), invalid);
            assertEquals("The table name of the query must start with a letter or underscore and have letters,"
                    + " digits and underscores only", error());
        }
        setText("queryTableField", "  BIG_ONES ");
        assertFalse(startDisabled());
        TransferRequest request = build();
        assertEquals(Map.of("BIG_ONES", "SELECT ID, NAME FROM T_ONE\n  WHERE ID > 2"), request.getQueries());
        assertEquals(List.of(), request.getTables());
        assertEquals(UiDatabase.CATALOG, request.getSourceCatalog());
        assertEquals(UiDatabase.SCHEMA, request.getSourceSchema());
        // The same schema is a valid target of a query, the table name pattern is not used
        FxTestSupport.run(() -> ((RadioButton) node("databaseRadio")).setSelected(true));
        @SuppressWarnings("unchecked")
        ComboBox<ConnectionProfile> target = (ComboBox<ConnectionProfile>) node("targetCombo");
        FxTestSupport.run(() -> target.setValue(target.getItems().get(0)));
        @SuppressWarnings("unchecked")
        ComboBox<String> targetSchema = (ComboBox<String>) node("targetSchemaCombo");
        FxTestSupport.waitUntil(() -> !targetSchema.getItems().isEmpty());
        FxTestSupport.run(() -> targetSchema.getEditor().setText(UiDatabase.SCHEMA));
        assertFalse(startDisabled(), error());
        request = build();
        assertEquals(null, request.getTableNamePattern());
        // The rows of the query go into a new table of the same schema
        context.getTransferService().transfer(request, ExportListener.NONE);
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(profile.getJdbcUrl(), "sa", "");
             java.sql.ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM BIG_ONES")) {
            rs.next();
            assertEquals(3, rs.getInt(1));
        }
    }
}
