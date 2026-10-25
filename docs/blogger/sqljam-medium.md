# SqlJam: Move Tables Across Databases with Schema, Data and All

![SqlJam logo](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/logo.png)

Tables, indexes, constraints, sequences, partitions and rows, in one go.

![SqlJam main window](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/main-dark.png)

## Overview

**SqlJam** is a Java 17 + JavaFX desktop tool that copies tables between relational and OLAP databases (**MySQL, MariaDB, PostgreSQL, Oracle, SQL Server, Db2, H2, SQLite, Microsoft Access, DuckDB, ClickHouse** and more) in any direction. Import directly into a target database or into the same schema as copies, or save a self-describing **export package** of SQL, **Parquet** or **CSV** files and import it later. Imports into a database run in **one transaction** which an error rolls back, and rows are **inserted, updated or skipped by key**.

Source: https://github.com/paganini2008/sqljam

## What Problem Does It Solve?

The hard part of a cross-database migration is not INSERT, it is the details: type names, identity columns, default value syntax, unsigned overflow, timestamps shifted by time zones, BLOBs inflating scripts, and old servers rejecting new syntax.

SqlJam reads one metadata tree and generates SQL with a dialect chosen by target type and version:

- **Dialect differences** → types, identifiers, defaults, identity and literals translated by the dialect of each target
- **Old versions** → version subclasses: Oracle11gDialect, PostgreSQL9Dialect, MySQL56Dialect, SQLServer2008Dialect
- **Missing keys and indexes** → primary keys, indexes, foreign keys, comments, sequences and partitions of every kind (range, list, hash, interval, composite, reference, nested) are copied, partitioned tables show their partitioning in the tree
- **Huge scripts** → data files split by size: SQL and CSV at 10 MB (orders.sql, orders_2.sql …), Parquet at 256 MB, imported part by part in order
- **LOBs** → stored as files and restored by primary key
- **Scripts without context** → manifest.json with source, target, options, SHA-256 per file and row counts
- **Analytics needs Parquet** → Parquet packages through an embedded DuckDB, Parquet files of other tools loaded into any table
- **Spreadsheets need CSV** → CSV packages with your delimiter, quote, NULL text, header, encoding and date format, exact types kept in manifest.json, CSV files of other tools loaded too
- **An import went wrong** → one transaction by default, rolled back on any error, or commit every N rows. Skip rows with errors goes on and logs each skipped row. Confirm before commit reviews a successful import first
- **Did every row arrive?** → a migration report for every import and export, also when it fails or is cancelled: rows per table, skipped rows with their errors, target rows counted afterwards, created objects and where it stopped. Import History lists every report, Markdown, HTML or JSON on export
- **A copy has to stay up to date** → Sync Tasks copy the changed rows by an update time or an increasing key, every N minutes or every day, run by SqlJam or by the system scheduler when it is closed, vector collections included, a report for every run
- **Rows exist already** → update or insert by key, or skip existing by key: the primary key or the columns of a unique index match the rows
- **Warehouses apart from OLTP** → DuckDB and ClickHouse data sources, grouped as OLAP databases
- **Only part of a table** → a data viewer query (columns, WHERE, GROUP BY, ORDER BY) and export of its rows
- **Rows of a join or a report** → the SQL Query editor with syntax highlighting shows the first rows and exports all rows of the query as a table
- **Columns named or typed differently** → Map Columns… renames a column, gives it a type of the target or leaves it out, for tables and collections, and keys, indexes and foreign keys follow the new names
- **A copy next to the original** → copies in the same schema with {table}_copy, keys and indexes renamed
- **Custom and modern types** → PostgreSQL enums and domains, unions, maps, tuples and variants
- **Embeddings move with their search** → vectors of pgvector, MySQL, MariaDB, Oracle, SQL Server, Db2, DuckDB and ClickHouse keep their dimensions, vector indexes keep their distances and options
- **Embeddings live in a vector database** → Milvus, Qdrant, Weaviate, Pinecone, Elasticsearch, OpenSearch and Chroma sit in the tree as vector databases. Browse their databases, collections, partitions, tenants and namespaces, page through the records, then import collections straight into a database with vectors: ids, vectors, a column of each field and the whole payload as JSON
- **Database behind a bastion host** → an SSH tunnel per data source with a password or a private key, host keys trusted on first use, a broken tunnel opens again by itself
- **Which server, how healthy?** → Info shows the overview and the health of a data source or a vector database, and a Dashboard of live charts: sessions, transactions, rows, cache, locks, network, CPU and memory
- **Long values, rows for a ticket** → one line per cell, a double click shows the whole value, Copy Rows As CSV, TSV, JSON or Markdown
- **Many data sources** → grouped as relational, OLAP and vector databases with their logos, a filter above the tree

