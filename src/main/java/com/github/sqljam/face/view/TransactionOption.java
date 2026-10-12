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

import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.face.model.ConnectionProfile;
import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * @Description: TransactionOption is the transaction of an import into a database, like the import wizards of other
 *               tools: one transaction which is rolled back on an error (the default), or a commit every N rows,
 *               N is typed in the option.
 *               Confirm before commit asks the user to commit or roll back a successful import in one transaction,
 *               commits every N rows are not confirmed. Skip errors logs and skips the rows with errors, otherwise an
 *               error rolls back one transaction or stops the commits every N rows. Databases without transactions
 *               (ClickHouse) commit every row as it is written.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
final class TransactionOption {

    static final int MAX_COMMIT_ROWS = 10_000_000;

    private final RadioButton oneTransactionRadio = new RadioButton(Messages.get("import.transaction.one"));
    private final RadioButton commitEveryRadio = new RadioButton(Messages.get("import.transaction.every"));
    private final Spinner<Integer> commitRowsSpinner = new Spinner<>(1, MAX_COMMIT_ROWS, 1, 1000);
    private final CheckBox confirmCheck = new CheckBox(Messages.get("import.transaction.confirm"));
    private final CheckBox skipErrorsCheck = new CheckBox(Messages.get("import.transaction.skipErrors"));
    private final Label unsupportedLabel = new Label();
    private final VBox node;
    private boolean oneTransactionChosen;

    /**
     * @param target     target data source of the dialog
     * @param commitRows rows per commit of the import by default, the page or batch size
     */
    TransactionOption(AppContext context, ObservableValue<ConnectionProfile> target, long commitRows) {
        oneTransactionRadio.setId("oneTransactionRadio");
        commitEveryRadio.setId("commitEveryRadio");
        commitRowsSpinner.setId("commitRowsSpinner");
        commitRowsSpinner.setEditable(true);
        commitRowsSpinner.setPrefWidth(110);
        confirmCheck.setId("confirmCommitCheck");
        skipErrorsCheck.setId("skipErrorsCheck");
        unsupportedLabel.setId("transactionUnsupportedLabel");
        ToggleGroup group = new ToggleGroup();
        oneTransactionRadio.setToggleGroup(group);
        commitEveryRadio.setToggleGroup(group);
        oneTransactionRadio.setTooltip(new Tooltip(Messages.get("import.transaction.one.tooltip")));
        confirmCheck.setTooltip(new Tooltip(Messages.get("import.transaction.confirm.tooltip")));
        skipErrorsCheck.setTooltip(new Tooltip(Messages.get("import.transaction.skipErrors.tooltip")));
        setCommitRows(commitRows);
        oneTransactionChosen = context.getSettings().isOneTransaction();
        (oneTransactionChosen ? oneTransactionRadio : commitEveryRadio).setSelected(true);
        unsupportedLabel.getStyleClass().add("muted");
        unsupportedLabel.setWrapText(true);
        group.selectedToggleProperty().addListener((obs, oldToggle, toggle) -> {
            if (!oneTransactionRadio.isDisabled()) {
                oneTransactionChosen = toggle == oneTransactionRadio;
            }
            update();
        });
        target.addListener((obs, oldProfile, profile) -> applyTarget(profile));
        Label rowsLabel = new Label(Messages.get("import.transaction.rows"));
        HBox commitEveryBox = new HBox(6, commitEveryRadio, commitRowsSpinner, rowsLabel);
        commitEveryBox.setAlignment(Pos.CENTER_LEFT);
        // One choice a line, the labels stay readable in narrow dialogs
        node = new VBox(6, oneTransactionRadio, commitEveryBox, confirmCheck, skipErrorsCheck, unsupportedLabel);
        applyTarget(target.getValue());
    }

    /**
     * Rows per commit of the option, e.g. after the page size is changed
     */
    void setCommitRows(long commitRows) {
        commitRowsSpinner.getValueFactory().setValue((int) Math.max(1, Math.min(MAX_COMMIT_ROWS, commitRows)));
    }

    /**
     * Shows rows per commit typed elsewhere, e.g. the page size, without changing the value yet
     */
    void showCommitRows(int commitRows) {
        commitRowsSpinner.getEditor().setText(String.valueOf(commitRows));
    }

    /**
     * Rows per commit, also while they are typed
     */
    int getCommitRows() {
        String text = StringUtils.trim(commitRowsSpinner.getEditor().getText());
        if (StringUtils.isNumeric(text) && !text.isEmpty() && text.length() < 9) {
            return Math.max(1, Math.min(MAX_COMMIT_ROWS, Integer.parseInt(text)));
        }
        return commitRowsSpinner.getValue();
    }

    /**
     * Value of the rows per commit, e.g. bound to the page size of an export
     */
    ObjectProperty<Integer> commitRowsProperty() {
        return commitRowsSpinner.getValueFactory().valueProperty();
    }

    private void applyTarget(ConnectionProfile profile) {
        boolean supported = profile == null || profile.getDbType().isTransactionSupported();
        oneTransactionRadio.setDisable(!supported);
        commitEveryRadio.setDisable(!supported);
        (supported && oneTransactionChosen ? oneTransactionRadio : commitEveryRadio).setSelected(true);
        unsupportedLabel.setText(supported ? null : Messages.format("import.transaction.unsupported",
                profile.getDbType().getDisplayName()));
        unsupportedLabel.setVisible(!supported);
        unsupportedLabel.setManaged(!supported);
        update();
    }

    private void update() {
        boolean oneTransaction = isOneTransaction();
        confirmCheck.setDisable(!oneTransaction);
        commitRowsSpinner.setDisable(oneTransaction || commitEveryRadio.isDisabled());
        // Commits every N rows are not confirmed
        if (!oneTransaction) {
            confirmCheck.setSelected(false);
        }
    }

    Node getNode() {
        return node;
    }

    /**
     * Whether the import runs in one transaction, rolled back on an error
     */
    boolean isOneTransaction() {
        return oneTransactionRadio.isSelected() && !oneTransactionRadio.isDisabled();
    }

    /**
     * Whether the user commits or rolls back a successful import in one transaction
     */
    boolean isConfirmCommit() {
        return isOneTransaction() && confirmCheck.isSelected();
    }

    /**
     * Whether rows with errors are skipped, otherwise an error rolls back one transaction or stops the import
     */
    boolean isSkipErrors() {
        return skipErrorsCheck.isSelected();
    }
}
