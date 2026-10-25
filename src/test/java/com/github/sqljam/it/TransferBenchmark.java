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
package com.github.sqljam.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.framework.junit5.ApplicationExtension;
import com.github.sqljam.face.view.DocImages;
import com.github.sqljam.face.view.FxTestSupport;
import com.github.sqljam.impexp.AbstractFileImporter;
import com.github.sqljam.impexp.CsvExporter;
import com.github.sqljam.impexp.DataFileStrategy;
import com.github.sqljam.impexp.DbType;
import com.github.sqljam.impexp.Exporter;
import com.github.sqljam.impexp.ImportExporter;
import com.github.sqljam.impexp.ParquetExporter;
import com.github.sqljam.impexp.ScriptExporter;
import com.github.sqljam.utils.RestClient;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbExporter;
import com.github.sqljam.vdb.it.VdbImportIT;
import com.github.sqljam.vdb.it.VdbServer;
import javafx.scene.Group;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * @Description: TransferBenchmark measures the throughput of SqlJam for the Performance section of the documents:
 *               a table of 200,000 rows and 6 columns of H2 copied straight into each database of the tests,
 *               exported as SQL, Parquet and CSV packages, a SQL package imported into PostgreSQL, and a Qdrant
 *               collection of vectors imported into PostgreSQL. It writes target/bench/transfer.md and the chart
 *               benchmark.svg and benchmark.png. It is not a test of the build (the name does not end with Test or
 *               IT), run it by
 *               mvn test -Dtest=TransferBenchmark -Dbench.output=docs/assets
 *               with -Dbench.rows, -Dbench.targets (e.g. POSTGRESQL,MYSQL), -Dbench.vectors (points of the
 *               collection, 0 leaves it out) and -Dbench.environment (the line under the title of the chart).
 *               The tables and the collection are dropped after each measurement, the source database at the end.
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class TransferBenchmark {

    private static final String TABLE = "BENCH_ROWS";
    private static final String COLLECTION = "sjvt_bench";
    private static final int DIMENSIONS = 256;
    private static final File DIR = new File("target/bench");

    static final String DIRECT = "#3fb950";
    static final String PACKAGE = "#d29922";
    static final String IMPORT = "#58a6ff";
    static final String VECTOR = "#a371f7";

    /**
     * One measured scenario: its label on the chart, the rows and the seconds
     */
    static final class Result {

        final String label;
        final String color;
        final long rows;
        final double seconds;

        Result(String label, String color, long rows, double seconds) {
            this.label = label;
            this.color = color;
            this.rows = rows;
            this.seconds = seconds;
        }

        double rowsPerSecond() {
            return rows / seconds;
        }
    }

    /**
     * A new source of 6 columns: id, order number, customer, status, total and time
     */
    private static String createSource(long rows) throws Exception {
        File file = new File(DIR, "transfer-source");
        FileUtils.deleteQuietly(new File(file.getPath() + ".mv.db"));
        String url = "jdbc:h2:file:" + file.getAbsolutePath();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + TABLE + " (ID BIGINT PRIMARY KEY, ORDER_NO VARCHAR(20) NOT NULL,"
                    + " CUSTOMER_ID INT NOT NULL, STATUS VARCHAR(12) NOT NULL, TOTAL DECIMAL(12,2) NOT NULL,"
                    + " CREATED_AT TIMESTAMP NOT NULL)");
            statement.execute("INSERT INTO " + TABLE + " SELECT X, 'SO-' || LPAD(X, 12, '0'), MOD(X, 5000),"
                    + " CASEWHEN(MOD(X, 3) = 0, 'PAID', 'NEW'), MOD(X, 100000) / 100.0,"
                    + " TIMESTAMP '2024-01-01 00:00:00' + X * INTERVAL '1' SECOND FROM SYSTEM_RANGE(1, " + rows + ")");
        }
        return url;
    }

    private static void configureSource(Exporter.ExportConfiguration source, String url) {
        source.setDbType(DbType.H2);
        source.setUrl(url);
        source.setUsername("sa");
        source.setPassword("");
        source.setIncludedSchemaNames(new String[]{"PUBLIC"});
        source.setIncludedTableNames(new String[]{TABLE});
        source.setIdReused(true);
    }

    private static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    /**
     * Rows of the imported table, its name is folded by the database or kept in lower case
     */
    private static long count(ItDatabase target) throws SQLException {
        String owner = target.getDbType() == DbType.MYSQL || target.getDbType() == DbType.MARIADB
                ? target.getTargetCatalog() : target.getTargetSchema();
        for (String table : new String[]{TABLE, TABLE.toLowerCase(Locale.ENGLISH)}) {
            String name = (owner != null ? owner + "." : "") + table;
            try (Connection connection = target.getTargetConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + name)) {
                rs.next();
                return rs.getLong(1);
            } catch (SQLException e) {
                // The other case of the name
            }
        }
        throw new SQLException("Table " + TABLE + " is not in " + target);
    }

    private static void drop(ItDatabase target, String table) {
        String owner = target.getDbType() == DbType.MYSQL || target.getDbType() == DbType.MARIADB
                ? target.getTargetCatalog() : target.getTargetSchema();
        for (String name : new String[]{table, table.toLowerCase(Locale.ENGLISH), table.toUpperCase(Locale.ENGLISH)}) {
            try (Connection connection = target.getTargetConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + (owner != null ? owner + "." : "") + name);
                return;
            } catch (Exception ignored) {
                // Not created, or another case
            }
        }
    }

    private static Result copy(String sourceUrl, ItDatabase target, long rows) throws Exception {
        drop(target, TABLE);
        ImportExporter copier = new ImportExporter();
        configureSource(copier.getExportConfiguration(), sourceUrl);
        target.configureTarget(copier.getImportConfiguration());
        long start = System.nanoTime();
        copier.exportDdlAndData();
        double seconds = seconds(start);
        assertEquals(rows, count(target), "Rows of " + target);
        drop(target, TABLE);
        return new Result("H2 → " + target.getDbType().getDisplayName(), DIRECT, rows, seconds);
    }

    private static Result sqlPackage(String sourceUrl, File dir, long rows) throws Exception {
        FileUtils.deleteQuietly(dir);
        ScriptExporter exporter = new ScriptExporter(dir, DataFileStrategy.FILE_PER_TABLE, 10L * 1024 * 1024);
        configureSource(exporter.getConfiguration(), sourceUrl);
        exporter.setTargetDbType(DbType.POSTGRESQL);
        long start = System.nanoTime();
        exporter.exportDdlAndData();
        return new Result("H2 → SQL package", PACKAGE, rows, seconds(start));
    }

    private static Result parquetPackage(String sourceUrl, File dir, long rows) throws Exception {
        FileUtils.deleteQuietly(dir);
        ParquetExporter exporter = new ParquetExporter(dir);
        configureSource(exporter.getConfiguration(), sourceUrl);
        long start = System.nanoTime();
        exporter.exportDdlAndData();
        return new Result("H2 → Parquet package", PACKAGE, rows, seconds(start));
    }

    private static Result csvPackage(String sourceUrl, File dir, long rows) throws Exception {
        FileUtils.deleteQuietly(dir);
        CsvExporter exporter = new CsvExporter(dir);
        configureSource(exporter.getConfiguration(), sourceUrl);
        long start = System.nanoTime();
        exporter.exportDdlAndData();
        return new Result("H2 → CSV package", PACKAGE, rows, seconds(start));
    }

    private static Result importPackage(File dir, ItDatabase target, long rows) throws Exception {
        drop(target, TABLE);
        double seconds;
        try (Connection connection = target.getTargetConnection()) {
            AbstractFileImporter importer = AbstractFileImporter.forDirectory(dir, connection, target.getDbType());
            importer.setStopOnError(true);
            long start = System.nanoTime();
            importer.importDirectory(dir);
            seconds = seconds(start);
        }
        assertEquals(rows, count(target), "Rows of the package in " + target);
        drop(target, TABLE);
        return new Result("SQL package → " + target.getDbType().getDisplayName(), IMPORT, rows, seconds);
    }

    /**
     * A collection of random vectors and a payload of two fields, imported into the target
     */
    private static Result vectors(ItDatabase target, int points) throws Exception {
        VdbServer server = VdbServer.QDRANT;
        RestClient rest = server.rest();
        try {
            rest.delete("/collections/" + COLLECTION);
        } catch (RuntimeException e) {
            // There is none
        }
        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", DIMENSIONS);
        vectors.put("distance", "Cosine");
        rest.put("/collections/" + COLLECTION, Map.of("vectors", vectors));
        Random random = new Random(7);
        for (int from = 1; from <= points; from += 500) {
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int id = from; id < Math.min(from + 500, points + 1); id++) {
                float[] vector = new float[DIMENSIONS];
                for (int i = 0; i < DIMENSIONS; i++) {
                    vector[i] = random.nextFloat() - 0.5f;
                }
                batch.add(Map.of("id", id, "vector", vector, "payload", Map.of("title", "Document " + id,
                        "n", id)));
            }
            rest.put("/collections/" + COLLECTION + "/points?wait=true", Map.of("points", batch));
        }
        drop(target, COLLECTION);
        try {
            VdbExportConfiguration configuration = new VdbExportConfiguration();
            configuration.setTargetSchema(VdbImportIT.getSchema(target));
            configuration.addCollection(COLLECTION, COLLECTION);
            configuration.setTableRecreated(true);
            configuration.setVectorIndexCreated(Boolean.parseBoolean(System.getProperty("bench.vectorIndex",
                    "true")));
            long start = System.nanoTime();
            try (VdbClient client = server.createClient()) {
                assertEquals(points, new VdbExporter(client, VdbImportIT.dataSource(target), configuration).export()
                        .getWritten());
            }
            return new Result(String.format(Locale.ENGLISH, "Qdrant → %s (%,d points, %d-d vectors and payload)",
                    target.getDbType().getDisplayName(), points, DIMENSIONS), VECTOR, points, seconds(start));
        } finally {
            drop(target, COLLECTION);
            rest.delete("/collections/" + COLLECTION);
        }
    }

    @Test
    void run() throws Exception {
        long rows = Long.getLong("bench.rows", 200_000);
        int points = Integer.getInteger("bench.vectors", 20_000);
        List<String> names = Arrays.asList(System.getProperty("bench.targets",
                "MYSQL,MARIADB,POSTGRESQL,ORACLE,SQLSERVER,DB2,CLICKHOUSE,DUCKDB").split(","));
        String environment = System.getProperty("bench.environment", "Apple M2 Max · JDK 17 · databases in Docker"
                + " on a LAN server · default settings · higher is better");
        DIR.mkdirs();
        String sourceUrl = createSource(rows);
        List<Result> results = new ArrayList<>();
        try {
            for (String name : names) {
                ItDatabase target = ItDatabase.valueOf(name.trim());
                if (target.isAvailable()) {
                    results.add(copy(sourceUrl, target, rows));
                }
            }
            File sql = new File(DIR, "transfer-sql");
            results.add(sqlPackage(sourceUrl, sql, rows));
            results.add(parquetPackage(sourceUrl, new File(DIR, "transfer-parquet"), rows));
            results.add(csvPackage(sourceUrl, new File(DIR, "transfer-csv"), rows));
            if (ItDatabase.POSTGRESQL.isAvailable()) {
                results.add(importPackage(sql, ItDatabase.POSTGRESQL, rows));
                if (points > 0 && VdbServer.QDRANT.isAvailable()) {
                    results.add(vectors(ItDatabase.POSTGRESQL, points));
                }
            }
        } finally {
            FileUtils.deleteQuietly(new File(DIR, "transfer-source.mv.db"));
        }
        assertFalse(results.isEmpty());
        String title = String.format(Locale.ENGLISH, "Throughput · %,d rows, 6 columns", rows);
        Files.writeString(new File(DIR, "transfer.md").toPath(), table(results, environment));
        // Rows of vectors are not rows of 6 columns, the chart compares the rows of the table only
        List<Shape> shapes = chart(title, environment, results.stream().filter(result -> !VECTOR.equals(
                result.color)).collect(Collectors.toList()));
        File output = new File(System.getProperty("bench.output", DIR.getPath()));
        output.mkdirs();
        Files.writeString(new File(output, "benchmark.svg").toPath(), svg(shapes), StandardCharsets.UTF_8);
        WritableImage image = FxTestSupport.call(() -> new Group(shapes.stream().map(Shape::toNode)
                .collect(Collectors.toList())).snapshot(null, null));
        DocImages.write(image, new File(output, "benchmark.png"));
    }

    /**
     * The Performance table of the README
     */
    static String table(List<Result> results, String environment) {
        StringBuilder text = new StringBuilder("| Scenario | Rows | Time | Rows/s |\n|---|---|---|---|\n");
        for (Result result : results) {
            text.append(String.format(Locale.ENGLISH, "| %s | %,d | %.1f s | %.1fk |%n", result.label, result.rows,
                    result.seconds, result.rowsPerSecond() / 1000));
        }
        return text.append("\nEnvironment: ").append(environment).append('\n').toString();
    }

    /**
     * An element of the chart, drawn as SVG and as a JavaFX node from the same values
     */
    static final class Shape {

        final String kind;
        final double x;
        final double y;
        final double width;
        final double height;
        final String text;
        final String color;
        final double size;
        final String anchor;
        final boolean bold;

        Shape(String kind, double x, double y, double width, double height, String text, String color, double size,
              String anchor, boolean bold) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.text = text;
            this.color = color;
            this.size = size;
            this.anchor = anchor;
            this.bold = bold;
        }

        static Shape rect(double x, double y, double width, double height, String color) {
            return new Shape("rect", x, y, width, height, null, color, 0, null, false);
        }

        static Shape line(double x1, double y1, double x2, double y2, String color) {
            return new Shape("line", x1, y1, x2, y2, null, color, 0, null, false);
        }

        static Shape text(double x, double y, String text, String color, double size, String anchor, boolean bold) {
            return new Shape("text", x, y, 0, 0, text, color, size, anchor, bold);
        }

        String toSvg() {
            switch (kind) {
                case "rect":
                    return String.format(Locale.ENGLISH, "<rect x=\"%.0f\" y=\"%.0f\" width=\"%.0f\" height=\"%.0f\""
                            + " rx=\"%d\" fill=\"%s\"/>", x, y, width, height, height > 100 ? 16 : 4, color);
                case "line":
                    return String.format(Locale.ENGLISH, "<line x1=\"%.0f\" y1=\"%.0f\" x2=\"%.0f\" y2=\"%.0f\""
                            + " stroke=\"%s\"/>", x, y, width, height, color);
                default:
                    return String.format(Locale.ENGLISH, "<text x=\"%.0f\" y=\"%.0f\" fill=\"%s\" font-size=\"%.0f\""
                                    + "%s%s>%s</text>", x, y, color, size, bold ? " font-weight=\"700\"" : "",
                            "start".equals(anchor) ? "" : " text-anchor=\"" + anchor + "\"", text.replace("&",
                                    "&amp;").replace("<", "&lt;"));
            }
        }

        javafx.scene.Node toNode() {
            switch (kind) {
                case "rect":
                    Rectangle rectangle = new Rectangle(x, y, width, height);
                    double arc = height > 100 ? 32 : 8;
                    rectangle.setArcWidth(arc);
                    rectangle.setArcHeight(arc);
                    rectangle.setFill(Color.web(color));
                    return rectangle;
                case "line":
                    Line line = new Line(x, y, width, height);
                    line.setStroke(Color.web(color));
                    return line;
                default:
                    Text node = new Text(text);
                    node.setFont(Font.font("Helvetica", bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
                    node.setFill(Color.web(color));
                    double textWidth = node.getLayoutBounds().getWidth();
                    node.setX("end".equals(anchor) ? x - textWidth : "middle".equals(anchor) ? x - textWidth / 2 : x);
                    node.setY(y);
                    node.setTextAlignment(TextAlignment.LEFT);
                    return node;
            }
        }
    }

    /**
     * Bars of rows per second, like the chart of the earlier documents
     */
    static List<Shape> chart(String title, String environment, List<Result> results) {
        double left = 340;
        double right = 1140;
        double top = 110;
        double bar = 26;
        double gap = 14;
        double height = top + results.size() * (bar + gap) + 70;
        double max = results.stream().mapToDouble(Result::rowsPerSecond).max().orElse(1);
        double step = niceStep(max / 5);
        double scale = Math.ceil(max / step) * step;
        double bottom = top + results.size() * (bar + gap);
        List<Shape> shapes = new ArrayList<>();
        shapes.add(Shape.rect(0, 0, 1200, height, "#0d1117"));
        shapes.add(Shape.text(40, 52, title, "#e6edf3", 24, "start", true));
        shapes.add(Shape.text(40, 78, environment, "#8b949e", 14, "start", false));
        for (double value = 0; value <= scale + 1e-6; value += step) {
            double x = left + (right - left) * value / scale;
            shapes.add(Shape.line(x, top, x, bottom, value == 0 ? "#30363d" : "#21262d"));
            shapes.add(Shape.text(x, bottom + 22, value == 0 ? "0" : (Math.round(value / 1000) + "k")
                    + (value + step > scale + 1e-6 ? " rows/s" : ""), "#8b949e", 12, "middle", false));
        }
        shapes.add(Shape.line(left, bottom, right, bottom, "#30363d"));
        double y = top + gap / 2;
        for (Result result : results) {
            double width = Math.max(2, (right - left) * result.rowsPerSecond() / scale);
            shapes.add(Shape.text(left - 16, y + bar * 0.68, result.label, "#c9d1d9", 14, "end", false));
            shapes.add(Shape.rect(left, y, width, bar, result.color));
            shapes.add(Shape.text(left + width + 10, y + bar * 0.68, String.format(Locale.ENGLISH, "%.1fk",
                    result.rowsPerSecond() / 1000), "#c9d1d9", 14, "start", false));
            y += bar + gap;
        }
        return shapes;
    }

    static double niceStep(double raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double factor : new double[]{1, 2, 2.5, 5, 10}) {
            if (factor * magnitude >= raw) {
                return factor * magnitude;
            }
        }
        return 10 * magnitude;
    }

    static String svg(List<Shape> shapes) {
        Shape background = shapes.get(0);
        return String.format(Locale.ENGLISH, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.0f\" height=\"%.0f\""
                + " viewBox=\"0 0 %.0f %.0f\" font-family=\"Inter, -apple-system, Segoe UI, Helvetica, Arial, sans-serif\">"
                + "%n", background.width, background.height, background.width, background.height)
                + shapes.stream().map(shape -> "  " + shape.toSvg()).collect(Collectors.joining("\n")) + "\n</svg>\n";
    }
}