## Quick Start

```
git clone git@github.com:paganini2008/sqljam.git && cd sqljam
./mvnw -DskipTests package
bin/sqljam.sh
```

On Windows use mvnw.cmd and bin\sqljam.bat. The build writes everything to bin/: a runnable jar for each platform, the launchers, sqljam.properties and sqljam.vmoptions. Double click the jar of your platform, or run the launcher. Edit sqljam.properties or sqljam.vmoptions and restart, no rebuild needed.

A splash screen shows the loading of the configuration, the data sources and the JDBC drivers:

![Splash screen](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/splash.png)

Three tutorial videos of about 2 minutes each walk through SqlJam: SQL databases https://github.com/paganini2008/sqljam/blob/master/docs/assets/sqljam-sql.mp4, vector databases https://github.com/paganini2008/sqljam/blob/master/docs/assets/sqljam-vectors.mp4, and scheduled sync with reports https://github.com/paganini2008/sqljam/blob/master/docs/assets/sqljam-sync.mp4

Step 1. SqlJam opens its main window with the data sources in the tree and connects again those which were connected when it was closed. Expand a data source to connect it, + on the toolbar creates one. The first start brings an Example Shop (H2) data source with a few e-commerce tables to try everything right away. Help → Restore Example Database brings it back at any time:

![Data source](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/data-source.png)

Step 2. choose tables and a target in the export wizard. A target data source that does not exist yet is created from the + button next to it:

![Export wizard](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/export-wizard.png)

Step 3. watch the progress:

![Progress](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/progress.png)

Step 4. narrow the rows of a table on the Data tab and export just those rows. Right-click a connected data source to Disconnect when you are done, and Help → About SqlJam shows the version, the home page and the repository:

![Data query](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/table-data.png)

Step 5. open SQL Query on the toolbar, run a query with Ctrl+Enter (⌘+Enter on macOS) and press Export… to send all its rows to SQL, Parquet, CSV or another database:

![SQL Query](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/sql-editor.png)

Step 6. look inside an export package, Parquet files included:

![Package viewer](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/package-viewer.png)

Step 7. load Parquet files of other tools into a table:

![Parquet files](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/import-parquet.png)

Step 8. Create a data source of the Vector databases category, with an API key, a user and password or neither. Expand it in the tree to its databases, collections and partitions, open a collection to page through its records, narrowed by conditions, a native filter or a nearest neighbor search, and Import… writes the checked collections into a data source with vectors. Only such data sources are offered as targets.

![Vector database collection](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/vdb-collection.png)

Step 9. A double click on a cell shows its whole value, Copy Rows As copies the selected rows as CSV, TSV, JSON or Markdown, and Info shows the server and its health. Connect through SSH tunnel in the data source form reaches a database behind a bastion host.

![Database info](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/database-info.png)

![Migration report](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/report.png)

![Import history](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/import-history.png)

![Sync tasks](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/sync-tasks.png)

![Dashboard](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/dashboard.png)

