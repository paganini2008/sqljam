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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.config.Config;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import com.github.sqljam.face.model.TransferRequest;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.ExportMode;
import com.github.sqljam.impexp.IdentifierCase;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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
}
