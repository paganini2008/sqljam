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
package com.github.sqljam.vdb.dialect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import com.github.sqljam.vdb.DefaultVdbMetaDataVisitor;
import com.github.sqljam.vdb.VdbClient;
import com.github.sqljam.vdb.VdbCollection;
import com.github.sqljam.vdb.VdbCollectionMetaData;
import com.github.sqljam.vdb.VdbDdlScripter;
import com.github.sqljam.vdb.VdbException;
import com.github.sqljam.vdb.VdbExportConfiguration;
import com.github.sqljam.vdb.VdbField;
import com.github.sqljam.vdb.VdbQuery;
import com.github.sqljam.vdb.VdbServerMetaData;
import com.github.sqljam.vdb.VdbType;
import com.github.sqljam.vdb.VectorIndex;
import com.github.sqljam.vdb.VectorType;

/**
 * @Description: VdbDialectTest tests the statements and values of the vdb dialects without a target: column types,
 *               vector types and indexes of the features of a target, upserts, and targets without vectors
 * @Author: Fred Feng
 * @Date: 24/10/2026
 * @Version 1.0.0
 */
class VdbDialectTest {

    static VdbCollection collection() {
        VdbCollection collection = new VdbCollection("sjv-doc");
        collection.setRemarks("Docs of 'SqlJam'");
        VdbField id = new VdbField("id", VdbField.Kind.BIGINT);
        id.setPrimaryKey(true);
        id.setNullable(false);
        collection.addField(id);
        VdbField title = VdbField.varchar("title", 255);
        title.setRemarks("Title");
        collection.addField(title);
        collection.addField(new VdbField("Title", VdbField.Kind.TEXT));
        for (VdbField.Kind kind : new VdbField.Kind[]{VdbField.Kind.SMALLINT, VdbField.Kind.INTEGER,
                VdbField.Kind.REAL, VdbField.Kind.DOUBLE, VdbField.Kind.BOOLEAN, VdbField.Kind.TIMESTAMP,
                VdbField.Kind.BINARY, VdbField.Kind.JSON, VdbField.Kind.TEXT}) {
            collection.addField(new VdbField("c_" + kind.name().toLowerCase(), kind));
        }
        collection.addField(VdbField.varchar("long_text", 70000));
        VdbField embedding = VdbField.vector("embedding", VectorType.dense(VectorType.Element.FLOAT32, 1024));
        embedding.setNullable(false);
        embedding.setVectorIndex(new VectorIndex(VectorIndex.Kind.HNSW, VectorIndex.Distance.COSINE, Map.of("m",
                "16", "ef_construction", "64")));
        collection.addField(embedding);
        VdbField half = VdbField.vector("half", VectorType.dense(VectorType.Element.FLOAT16, 2560));
        half.setVectorIndex(new VectorIndex(VectorIndex.Kind.IVF, VectorIndex.Distance.EUCLIDEAN, Map.of("lists",
                "4")));
        collection.addField(half);
        VdbField sparse = VdbField.vector("sparse", new VectorType(VectorType.Element.FLOAT32, 100, true));
        sparse.setVectorIndex(new VectorIndex(VectorIndex.Kind.HNSW, VectorIndex.Distance.DOT, null));
        collection.addField(sparse);
        return collection;
    }

    static VdbClient client(VdbCollection collection) {
        return new VdbClient() {
            @Override
            public VdbType getType() {
                return VdbType.QDRANT;
            }

            @Override
            public String getVersion() {
                return "1";
            }

            @Override
            public List<String> getCollections() {
                return List.of(collection.getName());
            }

            @Override
            public VdbCollection describe(String name) {
                return collection;
            }

            @Override
            public long count(VdbCollection collection, VdbQuery query) {
                return 0;
            }

            @Override
            public void read(VdbCollection collection, VdbQuery query, int pageSize, PageConsumer consumer) {
                consumer.accept(List.of(), true);
            }
        };
    }

    static VdbDialect full(VdbDialect dialect) {
        dialect.useFeatures(EnumSet.allOf(VdbFeature.class));
        return dialect;
    }

    static Stream<VdbDialect> dialects() {
        return Stream.of(new PostgreSQLVdbDialect(), new MySQLVdbDialect(), new MariaDBVdbDialect(),
                new OracleVdbDialect(), new SQLServerVdbDialect(), new Db2VdbDialect(), new DuckDBVdbDialect(),
                new ClickHouseVdbDialect()).map(VdbDialectTest::full);
    }

