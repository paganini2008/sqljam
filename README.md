<div align="center">

<img src="docs/assets/logo.png" alt="SqlJam logo" width="240">

# SqlJam

**Move tables across databases. Schema, data and all.**

SqlJam is a desktop import/export tool for relational and OLAP databases: MySQL, MariaDB, PostgreSQL, Oracle, SQL Server, Db2, H2, SQLite, DuckDB, ClickHouse and more.
It copies tables (with indexes, constraints, sequences and partitions) **directly into another database** or into the same schema as copies, or saves them as a **self-describing export package** of SQL, **Parquet** or **CSV** files that can be imported later. Imports into a database run in **one transaction** which an error rolls back as a whole, and rows are **inserted, updated or skipped by key**.

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-17-1f6feb)
![License](https://img.shields.io/badge/license-Apache%202.0-blue)
![Cross-database](https://img.shields.io/badge/cross--database%20matrix-passing-3fb950)

![SqlJam main window](docs/assets/main-dark.png)

</div>

---

## Features

| Problem | How SqlJam solves it |
|---|---|
| Different SQL dialects break hand-written migrations | One metadata model, **a dialect per target**: types, identifiers, defaults, identity and literals are translated |
| "Works on my 16, fails on their 9.4" | **Version-aware dialects** (`Oracle11gDialect`, `PostgreSQL9Dialect`, `MySQL56Dialect`, `SQLServer2008Dialect` …) chosen by detected or selected version |
| Tables arrive without keys, indexes or sequences | Copies **primary keys, unique/regular indexes, foreign keys, comments, sequences, identity values and partitions** |
| Huge `data.sql` files nobody can open | **Split data files** by size: `orders.sql`, `orders_2.sql` … and `orders.csv`, `orders_2.csv` … at 10 MB, `orders.parquet`, `orders_2.parquet` … at 256 MB, imported in the order of the parts |
| BLOB/CLOB literals explode scripts and hit 4000-byte limits | **LOBs are stored as files** (`lob/<table>/000001_<column>.blob`) and restored by primary key |
| Scripts lose context ("which DB? which version? complete?") | **`manifest.json`** records source, target, options, files (with SHA-256) and row counts, export and import are paired |
| Timestamps shift, unsigned values overflow, `bit` becomes garbage | Portable value pipeline: `LocalDateTime`, unsigned widening, bit strings, money, UUID, JSON, XML, arrays |
| Re-runs fail half way | Idempotent DDL (drop/create, `IF NOT EXISTS`, sequence restart), FKs created after data |
| Long jobs are a black box | **Percentage progress**, per-table progress, error log and cancel in the UI |
| Analytics teams want Parquet, not SQL | **Parquet packages** (ZSTD, Snappy, GZIP) through an embedded DuckDB, and **Parquet files of other tools** loaded into any table |
| Spreadsheets and text tools want CSV | **CSV packages** with your delimiter, quote, NULL text, header, encoding (UTF-8 with BOM for Excel, GBK and more) and date format, read back with the exact column types kept in `manifest.json`, and **CSV files of other tools** with detected delimiter and types |
| An import went wrong, now what? | **One transaction** by default: an error rolls back the whole import, or **commit every N rows** like other import tools. **Skip rows with errors** goes on and lists every skipped row in the log, which you can filter and save. **Confirm before commit** lets you review a successful import in one transaction, then **Commit** or **Roll Back** |
| Some rows exist already | **Update or insert by key** updates the rows of the same primary key, or of the columns you choose, and inserts the others. **Skip existing by key** keeps them and inserts the new rows only. Insert is the default |
| Warehouses live apart from OLTP databases | **DuckDB and ClickHouse data sources**, grouped as OLAP databases next to the relational ones |
| The rows come from a join or a report, not from one table | **SQL Query** editor with syntax highlighting: run the SQL, check the first rows, then export all rows of the query as a table into SQL, Parquet, CSV or another database |
| Only part of a table is needed | **Data viewer query**: columns, WHERE, GROUP BY and ORDER BY, then export the rows of the query. Missing target tables are created with the selected columns |
| A copy next to the original | **Copy in the same schema** with a table name such as `{table}_copy`. Keys, indexes and constraints get their own names and the source tables are protected |
| What is inside this package? | **Package viewer**: files with size and modified time, previews of manifest, SQL, LOB, Parquet and CSV files |
| Custom and modern types | PostgreSQL enums and domains, MySQL, MariaDB and Oracle vectors, DuckDB unions and fixed arrays, ClickHouse variants, maps and tuples |

---

## How It Works

![How SqlJam works](docs/assets/architecture.png)

```mermaid
flowchart LR
    S[(Source DB)] --> O[XxxMetaDataOperations]
    O --> T[Metadata tree<br/>catalog · schema · table · column · key · index · FK · partition · sequence]
    T --> V[MetaDataVisitor]
    V --> D[Target Dialect<br/>type + version subclass]
    D --> DS[DdlScripter]
    DS --> I[ImportExporter] --> TD[(Target DB)]
    DS --> E[ScriptExporter] --> P[/Export package<br/>schema.sql · data*.sql · lob/ · manifest.json/]
    DS --> PE[ParquetExporter · CsvExporter<br/>DuckDB workspace] --> PP[/Parquet or CSV package<br/>schema.sql · data/*.parquet or *.csv · manifest.json/]
    P --> SI[ScriptImporter] --> TD
    PP --> PI[ParquetImporter · CsvImporter] --> TD
```

| Component | Role |
|---|---|
| `Exporter` | Reads metadata, counts rows, pages rows (ordered by primary key) and feeds an `ExportHandler` |
| `ScriptExporter` / `ImportExporter` | Facades: export to a package / import into a database |
| `ScriptImporter` | Imports an export package (validates `manifest.json`, runs files in order, restores LOBs) |
| `ParquetExporter` / `ParquetImporter` | Parquet packages and Parquet files of other tools, written and read by an embedded DuckDB workspace |
| `CsvExporter` / `CsvImporter` | CSV packages (column types in `manifest.json`) and CSV files of other tools, through the same DuckDB workspace |
| `ManualTransaction` | Runs an import in one transaction which the user commits or rolls back. DDL which would commit the rows (MySQL, MariaDB, Oracle, H2) waits for the commit |
| `AbstractFileImporter` | Picks the importer of a package by the data format in its `manifest.json` |
| `TableQuery` | Columns, WHERE, GROUP BY and ORDER BY of the data viewer, used by exports per table |
| `Dialect` + `impexp.db.*` | Per-database SQL generation. Old versions are subclasses |
| `MetaDataOperations` + `impexp.db.*` | Per-database metadata fixes (comments, identity, generated columns, partitions, sequences) |
| `face.*` | JavaFX UI (AtlantaFX themes, Ikonli icons) |

---

## Requirements

Only **Java 17+** is needed to run SqlJam. JavaFX and all JDBC drivers are inside the runnable jar. Building from source needs no Maven installation either, the Maven Wrapper (`./mvnw`) is included.

| Platform | Runnable jar | Launcher | Supported |
|---|---|---|:---:|
| Windows 10 / 11 (x64) | `sqljam-<version>-win.jar` | `sqljam.bat` | ✅ |
| macOS Apple Silicon | `sqljam-<version>-mac-aarch64.jar` | `sqljam.sh` | ✅ |
| macOS Intel | `sqljam-<version>-mac.jar` | `sqljam.sh` | ✅ |
| Linux x64 (GTK 3) | `sqljam-<version>-linux.jar` | `sqljam.sh` | ✅ |

| Database | Tested on | Supported from |
|---|---|---|
| MySQL | 9.6 | 5.5 (`MySQL55/56/57Dialect`) |
| PostgreSQL | 16 | 9.x (`PostgreSQL9/10Dialect`) |
| Oracle | 23ai Free | 11g (`Oracle11g/12cDialect`) |
| SQL Server | 2022 | 2008 (`SQLServer2008Dialect`) |
| Db2 for Linux, UNIX and Windows | 12.1 | 11.1 |
| MariaDB | 11.8 | 10.3 |
| H2 | 2.3 | 2.x |
| SQLite | 3.46 | 3.x |
| DuckDB (OLAP) | 1.5 | 1.x |
| ClickHouse (OLAP) | 26.9 | 24.x |

All JDBC drivers are bundled, no extra downloads.

---

## Quick Start

```bash
git clone git@github.com:paganini2008/sqljam.git
cd sqljam
./mvnw -DskipTests package       # Windows: mvnw.cmd -DskipTests package, output goes to bin/
bin/sqljam.sh                    # Windows: bin\sqljam.bat, or double click the jar of your platform
```

```text
bin/
├── sqljam-1.0.0-SNAPSHOT-win.jar           # runnable jar of each platform,
├── sqljam-1.0.0-SNAPSHOT-mac-aarch64.jar   # JavaFX and all JDBC drivers inside
├── sqljam-1.0.0-SNAPSHOT-mac.jar
├── sqljam-1.0.0-SNAPSHOT-linux.jar
├── sqljam.sh / sqljam.bat                  # launchers, pick the jar of the platform
├── sqljam.properties                       # configuration, edit and restart
├── sqljam.vmoptions                        # JVM options, one per line
├── sqljam.png                              # Dock icon of macOS
├── sqljam-tutorial.mp4                     # tutorial video, export and import in under 3 minutes
├── sqljam-1.0.0-SNAPSHOT-sources.jar
└── sqljam-1.0.0-SNAPSHOT-javadoc.jar
```

Keep `sqljam.properties` and `sqljam.vmoptions` next to the runnable jar. Copy the folder anywhere and SqlJam still finds them, even when started by double click.

SqlJam starts with a splash screen while it loads the configuration, the data sources and the JDBC drivers:

<p align="center"><img src="docs/assets/splash.png" alt="SqlJam splash screen" width="520"></p>

1. Create a **data source** on the login page and click **Connect**. The first start brings an **Example Shop (H2)** data source with a few e-commerce tables to try everything right away. **Example** on the login page or **Help → Restore Example Database** brings it back at any time.
2. Right-click a database or schema → **Export…**
3. Pick tables, choose **SQL scripts**, **Parquet** or **CSV** (folder) or **Database** (another data source, or a new one from the **+** button next to it), press **Start**. An import into a database runs in one transaction or commits every N rows (N is typed next to it), check **Skip rows with errors** to go on after bad rows and **Confirm before commit** to review it first, and choose **Update or insert by key** or **Skip existing by key** for rows which exist already.
4. Open a table, narrow its rows on the **Data** tab with columns, WHERE, GROUP BY and ORDER BY, then **Export…** just those rows.
5. **SQL Query** on the toolbar or a right-click opens an editor for the data source. **Ctrl+Enter** (**⌘+Enter** on macOS) runs the statement at the caret, **Ctrl+Shift+Enter** runs them all, then **Export…** sends all rows of the query through the same exits.
6. Right-click a connected data source → **Disconnect** when you are done, its table tabs close with it. **Exit** sits at the right end of the toolbar. **Help → About SqlJam** shows the version, the home page, the repository and the author, and the GitHub icon at the bottom right opens the repository.

A short [tutorial video](docs/assets/sqljam-tutorial.mp4) walks through the example database: query rows, export into files, import the package into PostgreSQL, and copy the tables straight into SQL Server and Oracle.

| Login | Export wizard | Progress |
|---|---|---|
| ![login](docs/assets/login.png) | ![export](docs/assets/export-wizard.png) | ![progress](docs/assets/progress.png) |

| Data query | Package viewer | Parquet files |
|---|---|---|
| ![query](docs/assets/table-data.png) | ![viewer](docs/assets/package-viewer.png) | ![parquet](docs/assets/import-parquet.png) |

| SQL query | Package import | Light theme |
|---|---|---|
| ![sql](docs/assets/sql-editor.png) | ![import](docs/assets/import-package.png) | ![light](docs/assets/main-light.png) |

Expected export package:

```text
export/
├── schema.sql            # tables, sequences, indexes, comments
├── data.sql              # rows (data_2.sql, data_3.sql … when > 10 MB)
├── lob/
│   └── document/
│       ├── 000001_content.clob
│       └── 000001_attachment.blob
├── lob-manifest.json     # LOB files → table / primary key / column
├── constraints.sql       # foreign keys, applied after data
└── manifest.json         # source, target, options, files (sha256), row counts
```

A Parquet package has the same layout with `data/<table>.parquet` instead of SQL data and LOB files, a CSV package with `data/<table>.csv` and a header row. LOB values stay inside the Parquet or CSV columns, `manifest.json` records `"dataFormat": "PARQUET"` or `"CSV"` (with the type of every CSV column), so an import picks the right importer by itself and reads the values back exactly.

---

## Examples

### 1. Export tables as an export package

**Input**: MySQL database `shop`, target dialect PostgreSQL 16, one file per table.

```java
ScriptExporter exporter = new ScriptExporter(new File("export"), DataFileStrategy.FILE_PER_TABLE,
        10 * 1024 * 1024);
Exporter.ExportConfiguration config = exporter.getConfiguration();
config.setDbType(DbType.MYSQL);
config.setUrl(DbType.MYSQL.getUrl("localhost", 3306, "shop"));
config.setUsername("root");
config.setPassword("secret");
config.setIncludedCatalogNames(new String[]{"shop"});
config.setIdReused(true);                      // keep identity values
exporter.setTargetDbType(DbType.POSTGRESQL);   // generate PostgreSQL sql
exporter.setTargetSchemaName("public");
exporter.exportDdlAndData();

// The same tables as a Parquet package: data/orders.parquet ...
ParquetExporter parquet = new ParquetExporter(new File("export-parquet"));
// parquet.getConfiguration() is configured like the configuration above
parquet.setTargetDbType(DbType.POSTGRESQL);    // schema.sql and constraints.sql for PostgreSQL
parquet.setCompression("ZSTD");
parquet.exportDdlAndData();

// The same tables as a CSV package: data/orders.csv, data/orders_2.csv ...
CsvExporter csv = new CsvExporter(new File("export-csv"));
csv.setDelimiter(";");
csv.setMaxFileSize(10 * 1024 * 1024);
csv.exportDdlAndData();
```

**Output**: `export/schema.sql`, `export/data/orders.sql`, `export/data/orders_2.sql`, `export/lob/…`, `export/manifest.json`:

```sql
CREATE TABLE IF NOT EXISTS public.orders
(
    id                            bigserial                      NOT NULL,
    total                         numeric(12, 2)                 DEFAULT 0.00,
    paid                          boolean                        DEFAULT true,
    CONSTRAINT orders_pkey PRIMARY KEY (id)
);
```

### 2. Copy tables into another database

**Input**: Oracle 11g schema `HR` → SQL Server `demo.hr` (created if missing).

```java
ImportExporter importer = new ImportExporter();
Exporter.ExportConfiguration source = importer.getExportConfiguration();
source.setDbType(DbType.ORACLE);
source.setUrl(DbType.ORACLE.getUrl("ora-host", 1521, "ORCL"));
source.setUsername("hr");
source.setPassword("secret");
source.setIncludedSchemaNames(new String[]{"HR"});
source.setIdReused(true);

ImportExportHandler.ImportConfiguration target = importer.getImportConfiguration();
target.setDbType(DbType.SQLSERVER);
target.setUrl(DbType.SQLSERVER.getUrl("mssql-host", 1433, "demo"));
target.setUsername("sa");
target.setPassword("secret");
target.setTargetSchemaName("hr");            // created automatically
importer.setExportListener(new ExportListener() {
    @Override
    public void onProgress(long processed, long total) {
        System.out.printf("%d%%%n", processed * 100 / Math.max(total, 1));
    }
});
importer.exportDdlAndData();
```

**Output**: `0% … 100%`. Tables, keys, indexes, comments and sequences in `demo.hr`. Identity columns continue from the imported max value.

Rows of a query and copies in the same schema use the same API:

```java
// Only some columns and rows of EMPLOYEES, the target table is created with these columns
source.getTableQueries().put("EMPLOYEES", new TableQuery(List.of("EMPLOYEE_ID", "NAME", "SALARY"),
        "SALARY > 1000", null, "SALARY DESC"));
// Copies next to the originals: EMPLOYEES_COPY, keys and indexes get their own names
target.setTableNamePattern("{table}_copy");
```

### 3. Import an export package

```java
try (Connection connection = DriverManager.getConnection(
        "jdbc:postgresql://localhost:5432/demo", "postgres", "secret")) {
    ScriptImporter importer = new ScriptImporter(connection, DbType.POSTGRESQL);
    importer.importDirectory(new File("export"));
    System.out.println(importer.getExecutedCount() + " statements, " + importer.getLobCount() + " LOBs");
}
```

**Output**: `ScriptImporter` refuses packages whose status is not `COMPLETED`, whose target type differs, or whose files fail the SHA-256 check. `AbstractFileImporter.forDirectory(dir, connection, dbType)` returns the importer of the package (SQL, Parquet or CSV), and files of other tools are loaded by `ParquetImporter` or `CsvImporter`. A `ManualTransaction` keeps the import open until you commit or roll back:

```java
ParquetImporter importer = new ParquetImporter(connection, DbType.POSTGRESQL);
long rows = importer.importFiles(List.of(new File("sales-2024.parquet")), "sales", ParquetImporter.Mode.CREATE);

try (ManualTransaction transaction = new ManualTransaction(connection, DbType.POSTGRESQL.isTransactionalDdl())) {
    AbstractFileImporter packageImporter = AbstractFileImporter.forDirectory(dir, transaction.getConnection(),
            DbType.POSTGRESQL);
    packageImporter.importDirectory(dir);
    transaction.commit();                       // or transaction.rollback()
}
```

### 4. Type translation (cross-database)

| Source column | → PostgreSQL | → Oracle | → SQL Server | → MySQL |
|---|---|---|---|---|
| MySQL `bigint unsigned` | `numeric(20, 0)` | `NUMBER(20,0)` | `decimal(20,0)` | same |
| MySQL `tinyint(1)` | `boolean` | `NUMBER(1)` | `bit` | same |
| PostgreSQL `jsonb` | same | `CLOB` | `nvarchar(max)` | `json` |
| PostgreSQL `uuid` | same | `VARCHAR2(36)` | `uniqueidentifier` | `char(36)` |
| Oracle `NUMBER(9)` / `NUMBER(19)` | `int4` / `int8` | same | `int` / `bigint` | `int` / `bigint` |
| SQL Server `datetime2(7)` | `timestamp` (µs) | `TIMESTAMP(7)` | same | `datetime(6)` |
| SQL Server `tinyint` (0 to 255) | `int2` | `NUMBER(5)` | same | `smallint` |
| Db2 `DECFLOAT(34)` | `numeric` | `NUMBER` | `decimal(38,10)` | `decimal(65,30)` |
| Db2 `GRAPHIC(10)` | `char(10)` | `CHAR(10 CHAR)` | `nchar(10)` | `char(10)` |
| Db2 `CHAR(4) FOR BIT DATA` | `bytea` | `RAW(4)` | `binary(4)` | `binary(4)` |
| Db2 `XML` | `xml` | `CLOB` | `xml` | `longtext` |
| PostgreSQL enum / domain | same (type created in the target schema) | `VARCHAR2` / base type | `varchar` / base type | `varchar` / base type |
| MySQL / Oracle `VECTOR` | `text` | same | `nvarchar(max)` | same |
| ClickHouse `Nullable(String)` / `Array(T)` | `text` | `CLOB` | `nvarchar(max)` | `longtext` |
| DuckDB `HUGEINT` / `UNION` | `numeric(38, 0)` / `text` | `NUMBER(38,0)` / `CLOB` | `decimal(38,0)` / `nvarchar(max)` | `decimal(38,0)` / `longtext` |

### Best practices

| Do | Why |
|---|---|
| Keep **Keep identity values** on | Foreign keys stay valid. Identities/sequences are reset to `max + 1` |
| Pick the **target version** when exporting for an older server | e.g. `11.2` generates sequences + triggers instead of identity columns |
| Use **one file per table** for large exports | Re-run or inspect a single table easily |
| Import packages with SqlJam | Manifest validation, ordered files, LOB restore and FKs last |
| Use **Parquet** for analytics and lakes | Compact columnar files any engine reads, the schema still travels in `schema.sql` |
| Use **CSV** for spreadsheets and text tools | Opens anywhere, and SqlJam still restores the exact types from `manifest.json` |
| Keep **one transaction** for imports | An error rolls back everything, **Confirm before commit** shows the log and the row counts before the commit |
| Load again with **Update or insert by key** | Kept tables are updated by their primary keys, nothing is dropped |
| Shape a report in **SQL Query** first | The first rows show the columns and values, **Export…** then reads all rows by one cursor |
| Copy with a **target table name** | `{table}_copy` keeps the originals, a copy in the same schema without a new name is refused |

---

## Configuration

All settings live in `sqljam.properties`, placed next to the runnable jar in `bin/`.

| Layer (later wins) | Location |
|---|---|
| Built-in defaults | Coded in the application |
| Application file | `sqljam.properties` or `conf/sqljam.properties` next to the jar, or `-Dsqljam.app.config=/path` |
| User settings saved by the UI | `~/.sqljam/sqljam.properties`, or `-Dsqljam.config=/path` |

| Property | Default | Description |
|---|---|---|
| `sqljam.home` | `${user.home}/.sqljam` | Application data directory |
| `sqljam.connections.file` | `${sqljam.home}/connections.json` | Saved data sources |
| `sqljam.ui.theme` | `Primer Dark` | Primer/Nord/Cupertino Light & Dark, Dracula |
| `sqljam.ui.window.width` / `height` | `1280` / `800` | Main window size |
| `sqljam.banner.mode` | `console` | Startup banner with the version: `console`, `log` or `off` |
| `sqljam.banner.location` | | Custom banner file, `${sqljam.version}` and `${java.version}` are resolved |
| `sqljam.splash.enabled` | `true` | Splash screen with the logo while starting |
| `sqljam.splash.min-duration` | `1200` | Minimum time of the splash screen in ms, so that it does not flash |
| `sqljam.ui.data.page-size` | `200` | Rows per page in the data viewer |
| `sqljam.export.page-size` | `5000` | Rows read per page when exporting, tuned by benchmark |
| `sqljam.export.lob-page-size` | `100` | Rows per page for tables with LOB columns, keeps memory low |
| `sqljam.import.batch-size` | `1000` | INSERT statements per batch when importing a package (Oracle uses PL/SQL blocks) |
| `sqljam.export.max-file-size` | `10485760` | Max bytes of a SQL or CSV data file before it is split (0 = unlimited) |
| `sqljam.export.parquet-max-file-size` | `268435456` | Max bytes of a Parquet file before the table is split, Parquet reads best in large files |
| `sqljam.export.csv-delimiter` | `,` | Delimiter of CSV files: `,` `;` `\|` or `\t` |
| `sqljam.import.confirm-commit.reminder-minutes` | `10` | An import waiting for commit reminds you after these minutes, its transaction holds locks and log space (0 = off) |
| `sqljam.export.data-file-strategy` | `SINGLE_FILE` | `SINGLE_FILE` or `FILE_PER_TABLE` |
| `sqljam.export.lob-separated` | `true` | Write LOBs as files |
| `sqljam.pool.maximum-size` | `10` | HikariCP max pool size |
| `sqljam.pool.minimum-idle` | `1` | HikariCP min idle connections |
| `sqljam.pool.connection-timeout` | `30000` | ms |
| `sqljam.pool.idle-timeout` | `300000` | ms |
| `sqljam.pool.max-lifetime` | `1800000` | ms |

JVM options live in `sqljam.vmoptions` next to the runnable jar, one option per line. The launchers pass them to Java, and a jar started by double click restarts itself with them. `JAVA_OPTS` adds more and wins.

```text
# sqljam.vmoptions (defaults)
-Xms256m
-Xmx2g
-XX:+UseG1GC
-Dfile.encoding=UTF-8
```

Export options (UI / `ExportConfiguration`):

| Option | Default | Description |
|---|---|---|
| `tableRecreated` | `true` | Drop and recreate target tables |
| `idReused` | `false` (UI: `true`) | Copy identity values and reset sequences |
| `indexIncluded` / `foreignKeyIncluded` / `commentIncluded` / `sequenceIncluded` | `true` | Objects to copy |
| `failFast` | `true` | Stop on the first data error, `false` skips the rows with errors and reports each of them |
| `identifierCase` | `AUTO` | `AUTO`, `KEEP`, `LOWER`, `UPPER` |
| `dataFormat` / `compression` | `SQL` / `ZSTD` | `SQL`, `PARQUET` or `CSV` packages, Parquet compression `ZSTD`, `SNAPPY`, `GZIP`, `UNCOMPRESSED` |
| `csvDelimiter` | `,` | Delimiter of CSV packages |
| `writeMode` / `keyColumnNames` | `INSERT` | `UPSERT` updates the rows of the same key (the primary key or chosen columns) and inserts the others, `SKIP_EXISTING` keeps them and inserts the others |
| `CsvOptions` | comma, `"`, UTF-8 | Delimiter, quote, NULL text, header, encoding and date format of CSV files |
| `tableNamePattern` | | Target table name, `{table}` is the source name, e.g. `{table}_copy` |
| `tableQueries` | | Columns, WHERE and ORDER BY per table, e.g. rows narrowed in the data viewer |
| `queries` | | Free queries exported as tables, SQL by table name, e.g. the query of the SQL editor |

---

## Performance

![Benchmark](docs/assets/benchmark.png)

| Scenario (200,000 rows × 6 columns) | Time | Rows/s |
|---|---|---|
| H2 → Oracle 23ai | 2.1 s | 95.3k |
| H2 → export package (PostgreSQL, 32 MB, 4 files) | 2.9 s | 69.4k |
| H2 → PostgreSQL 16 | 3.2 s | 62.4k |
| H2 → MySQL 9.6 | 3.3 s | 60.7k |
| H2 → SQL Server 2022 | 5.0 s | 40.1k |
| Export package → PostgreSQL | 4.5 s | 44.5k |

Environment: Apple M2 Max, 32 GB, JDK 17. MySQL/PostgreSQL local, Oracle/SQL Server in Docker. Default settings.

| Design choice | Benefit |
|---|---|
| Rows paged by primary key | Stable, deterministic reads on every database |
| Tables copied one by one | Predictable load on production servers |
| Packages contain plain SQL | Readable, editable and portable. Direct import uses batched inserts for maximum speed |

---

## Documentation

| Topic | Link |
|---|---|
| Project site | [paganini2008.github.io/sqljam](https://paganini2008.github.io/sqljam/) |
| Blog | [docs/blogger/sqljam-en.md](docs/blogger/sqljam-en.md) |
| Developer guide | [docs/developer-guide.md](docs/developer-guide.md), the Java API for exports, imports, copies and queries |
| Change logs | [change_logs](change_logs) |
| Core API | `com.github.sqljam.impexp` (`Exporter`, `ScriptExporter`, `ImportExporter`, `ScriptImporter`, `ParquetExporter`, `ParquetImporter`, `CsvExporter`, `CsvImporter`, `ManualTransaction`) |

**FAQ**

| Question | Answer |
|---|---|
| Can I export only some rows or columns? | Yes. Narrow the rows on the **Data** tab (columns, WHERE, ORDER BY) and press **Export…**, or set `tableQueries`. A missing target table is created with the selected columns |
| Can I export the rows of my own SQL? | Yes. Write it in **SQL Query**, run it, then **Export…**. Columns of tables keep their types, the rows are read once by a cursor. In code, put the SQL into `queries` |
| Can I copy a table inside the same database? | Yes. Choose the same data source and schema as the target and a table name such as `{table}_copy` |
| How are LOBs stored in Parquet? | Inside the Parquet columns (strings and binaries), no extra LOB files |
| How exact is CSV? | Exact. NULL is an empty field, an empty string is `""`, binaries are escaped, and every column is read back with its type from `manifest.json` |
| How are rows matched when they are updated or skipped? | By the primary key, or by the columns chosen in **Key Columns**. Choose the columns of a unique index of the target table: MySQL and MariaDB match rows by their unique indexes, PostgreSQL, SQLite and DuckDB require such an index. A table without primary key and chosen columns is inserted |
| Why does Db2 refuse a wide table? | Rows wider than a 4K page need a table space of larger pages. Create one once, e.g. `CREATE BUFFERPOOL BP32K PAGESIZE 32K` and `CREATE TABLESPACE TS32K PAGESIZE 32K BUFFERPOOL BP32K`, Db2 then places wide tables there by itself |
| Do names have to match in case? | No. An existing target table and its columns are matched without case, e.g. `OrderId` of the source writes the column `orderid` of PostgreSQL or `ORDERID` of Oracle |
| Which rows were skipped? | Each skipped row is in the log of the import with its key and its error. **Errors only** shows them, **Save Log** writes the whole log to a file |
| What does a roll back undo? | Everything on PostgreSQL, SQL Server, Db2, SQLite and DuckDB, tables included. MySQL, MariaDB, Oracle and H2 commit DDL at once, there the created tables stay empty and keys and identity values are set after the commit |
| Can I export only the structure? | Yes. Rows are optional. Pick content **Structure** (`ExportMode.DDL`) for a package or a direct import. Tables, keys, indexes, comments, sequences and partitions are created without rows |
| Are partitioned tables kept? | Yes. The same database type creates the partitioning again with its partitions, bounds and rows: range, list, hash, key and subpartitions of MySQL and MariaDB (system time too), range, list, hash, default and nested partitions of PostgreSQL, range, list, hash, interval, automatic list, composite and reference partitions of Oracle, partition functions of SQL Server, range partitions of Db2 and partition keys of ClickHouse. Other databases get the tables with all rows. In the tree a partitioned table shows its partitioning next to its name and PostgreSQL partitions are listed under their table |
| Can I generate scripts for an older server? | Yes, choose the target version (e.g. Oracle `11.2`, SQL Server `2008`) and a compatible dialect is used |
| Can I keep the password out of disk? | Uncheck *Remember password* on the login page, it stays in memory for the session |
| How do I run the tests? | `mvn verify`, integration tests use local databases and are skipped when unreachable |

---

## Contributing & License

| Step | |
|---|---|
| Issues | [github.com/paganini2008/sqljam/issues](https://github.com/paganini2008/sqljam/issues) |
| Pull requests | Fork → branch → `mvn verify` → PR |
| Code style | Match existing code: `getXxxStatement` naming, Apache license header, class header javadoc, 4-space indent, no Spring |
| New database or version | Add `XxxDialect` / `XxxMetaDataOperations` in `impexp.db`, register in `DbType`, add a fixture in `src/test/resources/<db>/` |

License: [Apache License 2.0](LICENSE).