![Column mapping](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/column-mapping.png)

![Feature map](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/feature-map.png)

## Requirements

Only Java 17+ is needed. JavaFX and the JDBC drivers are inside the runnable jar, and the Maven Wrapper builds without a Maven installation.

Platforms:

- ✅ Windows 10 / 11 (x64): sqljam-<version>-win.jar, sqljam.bat
- ✅ macOS Apple Silicon: sqljam-<version>-mac-aarch64.jar, sqljam.sh
- ✅ macOS Intel: sqljam-<version>-mac.jar, sqljam.sh
- ✅ Linux x64 (GTK 3): sqljam-<version>-linux.jar, sqljam.sh

Databases:

- MySQL 5.5 to 9.x
- PostgreSQL 9.x to 17
- Oracle 11g to 23ai
- SQL Server 2008 to 2025
- MariaDB 10.3 to 11.x
- H2 2.x, SQLite 3.x
- Microsoft Access 2000 to 2019 (.accdb and .mdb files)
- DuckDB 1.x and ClickHouse 24.x to 26.x (OLAP)
- All JDBC drivers are bundled

Vector databases (sources only):

- Milvus 2.4 to 3.x
- Qdrant 1.10 to 1.19
- Weaviate 1.24 to 1.39
- Pinecone and Pinecone Local, API 2025-01
- Elasticsearch 8.0 to 8.19
- OpenSearch 2.4 to 2.19
- Chroma 1.0 to 1.5

## How It Works

![How SqlJam works](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/architecture.png)

- **Read metadata once**: per-database operations complete comments, identity, generated columns, partitions and sequences.
- **Generate for the target**: DbType.createDialect(major, minor) picks the version subclass.
- **Stream rows**: count rows for percentage progress, page by primary key, normalize values, then batch insert or write SQL literals.
- **Parquet and CSV**: rows pass an embedded DuckDB workspace, COPY TO writes the files, read_parquet or read_csv with the column types of manifest.json loads them back.
- **Transactions**: an import runs in one transaction, committed when it succeeds or after your confirmation. MySQL, MariaDB, Oracle and H2 commit around DDL, so keys and identity values are set after the commit.
- **Upserts and skips**: ON CONFLICT, ON DUPLICATE KEY UPDATE or MERGE of each database, kept tables, indexes and keys are skipped.
- **Skipped errors**: a failed page is written row by row under savepoints, each bad row is logged with its key and the others are kept.
- **Vector databases**: a line of its own next to the JDBC one. REST clients read the collections, a visitor of the collection tree builds the tables, and VdbImportHandler writes the pages into the target, whose vector features are probed first.

## Code Examples

### Example 1: export a MySQL database as a PostgreSQL package

```
ScriptExporter exporter = new ScriptExporter(new File("export"),
        DataFileStrategy.FILE_PER_TABLE, 10 * 1024 * 1024);
Exporter.ExportConfiguration config = exporter.getConfiguration();
config.setDbType(DbType.MYSQL);
config.setUrl(DbType.MYSQL.getUrl("localhost", 3306, "shop"));
config.setUsername("root");
config.setPassword("secret");
config.setIdReused(true);
exporter.setTargetDbType(DbType.POSTGRESQL);
exporter.exportDdlAndData();
```

Output:

```
export/
  schema.sql
  data/orders.sql
  data/orders_2.sql
  lob/
  lob-manifest.json
  constraints.sql
  manifest.json
```

The same tables as Parquet files:

```
ParquetExporter parquet = new ParquetExporter(new File("export-parquet"));
parquet.setTargetDbType(DbType.POSTGRESQL);
parquet.setCompression("ZSTD");
parquet.exportDdlAndData();

CsvExporter csv = new CsvExporter(new File("export-csv"));
csv.setDelimiter(";");
csv.exportDdlAndData();   // data/orders.csv, data/orders_2.csv ...
```

### Example 2: Oracle → SQL Server directly