    static VdbCollectionMetaData metaData(VdbDialect dialect, VdbExportConfiguration configuration) {
        VdbCollection collection = collection();
        configuration.setDialect(dialect);
        configuration.addCollection(collection.getName(), null);
        return new VdbServerMetaData(client(collection), dialect, configuration).getCollectionMetaData().get(0);
    }

    static VdbDdlScripter.Table scripts(VdbDialect dialect, boolean recreated) throws Exception {
        VdbExportConfiguration configuration = new VdbExportConfiguration();
        configuration.setTargetSchema("sjit_dst");
        configuration.setTableRecreated(recreated);
        VdbCollectionMetaData metaData = metaData(dialect, configuration);
        VdbDdlScripter scripter = new VdbDdlScripter();
        metaData.getServerMetaData().accept(new DefaultVdbMetaDataVisitor(configuration, scripter));
        return scripter.getTable(metaData.getSchemaName(), metaData.getTableName());
    }

    @ParameterizedTest
    @MethodSource("dialects")
    void createsTables(VdbDialect dialect) throws Exception {
        VdbDdlScripter.Table table = scripts(dialect, true);
        List<String> scripts = table.getScripts();
        String create = table.getCreateTableScript();
        assertTrue(scripts.get(0).startsWith("DROP TABLE IF EXISTS"), scripts.get(0));
        assertTrue(create.startsWith("CREATE TABLE"), create);
        // The table of sjv-doc is sjv_doc, Title and title are different columns
        assertTrue(create.contains(dialect.getIdentifier("sjv_doc")), create);
        assertTrue(create.contains(dialect.getIdentifier("Title_2")), create);
        String vectorType = dialect.getTypeName(collection().findField("embedding").get());
        assertTrue(create.contains(vectorType), create);
        // MySQL, SQL Server and DuckDB have no vector indexes
        boolean indexed = !(dialect instanceof MySQLVdbDialect) || dialect instanceof MariaDBVdbDialect;
        indexed &= !(dialect instanceof SQLServerVdbDialect) && !(dialect instanceof DuckDBVdbDialect);
        assertEquals(indexed, !table.getIndexScripts().isEmpty(), dialect.getName());
        assertEquals(scripts.size(), table.getCreateScripts().size() + table.getIndexScripts().size());
        assertTrue(scripts(dialect, false).getBeforeStatements().isEmpty());
    }

    @Test
    void writesPostgreSQL() throws Exception {
        VdbDialect dialect = full(new PostgreSQLVdbDialect());
        VdbDdlScripter.Table table = scripts(dialect, true);
        String create = table.getCreateTableScript();
        assertTrue(create.contains("\"embedding\" vector(1024) NOT NULL"), create);
        assertTrue(create.contains("\"half\" halfvec(2560)"), create);
        assertTrue(create.contains("\"sparse\" sparsevec(100)"), create);
        assertTrue(create.contains("\"c_timestamp\" timestamptz"), create);
        assertTrue(create.contains("\"long_text\" varchar(70000)"), create);
        assertTrue(create.contains("CONSTRAINT \"pk_sjv_doc\" PRIMARY KEY (\"id\")"), create);
        assertTrue(table.getCommentStatements().contains("COMMENT ON TABLE \"sjit_dst\".\"sjv_doc\" IS 'Docs of"
                + " ''SqlJam'''"), String.valueOf(table.getCommentStatements()));
        assertEquals(List.of("CREATE INDEX IF NOT EXISTS \"idx_sjv_doc_embedding\" ON \"sjit_dst\".\"sjv_doc\""
                        + " USING hnsw (\"embedding\" vector_cosine_ops) WITH (m = 16, ef_construction = 64)",
                "CREATE INDEX IF NOT EXISTS \"idx_sjv_doc_half\" ON \"sjit_dst\".\"sjv_doc\" USING ivfflat (\"half\""
                        + " halfvec_l2_ops) WITH (lists = 4)",
                "CREATE INDEX IF NOT EXISTS \"idx_sjv_doc_sparse\" ON \"sjit_dst\".\"sjv_doc\" USING hnsw (\"sparse\""
                        + " sparsevec_ip_ops)"), table.getIndexScripts());

        VdbCollectionMetaData metaData = metaData(dialect, new VdbExportConfiguration());
        List<VdbDialect.Column> columns = metaData.getColumns();
        String upsert = dialect.getUpsertStatement("s", "t", columns, List.of("id"));
        assertTrue(upsert.contains("ON CONFLICT (\"id\") DO UPDATE SET \"title\" = EXCLUDED.\"title\""), upsert);
        assertTrue(upsert.contains("CAST(? AS vector(1024))"), upsert);
        assertTrue(dialect.getUpsertStatement("s", "t", columns.subList(0, 1), List.of("id"))
                .endsWith("ON CONFLICT (\"id\") DO NOTHING"));
        VdbDialect.Column sparse = column(columns, "sparse");
        assertEquals("{3:0.5}/100", dialect.getJdbcValue("{3:0.5}/100", sparse));
        VdbDialect.Column time = column(columns, "c_timestamp");
        assertEquals("2024-01-01 08:30:00.000000+00:00", dialect.getJdbcValue(OffsetDateTime.parse(
                "2024-01-01T08:30Z"), time));
        assertEquals(Types.VARCHAR, dialect.getNullSqlType(time));
        assertEquals("12", dialect.getJdbcValue(12L, column(columns, "title")));
        assertThrows(VdbException.class, () -> dialect.getJdbcValue("not a vector", column(columns,
                "embedding")));
    }

