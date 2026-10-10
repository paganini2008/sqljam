# SqlJam: Move Tables Across Databases with Schema, Data and All

Tables, indexes, constraints, sequences, partitions and rows, in one go.

![SqlJam main window](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/main-dark.png)

## Overview

**SqlJam** is a Java 17 + JavaFX desktop tool that copies tables between relational and OLAP databases (**MySQL, MariaDB, PostgreSQL, Oracle, SQL Server, H2, SQLite, DuckDB, ClickHouse** and more) in any direction. Import directly into a target database or into the same schema as copies, or save a self-describing **export package** of SQL or **Parquet** files and import it later.

Source: https://github.com/paganini2008/sqljam

## What Problem Does It Solve?

The hard part of a cross-database migration is not INSERT, it is the details: type names, identity columns, default value syntax, unsigned overflow, timestamps shifted by time zones, BLOBs inflating scripts, and old servers rejecting new syntax.

SqlJam reads one metadata tree and generates SQL with a dialect chosen by target type and version:

- **Dialect differences** → types, identifiers, defaults, identity and literals translated by the dialect of each target
- **Old versions** → version subclasses: Oracle11gDialect, PostgreSQL9Dialect, MySQL56Dialect, SQLServer2008Dialect
- **Missing keys and indexes** → primary keys, indexes, foreign keys, comments, sequences and partitions are copied
- **Huge scripts** → data files split at 10 MB: orders.sql, orders_2.sql …
- **LOBs** → stored as files and restored by primary key
- **Scripts without context** → manifest.json with source, target, options, SHA-256 per file and row counts
- **Analytics needs Parquet** → Parquet packages through an embedded DuckDB, Parquet files of other tools loaded into any table
- **Warehouses apart from OLTP** → DuckDB and ClickHouse data sources, grouped as OLAP databases
- **Only part of a table** → a data viewer query (columns, WHERE, GROUP BY, ORDER BY) and export of its rows
- **A copy next to the original** → copies in the same schema with {table}_copy, keys and indexes renamed
- **Custom and modern types** → PostgreSQL enums and domains, vectors, unions, maps, tuples and variants

## Quick Start

```
git clone git@github.com:paganini2008/sqljam.git && cd sqljam
./mvnw -DskipTests package
bin/sqljam.sh
```

On Windows use mvnw.cmd and bin\sqljam.bat. The build writes everything to bin/: a runnable jar for each platform, the launchers, sqljam.properties and sqljam.vmoptions. Double click the jar of your platform, or run the launcher. Edit sqljam.properties or sqljam.vmoptions and restart, no rebuild needed.

A splash screen shows the loading of the configuration, the data sources and the JDBC drivers:

![Splash screen](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/splash.png)

A short tutorial video walks through all of the steps below with the example database: https://github.com/paganini2008/sqljam/blob/master/docs/assets/sqljam-tutorial.mp4

Step 1. log in to a data source. The first start brings an Example Shop (H2) data source with a few e-commerce tables to try everything right away. Example on the login page or Help → Restore Example Database brings it back at any time:

![Login](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/login.png)

Step 2. choose tables and a target in the export wizard. A target data source that does not exist yet is created from the + button next to it:

![Export wizard](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/export-wizard.png)

Step 3. watch the progress:

![Progress](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/progress.png)

Step 4. narrow the rows of a table on the Data tab and export just those rows. Right-click a connected data source to Disconnect when you are done, and Help → About SqlJam shows the version, the home page and the repository:

![Data query](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/table-data.png)

Step 5. look inside an export package, Parquet files included:

![Package viewer](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/package-viewer.png)

Step 6. load Parquet files of other tools into a table:

![Parquet files](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/import-parquet.png)

## Requirements

Only Java 17+ is needed. JavaFX and the JDBC drivers are inside the runnable jar, and the Maven Wrapper builds without a Maven installation.

Platforms:

- ✅ Windows 10 / 11 (x64): sqljam-<version>-win.jar, sqljam.bat
- ✅ macOS Apple Silicon: sqljam-<version>-mac-aarch64.jar, sqljam.sh
- ✅ macOS Intel: sqljam-<version>-mac.jar, sqljam.sh
- ✅ Linux x64 (GTK 3): sqljam-<version>-linux.jar, sqljam.sh

Databases:

