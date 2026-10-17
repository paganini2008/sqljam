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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.kordamp.ikonli.javafx.FontIcon;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import com.github.sqljam.face.model.ConnectionProfile;
import com.github.sqljam.face.model.TableInfo;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * @Description: ConnectionTreeTest verifies how tables are told apart in the tree: partitioned tables have the
 *               layers icon and a badge of their partitioning, partitions are children of their partitioned tables
 *               with the slice icon, tooltips show the partitioning
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class ConnectionTreeTest {

    @TempDir
    File dir;

    private ConnectionTree tree;
    private final ConnectionProfile profile = new ConnectionProfile();

    @Start
    void start(Stage stage) {
        tree = new ConnectionTree(FxTestSupport.createContext(dir));
        stage.setScene(new Scene(tree, 400, 400));
        stage.show();
    }

    private static TableInfo table(String name) {
        return new TableInfo(null, "public", name, null, false, false);
    }

    private static TableInfo partitioned(String name, String type, String key) {
        TableInfo table = new TableInfo(null, "public", name, null, true, false);
        table.setPartitionType(type);
        table.setPartitionKey(key);
        return table;
    }

    private static TableInfo partition(String name, String parent) {
        TableInfo table = new TableInfo(null, "public", name, null, false, true);
        table.setParent(parent);
        return table;
    }

    private static List<String> names(List<TreeItem<DbNode>> items) {
        return items.stream().map(item -> item.getValue().getLabel()).collect(Collectors.toList());
    }

    @Test
    void nestsPartitionsUnderPartitionedTables() {
        // Two levels: sales by year, the year 2024 by region
        List<TableInfo> tables = List.of(table("orders"), partitioned("sales", "RANGE", "sale_year"),
                partition("sales_2023", "sales"), partitioned("sales_2024", "LIST", "region"),
                partition("sales_2024_n", "sales_2024"), partition("orphan", "missing"));
        tables.get(3).setPartition(true);
        tables.get(3).setParent("sales");
        List<TreeItem<DbNode>> children = new ArrayList<>();
        ConnectionTree.addTables(children, profile, tables);

        assertEquals(List.of("orders", "sales", "orphan"), names(children), "A partition without its table is kept");
        TreeItem<DbNode> sales = children.get(1);
        assertEquals(List.of("sales_2023", "sales_2024"), names(sales.getChildren()));
        assertEquals(List.of("sales_2024_n"), names(sales.getChildren().get(1).getChildren()));
        assertTrue(children.get(0).isLeaf());
    }

    @Test
    void badgesShowPartitioning() {
        assertNull(ConnectionTree.partitionBadge(table("orders")));
        assertNull(ConnectionTree.partitionBadge(partition("sales_2023", "sales")));
        assertEquals("RANGE COLUMNS", ConnectionTree.partitionBadge(partitioned("sales", "range columns", "d"))
                .getText());
        // ClickHouse has the partition key only
        assertEquals("toYYYYMM(created)", ConnectionTree.partitionBadge(partitioned("events", null,
                "toYYYYMM(created)")).getText());
        assertEquals("PARTITIONED", ConnectionTree.partitionBadge(partitioned("t", null, null)).getText());
        Label badge = ConnectionTree.partitionBadge(partitioned("t", null, "cityHash64(name, created, id) % 16"));
        assertEquals(24, badge.getText().length(), "Long keys are abbreviated");
        assertTrue(badge.getStyleClass().contains("partition-badge"));
    }

    @Test
    void iconsAndTooltipsTellTablesApart() {
        FontIcon partitionedIcon = ConnectionTree.tableIcon(partitioned("sales", "RANGE", "sale_year"));
        FontIcon partitionIcon = ConnectionTree.tableIcon(partition("sales_2023", "sales"));
        FontIcon tableIcon = ConnectionTree.tableIcon(table("orders"));
        assertEquals(Icons.PARTITIONED, partitionedIcon.getIconLiteral());
        assertTrue(partitionedIcon.getStyleClass().contains("partitioned-icon"));
        assertEquals(Icons.PARTITION, partitionIcon.getIconLiteral());
        assertTrue(partitionIcon.getStyleClass().contains("partition-icon"));
        assertEquals(Icons.TABLE, tableIcon.getIconLiteral());

        assertNull(ConnectionTree.tableTooltip(table("orders")));
        TableInfo commented = partitioned("sales", "RANGE", "sale_year");
        commented.setRemarks("Sales by year");
        assertEquals("Partitioned table: RANGE (sale_year)\nSales by year", ConnectionTree.tableTooltip(commented));
        assertEquals("Partitioned table: toYYYYMM(created)", ConnectionTree.tableTooltip(partitioned("events", null,
                "toYYYYMM(created)")));
        assertEquals("Partitioned table: HASH", ConnectionTree.tableTooltip(partitioned("h", "HASH", null)));
        assertEquals("Partition of sales", ConnectionTree.tableTooltip(partition("sales_2023", "sales")));
    }

    @Test
    void cellsOfPartitionedTablesHaveBadges() {
        FxTestSupport.run(() -> {
            TreeItem<DbNode> root = new TreeItem<>(DbNode.root());
            List<TreeItem<DbNode>> children = new ArrayList<>();
            ConnectionTree.addTables(children, profile, List.of(table("orders"),
                    partitioned("sales", "RANGE", "sale_year"), partition("sales_2023", "sales")));
            root.getChildren().setAll(children);
            children.get(1).setExpanded(true);
            tree.setRoot(root);
            tree.applyCss();
            tree.layout();
        });
        List<TreeCell<?>> cells = FxTestSupport.call(() -> tree.lookupAll(".tree-cell").stream()
                .map(node -> (TreeCell<?>) node).filter(cell -> cell.getItem() != null)
                .collect(Collectors.toList()));
        TreeCell<?> orders = find(cells, "orders");
        assertEquals("orders", orders.getText());
        assertEquals(Icons.TABLE, ((FontIcon) orders.getGraphic()).getIconLiteral());

        TreeCell<?> sales = find(cells, "sales");
        assertNull(sales.getText(), "The name is a label before the badge");
        HBox graphic = (HBox) sales.getGraphic();
        assertNotNull(graphic);
        assertEquals("sales", ((Label) graphic.getChildren().get(1)).getText());
        assertEquals("RANGE", ((Label) graphic.getChildren().get(2)).getText());
        assertEquals("Partitioned table: RANGE (sale_year)", sales.getTooltip().getText());

        TreeCell<?> partition = find(cells, "sales_2023");
        assertEquals(Icons.PARTITION, ((FontIcon) partition.getGraphic()).getIconLiteral());
        assertEquals("Partition of sales", partition.getTooltip().getText());
    }

    private static TreeCell<?> find(List<TreeCell<?>> cells, String name) {
        return cells.stream().filter(cell -> ((DbNode) cell.getItem()).getLabel().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("No cell of " + name));
    }
}
