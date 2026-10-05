# SqlJam：跨数据库，一键搬表

> 结构、索引、约束、序列、分区、数据，一起搬走。

## 1. Overview

**SqlJam**：*Move tables across databases. Schema, data and all.*

一个 Java 17 + JavaFX 的桌面导入导出工具，支持 **MySQL、PostgreSQL、Oracle、SQL Server、H2、SQLite** 等数据库任意互导：
可以**直接导入目标库**，也可以导出为带描述文件的 **SQL 导出包**，之后再成对导入。

![SqlJam 主界面](../assets/main-dark.png)

## 2. 解决什么问题？

跨库迁移最麻烦的不是 `INSERT`，而是那些"细节"：类型名不同、自增实现不同、默认值语法不同、`bigint unsigned` 溢出、时间被时区平移、BLOB 写进脚本后几百 MB、老版本数据库不认新语法……手写脚本既慢又容易漏。

SqlJam 用**一棵元数据树 + 按目标库和版本选择的方言**，把这些差异集中处理掉。导出包自带 `manifest.json`，导入时能校验"导给谁、完整不完整、文件有没有被改过"。

| 痛点 | SqlJam 的做法 |
|---|---|
| 方言差异 | 按目标方言统一翻译类型 / 标识符 / 默认值 / 自增 / 字面量 |
| 老版本 | `Oracle11gDialect`、`PostgreSQL9Dialect`、`MySQL56Dialect`、`SQLServer2008Dialect` 等版本子类 |
| 丢键丢索引 | 主键、唯一/普通索引、外键、注释、序列、分区一起迁移 |
| 脚本太大 | 数据文件按 10 MB 切分：`orders.sql`、`orders_2.sql`… |
| LOB 难处理 | LOB 单独存文件，按主键回填 |
| 脚本没有上下文 | `manifest.json`：源、目标、选项、文件 SHA-256、行数 |

## 3. Quick Start

```bash
git clone git@github.com:paganini2008/sqljam.git && cd sqljam
mvn -DskipTests package                     # 输出到 bin/
java -jar bin/sqljam-1.0.0-SNAPSHOT.jar     # 也可以直接双击 jar
```

```text
bin/
├── sqljam-1.0.0-SNAPSHOT.jar           # 可运行 jar，内置全部 JDBC 驱动
├── sqljam.properties                   # 外置配置，改完重启即可
├── sqljam-1.0.0-SNAPSHOT-sources.jar
└── sqljam-1.0.0-SNAPSHOT-javadoc.jar
```

`sqljam.properties` 和可运行 jar 放在一起。两个文件一起拷到任何目录都能用，双击启动也能找到配置。

| 1. 登录数据源 | 2. 导出向导 | 3. 进度 |
|---|---|---|
| ![login](../assets/login.png) | ![export](../assets/export-wizard.png) | ![progress](../assets/progress.png) |

## 4. Requirements

| 项 | 版本 |
|---|---|
| JDK | 17+ |
| Maven | 3.8+（仅构建） |
| MySQL | 5.5 ~ 9.x |
| PostgreSQL | 9.x ~ 16 |
| Oracle | 11g ~ 23ai |
| SQL Server | 2008 ~ 2022 |
| H2 / SQLite | 2.x / 3.x |

JDBC 驱动全部打进 fat jar，无需额外下载。

## 5. How It Works

![工作原理](../assets/architecture.png)

```mermaid
flowchart LR
    S[(源库)] --> T[元数据树]
    T --> D[目标方言<br/>类型 + 版本子类]
    D --> I[直接导入] --> TD[(目标库)]
    D --> E[导出包<br/>schema.sql · data*.sql · lob/ · manifest.json]
    E --> SI[ScriptImporter] --> TD
```

- **读一次元数据**：`XxxMetaDataOperations` 按库补全注释、自增、生成列、分区、序列。
- **按目标生成 SQL**：`DbType.createDialect(major, minor)` 选出版本子类。
- **数据流**：先统计行数（百分比进度）→ 按主键分页读取 → 值归一化 → 批量写入或生成字面量。

## 6. Code Examples

### Example 1：导出为 PostgreSQL 脚本包

**Input**：MySQL 库 `shop`