- MySQL 5.5 to 9.x
- PostgreSQL 9.x to 16
- Oracle 11g to 23ai
- SQL Server 2008 to 2022
- MariaDB 10.3 to 11.x
- H2 2.x, SQLite 3.x
- DuckDB 1.x and ClickHouse 24.x to 26.x (OLAP)
- All JDBC drivers are bundled

## How It Works

![How SqlJam works](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/architecture.png)

- **Read metadata once**: per-database operations complete comments, identity, generated columns, partitions and sequences.
- **Generate for the target**: DbType.createDialect(major, minor) picks the version subclass.
- **Stream rows**: count rows for percentage progress, page by primary key, normalize values, then batch insert or write SQL literals.
- **Parquet**: rows pass an embedded DuckDB workspace, COPY TO writes Parquet files and read_parquet loads them back.

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
- MySQL / Oracle VECTOR → PostgreSQL text · SQL Server nvarchar(max) · same type kept
- ClickHouse Array(T) / Map(K, V) → PostgreSQL text · Oracle CLOB · SQL Server nvarchar(max)

## Configuration

All settings live in bin/sqljam.properties next to the runnable jar. Settings changed in the UI are saved to ~/.sqljam/sqljam.properties and win over the file.

- **sqljam.export.max-file-size** = 10485760, max bytes of a data file
- **sqljam.export.data-file-strategy** = SINGLE_FILE, or FILE_PER_TABLE
- **sqljam.export.lob-separated** = true, write LOBs as files
- **sqljam.export.page-size** = 5000, rows per page, tuned by benchmark
- **sqljam.export.lob-page-size** = 100, rows per page for tables with LOB columns
- **sqljam.import.batch-size** = 1000, INSERT statements per batch when importing a package
- **sqljam.ui.theme** = Primer Dark, 7 themes
- **sqljam.pool.maximum-size** = 10, connection pool size

## Performance

![Benchmark](https://raw.githubusercontent.com/paganini2008/sqljam/master/docs/assets/benchmark.png)

200,000 rows × 6 columns, Apple M2 Max, 32 GB, JDK 17 (MySQL/PostgreSQL local, Oracle/SQL Server in Docker, default settings):

- H2 → Oracle 23ai: 2.1 s, 95.3k rows/s
- H2 → export package (32 MB, 4 files): 2.9 s, 69.4k rows/s
- H2 → PostgreSQL 16: 3.2 s, 62.4k rows/s
- H2 → MySQL 9.6: 3.3 s, 60.7k rows/s
- H2 → SQL Server 2022: 5.0 s, 40.1k rows/s
- Export package → PostgreSQL: 4.5 s, 44.5k rows/s

Quality: 578 tests (full cross-database import matrix with every column type of every database, export package and Parquet round trips, copies in the same schema, old-version SQL executed on real servers, UI and boundary tests), 91% line coverage.

## Design Trade-offs

- **Table-level focus**: tables, keys, indexes, constraints, sequences, partitions and comments.
- **Rows paged by primary key**: stable, deterministic reads on every database.
- **Tables copied one by one**: predictable load on production servers.
- **Plain SQL in packages**: readable and portable. Direct import uses batched inserts for speed.
- **Version-specific SQL**: older servers get the syntax they understand.
- **Parquet through DuckDB**: one embedded engine writes and reads Parquet, no Hadoop libraries.
- **Grouped rows are for viewing**: exports carry real table rows.
- **Copies need their own names**: a copy in the same schema takes a name such as {table}_copy.

## Summary

1. Any-to-any copies. Every cross-database pair passes automated tests.
2. Dialects are chosen by type + version. Old versions are subclasses.
3. Tables, indexes, constraints, sequences, partitions and comments move together.
4. Export package = SQL + LOB files + manifest.json. Export and import are paired.
5. Data files split at 10 MB by default. LOBs stored separately.
6. Timestamps, unsigned numbers, bits, money, UUID and JSON survive the trip.
7. Pooled connections and batch inserts: 40k to 95k rows/s for direct imports.
8. A mainstream dark UI with 7 themes, percentage progress and cancel.
9. Parquet packages and Parquet files of other tools, plus DuckDB and ClickHouse as OLAP data sources.
10. Query the rows of a table, export just those rows, or copy tables inside the same schema.

GitHub: https://github.com/paganini2008/sqljam