```
ImportExporter importer = new ImportExporter();
Exporter.ExportConfiguration source = importer.getExportConfiguration();
source.setDbType(DbType.ORACLE);
source.setUrl(DbType.ORACLE.getUrl("ora-host", 1521, "ORCL"));
source.setUsername("hr");
source.setPassword("secret");
source.setIncludedSchemaNames(new String[]{"HR"});
ImportExportHandler.ImportConfiguration target = importer.getImportConfiguration();
target.setDbType(DbType.SQLSERVER);
target.setUrl(DbType.SQLSERVER.getUrl("mssql-host", 1433, "demo"));
target.setUsername("sa");
target.setPassword("secret");
target.setTargetSchemaName("hr");
importer.exportDdlAndData();
```

Output: tables, keys, indexes, foreign keys, comments and sequences in demo.hr. Identities continue from the imported max value.

Rows of a query and copies in the same schema:

```
source.getTableQueries().put("EMPLOYEES", new TableQuery(List.of("EMPLOYEE_ID", "NAME", "SALARY"),
        "SALARY > 1000", null, "SALARY DESC"));
target.setTableNamePattern("{table}_copy");
```

Type translation examples:

- MySQL bigint unsigned → PostgreSQL numeric(20, 0) · Oracle NUMBER(20,0) · SQL Server decimal(20,0)
- PostgreSQL jsonb → Oracle CLOB · SQL Server nvarchar(max) · MySQL json
- SQL Server datetime2(7) → PostgreSQL timestamp · Oracle TIMESTAMP(7) · MySQL datetime(6)
- PostgreSQL enum / domain → same type created in the target schema · varchar or the base type elsewhere
- MySQL / Oracle / SQL Server VECTOR(1024) → PostgreSQL vector(1024) · Oracle VECTOR(1024, FLOAT32) · SQL Server VECTOR(1024)
- PostgreSQL halfvec(2560) → Oracle VECTOR(2560, FLOAT32) · SQL Server VECTOR(2560, float16) · MySQL VECTOR(2560)
- ClickHouse Array(T) / Map(K, V) → PostgreSQL text · Oracle CLOB · SQL Server nvarchar(max)

A collection of a vector database goes straight into a target with vectors:

```java
try (VdbClient client = VdbType.QDRANT.createClient(new VdbSource(VdbType.QDRANT, "http://qdrant-host:6333"))) {
    VdbExportConfiguration configuration = new VdbExportConfiguration();
    configuration.setTargetSchema("public");
    configuration.addCollection("docs", "docs");
    configuration.setQuery(VdbQuery.all().where("lang = 'en'"));
    new VdbExporter(client, pgDataSource, configuration).export();
}
```

Output: public.docs with id, embedding vector(1024), a column of each payload field and payload jsonb, and an HNSW index of the cosine distance of the collection.

## Configuration

All settings live in bin/sqljam.properties next to the runnable jar. Settings changed in the UI are saved to ~/.sqljam/sqljam.properties and win over the file.

- **sqljam.export.max-file-size** = 10485760, max bytes of a SQL or CSV data file
- **sqljam.export.parquet-max-file-size** = 268435456, max bytes of a Parquet file
- **sqljam.export.csv-delimiter** = comma, delimiter of CSV files
- **sqljam.import.confirm-commit.reminder-minutes** = 10, reminder for an import still waiting for commit
- **sqljam.export.data-file-strategy** = SINGLE_FILE, or FILE_PER_TABLE
- **sqljam.export.lob-separated** = true, write LOBs as files
- **sqljam.export.page-size** = 5000, rows per page, tuned by benchmark
- **sqljam.export.lob-page-size** = 100, rows per page for tables with LOB columns
- **sqljam.import.batch-size** = 1000, INSERT statements per batch when importing a package
- **sqljam.ui.theme** = Primer Dark, 7 themes
- **sqljam.pool.maximum-size** = 10, connection pool size
- **sqljam.vdb.enabled** = true, shows the vector databases, false hides them
- **sqljam.vdb.connections.file** = ${sqljam.home}/vdb-connections.json, saved vector databases
- **sqljam.ssh.known-hosts.file** = ${sqljam.home}/ssh_known_hosts, host keys of the SSH servers of tunnels