```java
ScriptExporter exporter = new ScriptExporter(new File("export"), DataFileStrategy.FILE_PER_TABLE, 10 * 1024 * 1024);
Exporter.ExportConfiguration config = exporter.getConfiguration();
config.setDbType(DbType.MYSQL);
config.setUrl(DbType.MYSQL.getUrl("localhost", 3306, "shop"));
config.setUsername("root");
config.setPassword("secret");
config.setIdReused(true);
exporter.setTargetDbType(DbType.POSTGRESQL);
exporter.exportDdlAndData();
```

**Output**：

```text
export/
├── schema.sql   ├── data/orders.sql   ├── data/orders_2.sql
├── lob/         ├── lob-manifest.json ├── constraints.sql
└── manifest.json
```

### Example 2：Oracle 直接导入 SQL Server（自动建 schema）

```java
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

**Output**：表、主键、索引、外键、注释、序列都在 `demo.hr`，自增从导入的最大值继续。

| 源列 | → PostgreSQL | → Oracle | → SQL Server |
|---|---|---|---|
| MySQL `bigint unsigned` | `numeric(20, 0)` | `NUMBER(20,0)` | `decimal(20,0)` |
| PostgreSQL `jsonb` | same | `CLOB` | `nvarchar(max)` |
| SQL Server `datetime2(7)` | `timestamp` | `TIMESTAMP(7)` | same |

## 7. Configuration

全部配置在可运行 jar 旁边的 `bin/sqljam.properties` 里。界面里修改的设置保存在 `~/.sqljam/sqljam.properties`，优先于该文件。

| Property | Default | Description |
|---|---|---|
| `sqljam.export.max-file-size` | `10485760` | 单个数据文件上限（字节） |
| `sqljam.export.data-file-strategy` | `SINGLE_FILE` | 单文件 / 每表一个文件 |
| `sqljam.export.lob-separated` | `true` | LOB 单独存文件 |
| `sqljam.export.page-size` | `5000` | 每页读取行数（实测最优） |
| `sqljam.export.lob-page-size` | `100` | 含 LOB 的表每页行数，内存更省 |
| `sqljam.banner.mode` | `console` | 启动 banner（带版本号）：console / log / off |
| `sqljam.import.batch-size` | `1000` | 导入导出包时每批 INSERT 条数 |
| `sqljam.ui.theme` | `Primer Dark` | 主题，可切换 7 种 |
| `sqljam.pool.maximum-size` | `10` | 连接池大小 |

## 8. Performance

![Benchmark](../assets/benchmark.png)

| 场景（20 万行 × 6 列） | 耗时 | 行/秒 |
|---|---|---|
| H2 → Oracle 23ai | 2.1 s | 95.3k |
| H2 → 导出包（32 MB，切成 4 个文件） | 2.9 s | 69.4k |
| H2 → PostgreSQL 16 | 3.2 s | 62.4k |
| H2 → MySQL 9.6 | 3.3 s | 60.7k |
| H2 → SQL Server 2022 | 5.0 s | 40.1k |
| 导出包 → PostgreSQL | 4.5 s | 44.5k |

环境：Apple M2 Max / 32 GB / JDK 17。MySQL、PostgreSQL 本机，Oracle、SQL Server 在 Docker。默认配置。

**质量**：292 个测试（含完整的跨库互导矩阵、导出包回放、旧版本语法在真实库上执行、界面功能与边界测试），行覆盖率 91%。

## 9. 设计取舍（Design Trade-offs）

- **聚焦表级对象**：表、主键、索引、约束、序列、分区、注释，数据迁移真正依赖的对象。
- **按主键分页读取**：在每种数据库上都稳定、可重复。
- **逐表复制**：对生产库的压力可预期。
- **导出包是纯 SQL**：可读、可改、可移植。直接导入走批量写入，追求速度。
- **按版本生成 SQL**：老版本数据库拿到的是它能理解的语法，而不是最低公分母。

## 10. Summary

1. 任意数据库之间互导，所有跨库组合均通过自动化测试。
2. 方言按**类型 + 版本**选择，老版本差异用子类表达。
3. 表、索引、约束、序列、分区、注释一起迁移。
4. 导出包 = SQL + LOB 文件 + `manifest.json`，导出导入成对。
5. 数据文件默认 10 MB 切分，LOB 单独存放。
6. 时间、无符号、bit、money、UUID、JSON 等值跨库不失真。
7. 连接池 + 批量写入，直接导入 4 万 ~ 9.5 万行/秒。
8. 暗色主流界面，7 种主题，百分比进度与取消。