    static VdbDialect.Column column(List<VdbDialect.Column> columns, String name) {
        return columns.stream().filter(column -> column.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void writesMySQLAndMariaDB() throws Exception {
        VdbDialect mysql = full(new MySQLVdbDialect());
        String create = scripts(mysql, true).getCreateTableScript();
        assertTrue(create.contains("`embedding` VECTOR(1024) NOT NULL"), create);
        assertTrue(create.contains("`title` VARCHAR(255) COMMENT 'Title'"), create);
        assertTrue(create.contains("`long_text` LONGTEXT"), create);
        // Sparse vectors are text
        assertTrue(create.contains("`sparse` LONGTEXT"), create);
        assertTrue(scripts(mysql, true).getIndexScripts().isEmpty());
        List<VdbDialect.Column> columns = metaData(mysql, new VdbExportConfiguration()).getColumns();
        byte[] bytes = (byte[]) mysql.getJdbcValue("[1.5,-2]", column(columns, "embedding"));
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1.5f, buffer.getFloat());
        assertEquals(-2f, buffer.getFloat());
        assertEquals(Timestamp.valueOf("2024-01-01 08:30:00"), mysql.getJdbcValue(OffsetDateTime.parse(
                "2024-01-01T10:30+02:00"), column(columns, "c_timestamp")));
        assertTrue(mysql.getUpsertStatement("s", "t", columns, List.of("id")).contains(" AS new_row ON DUPLICATE"
                + " KEY UPDATE `title` = new_row.`title`"));
        assertTrue(mysql.getUpsertStatement("s", "t", columns.subList(0, 1), List.of("id"))
                .endsWith("`id` = new_row.`id`"));
        assertEquals("ALTER TABLE `s`.`t` COMMENT = 'a\\\\b'", mysql.getTableCommentStatement("s", "t", "a\\b"));

        VdbDialect mariadb = full(new MariaDBVdbDialect());
        // One vector index of a table
        assertEquals(List.of("CREATE VECTOR INDEX `idx_sjv_doc_embedding` ON `sjit_dst`.`sjv_doc` (`embedding`)"
                + " M=16 DISTANCE=cosine"), scripts(mariadb, true).getIndexScripts());
        assertTrue(mariadb.getUpsertStatement("s", "t", columns, List.of("id")).contains("`title` = VALUES("
                + "`title`)"));
        List<VdbDialect.Column> keys = List.of(new VdbDialect.Column("id", VdbField.varchar("id", 512),
                "VARCHAR(512)"));
        assertFalse(mariadb.isVectorIndexAllowed(keys));
        assertTrue(mariadb.isVectorIndexAllowed(List.of(column(columns, "id"))));
    }

    @Test
    void writesOracle() throws Exception {
        VdbDialect dialect = full(new OracleVdbDialect());
        VdbDdlScripter.Table table = scripts(dialect, true);
        String create = table.getCreateTableScript();
        assertTrue(create.contains("\"EMBEDDING\" VECTOR(1024, FLOAT32) NOT NULL"), create);
        assertTrue(create.contains("\"HALF\" VECTOR(2560, FLOAT32)"), create);
        assertTrue(create.contains("\"SPARSE\" VECTOR(100, FLOAT32, SPARSE)"), create);
        assertTrue(create.contains("\"C_JSON\" JSON"), create);
        assertTrue(create.contains("\"LONG_TEXT\" CLOB"), create);
        assertEquals(List.of("CREATE VECTOR INDEX \"IDX_SJV_DOC_EMBEDDING\" ON \"SJIT_DST\".\"SJV_DOC\""
                        + " (\"EMBEDDING\") ORGANIZATION INMEMORY NEIGHBOR GRAPH DISTANCE COSINE PARAMETERS (TYPE HNSW,"
                        + " NEIGHBORS 16, EFCONSTRUCTION 64)",
                "CREATE VECTOR INDEX \"IDX_SJV_DOC_HALF\" ON \"SJIT_DST\".\"SJV_DOC\" (\"HALF\") ORGANIZATION"
                        + " NEIGHBOR PARTITIONS DISTANCE EUCLIDEAN PARAMETERS (TYPE IVF, NEIGHBOR PARTITIONS 4)"),
                table.getIndexScripts());
        List<VdbDialect.Column> columns = metaData(dialect, new VdbExportConfiguration()).getColumns();
        assertEquals("[100,[2],[0.5]]", dialect.getJdbcValue("{3:0.5}/100", column(columns, "sparse")));
        String upsert = dialect.getUpsertStatement("s", "t", columns, List.of("id"));
        assertTrue(upsert.startsWith("MERGE INTO \"S\".\"T\" tgt USING (SELECT ? \"ID\""), upsert);
        assertTrue(upsert.contains("TO_VECTOR(?, 1024, FLOAT32) \"EMBEDDING\""), upsert);
        assertEquals(Types.CLOB, dialect.getNullSqlType(column(columns, "embedding")));

        // Without vector memory HNSW indexes are IVF indexes
        dialect.useFeatures(EnumSet.of(VdbFeature.VECTOR, VdbFeature.IVF_INDEX));
        assertTrue(scripts(dialect, true).getIndexScripts().get(0).contains("NEIGHBOR PARTITIONS DISTANCE COSINE"));
    }

    @Test
    void writesSQLServerAndDb2() throws Exception {
        VdbDialect sqlserver = full(new SQLServerVdbDialect());
        String create = scripts(sqlserver, true).getCreateTableScript();
        assertTrue(create.contains("[embedding] VECTOR(1024) NOT NULL"), create);
        assertTrue(create.contains("[half] VECTOR(2560, float16)"), create);
        assertTrue(create.contains("PRIMARY KEY NONCLUSTERED ([id])"), create);
        assertTrue(create.contains("[c_timestamp] DATETIMEOFFSET(6)"), create);
        assertTrue(scripts(sqlserver, true).getCommentStatements().isEmpty());
        List<VdbDialect.Column> columns = metaData(sqlserver, new VdbExportConfiguration()).getColumns();
        String merge = sqlserver.getUpsertStatement("s", "t", columns, List.of("id"));
        assertTrue(merge.startsWith("MERGE INTO [s].[t] tgt USING (VALUES (?, ?"), merge);
        assertTrue(merge.endsWith(");"), merge);

        VdbDialect db2 = full(new Db2VdbDialect());
        VdbDdlScripter.Table table = scripts(db2, true);
        create = table.getCreateTableScript();
        assertTrue(create.contains("\"EMBEDDING\" VECTOR(1024, FLOAT32) NOT NULL"), create);
        assertTrue(create.contains("\"TITLE\" VARCHAR(765)"), create);
        assertTrue(create.contains("\"HALF\" VECTOR(2560, FLOAT32)"), create);
        assertTrue(create.contains("\"SPARSE\" CLOB(2G)"), create);
        // 1024 floats are 4096 bytes of a key of at most 8000 bytes
        assertEquals(List.of("CREATE VECTOR INDEX \"SJIT_DST\".\"IDX_SJV_DOC_EMBEDDING\" ON \"SJIT_DST\".\"SJV_DOC\""
                + " (\"EMBEDDING\") WITH DISTANCE COSINE"), table.getIndexScripts());
        columns = metaData(db2, new VdbExportConfiguration()).getColumns();
        String upsert = db2.getUpsertStatement("s", "t", columns, List.of("id"));
        assertTrue(upsert.contains("VECTOR(CAST(? AS CLOB(2G)), 1024, FLOAT32)"), upsert);
        assertTrue(upsert.contains("CAST(? AS BIGINT)"), upsert);
        assertEquals(Types.CLOB, db2.getNullSqlType(column(columns, "embedding")));
        assertEquals(Types.TIMESTAMP, db2.getNullSqlType(column(columns, "c_timestamp")));
    }

    @Test
    void writesDuckDBAndClickHouse() throws Exception {
        VdbDialect duckdb = full(new DuckDBVdbDialect());
        String create = scripts(duckdb, true).getCreateTableScript();
        assertTrue(create.contains("\"embedding\" FLOAT[1024] NOT NULL"), create);
        assertTrue(create.contains("\"sparse\" VARCHAR"), create);
        List<VdbDialect.Column> columns = metaData(duckdb, new VdbExportConfiguration()).getColumns();
        assertTrue(duckdb.getInsertStatement("main", "t", columns).contains("CAST(? AS FLOAT[1024])"));
        assertEquals("2024-01-01 08:30:00.000000+00:00", duckdb.getJdbcValue(OffsetDateTime.parse(
                "2024-01-01T08:30Z"), column(columns, "c_timestamp")));
        assertEquals(Types.VARCHAR, duckdb.getNullSqlType(column(columns, "c_timestamp")));

        VdbDialect clickhouse = full(new ClickHouseVdbDialect());
        VdbDdlScripter.Table table = scripts(clickhouse, true);
        create = table.getCreateTableScript();
        assertTrue(create.contains("`embedding` Array(Float32)"), create);
        assertTrue(create.contains("`title` Nullable(String) COMMENT 'Title'"), create);
        assertTrue(create.endsWith(") ENGINE = MergeTree ORDER BY (`id`)"), create);
        assertFalse(create.contains("PRIMARY KEY"), create);
        // Missing vectors are empty arrays, which are not indexed
        assertEquals(1, table.getIndexScripts().size());
        assertTrue(table.getIndexScripts().get(0).contains("vector_similarity('hnsw', 'cosineDistance', 1024,"
                + " 'bf16', 16, 64)"));
        columns = metaData(clickhouse, new VdbExportConfiguration()).getColumns();
        assertEquals("[]", clickhouse.getJdbcValue(null, column(columns, "half")));
        assertEquals("2024-01-01 08:30:00.000000", clickhouse.getJdbcValue(OffsetDateTime.parse(
                "2024-01-01T10:30+02:00"), column(columns, "c_timestamp")));
        assertFalse(clickhouse.isUpsertSupported());
        assertFalse(clickhouse.isTransactionSupported());
        List<VdbDialect.Column> all = columns;
        assertThrows(UnsupportedOperationException.class, () -> clickhouse.getUpsertStatement("s", "t", all,
                List.of("id")));
        assertNull(clickhouse.getColumnCommentStatement("s", "t", "c", "x"));
        assertEquals("ALTER TABLE `s`.`t` MODIFY COMMENT 'x'", clickhouse.getTableCommentStatement("s", "t", "x"));
        assertTrue(clickhouse.getTableOptions(List.of()).endsWith("ORDER BY tuple()"));
    }

    /**
     * A target without vectors keeps vectors as text and has no vector indexes
     */
    @ParameterizedTest
    @MethodSource("dialects")
    void degradesWithoutVectors(VdbDialect dialect) throws Exception {
        dialect.useFeatures(EnumSet.noneOf(VdbFeature.class));
        assertFalse(dialect.isVectorSupported());
        VdbField embedding = collection().findField("embedding").get();
        String type = dialect.getTypeName(embedding);
        assertFalse(dialect.isVectorTypeName(embedding, type), type);
        VdbDdlScripter.Table table = scripts(dialect, false);
        assertTrue(table.getIndexScripts().isEmpty(), String.valueOf(table.getIndexScripts()));
        List<VdbDialect.Column> columns = metaData(dialect, new VdbExportConfiguration()).getColumns();
        assertEquals("[1.0,2.0]", dialect.getJdbcValue("[1.0,2.0]", column(columns, "embedding")));
    }

    @ParameterizedTest
    @MethodSource("dialects")
    void quotesIdentifiers(VdbDialect dialect) {
        String quoted = dialect.quoteIdentifier("a\"b`c]d");
        assertTrue(quoted.length() > "a\"b`c]d".length());
        String limited = dialect.getLimitedIdentifier("x".repeat(300));
        assertEquals(dialect.getMaxIdentifierLength(), limited.length());
        assertEquals(dialect.getIdentifier("t"), dialect.getQualifiedTableName(null, "t"));
        assertTrue(dialect.getName().length() > 1);
        assertNull(dialect.getProductVersion());
        assertTrue(dialect.getFeatures().contains(VdbFeature.VECTOR));
        List<String> names = Stream.of("a", "b").collect(Collectors.toList());
        assertEquals(dialect.getIdentifier("a") + ", " + dialect.getIdentifier("b"), dialect.getIdentifiers(names));
    }

    @Test
    void namesTablesOfCollections() {
        assertEquals("sjv_doc", VdbCollectionMetaData.getDefaultTableName("sjv-doc"));
        assertEquals("t_1doc", VdbCollectionMetaData.getDefaultTableName("1doc"));
        assertEquals("文档_a", VdbCollectionMetaData.getDefaultTableName("文档.a"));
        assertArrayEquals(new String[0], new VdbDialect.Column("c", new VdbField("c", VdbField.Kind.TEXT),
                "text").getField().getProperties().keySet().toArray(new String[0]));
    }
}