## Performance

![Benchmark](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/benchmark.png)

200,000 rows × 6 columns, Apple M2 Max, 32 GB, JDK 17, databases in Docker on a server of the local network (about 100 Mbit/s), H2, DuckDB and the packages local, default settings:

- H2 → MySQL: 10.3 s, 19.5k rows/s
- H2 → MariaDB: 9.5 s, 21.1k rows/s
- H2 → PostgreSQL: 6.4 s, 31.3k rows/s
- H2 → Oracle: 4.2 s, 47.9k rows/s
- H2 → SQL Server: 12.0 s, 16.7k rows/s
- H2 → Db2: 9.3 s, 21.6k rows/s
- H2 → ClickHouse: 4.2 s, 47.4k rows/s
- H2 → DuckDB: 5.7 s, 35.1k rows/s
- H2 → SQL package: 2.6 s, 76.1k rows/s
- H2 → Parquet package: 5.7 s, 35.4k rows/s
- H2 → CSV package: 5.8 s, 34.7k rows/s
- SQL package → PostgreSQL: 24.3 s, 8.2k rows/s
- Qdrant → PostgreSQL (20,000 points, 256-d vectors and payload): 18.4 s, 1.1k rows/s

Quality: a full cross-database import matrix with every column type of every database, SQL, Parquet and CSV package round trips, commit and roll back verified on every database, copies in the same schema, old-version SQL executed on real servers, UI and boundary tests.

## Design Trade-offs

- **Table-level focus**: tables, keys, indexes, constraints, sequences, partitions and comments.
- **Rows paged by primary key**: stable, deterministic reads on every database.
- **Tables copied one by one**: predictable load on production servers.
- **Plain SQL in packages**: readable and portable. Direct import uses batched inserts for speed.
- **Version-specific SQL**: older servers get the syntax they understand.
- **Parquet and CSV through DuckDB**: one embedded engine writes and reads both, no Hadoop libraries.
- **One transaction by default**: an import is all or nothing, confirm before commit when you want to look first.
- **Grouped rows are for viewing**: exports carry real table rows.
- **Vector databases are sources**: their collections go into a relational database with vectors, which stays the end point of every copy.
- **Copies need their own names**: a copy in the same schema takes a name such as {table}_copy.

## Summary

1. Any-to-any copies. Every cross-database pair passes automated tests.
2. Dialects are chosen by type + version. Old versions are subclasses.
3. Tables, indexes, constraints, sequences, partitions and comments move together.
4. Export package = SQL + LOB files + manifest.json. Export and import are paired.
5. Data files split by size (SQL and CSV 10 MB, Parquet 256 MB) and imported in order. LOBs stored separately.
6. Timestamps, unsigned numbers, bits, money, UUID and JSON survive the trip.
7. Pooled connections and batch inserts: 40k to 95k rows/s for direct imports.
8. A mainstream dark UI with 7 themes, percentage progress and cancel.
9. Parquet and CSV packages and files of other tools, DuckDB and ClickHouse as OLAP data sources, imports in one transaction and rows updated or skipped by key.
10. A SQL Query editor exports the rows of any query through the same exits, columns of tables keep their types.
11. Query the rows of a table, export just those rows, or copy tables inside the same schema.
12. Collections of Milvus, Qdrant, Weaviate, Pinecone, Elasticsearch, OpenSearch and Chroma go straight into a database with vectors, previewed by conditions, native filters or nearest neighbor searches.

GitHub: https://github.com/paganini2008/sqljam
