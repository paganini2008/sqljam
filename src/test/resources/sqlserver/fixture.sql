IF OBJECT_ID('dbo.sjs_emp_tag', 'U') IS NOT NULL DROP TABLE dbo.sjs_emp_tag;
IF OBJECT_ID('dbo.sjs_emp', 'U') IS NOT NULL DROP TABLE dbo.sjs_emp;
IF OBJECT_ID('dbo.sjs_dept', 'U') IS NOT NULL DROP TABLE dbo.sjs_dept;
IF OBJECT_ID('dbo.sjs_sales', 'U') IS NOT NULL DROP TABLE dbo.sjs_sales;

CREATE TABLE dbo.sjs_dept (
    id         INT IDENTITY(1,1) PRIMARY KEY,
    name       NVARCHAR(100) NOT NULL CONSTRAINT uk_sjs_dept_name UNIQUE,
    budget     DECIMAL(12,2) DEFAULT 0,
    created_at DATETIME2 DEFAULT GETDATE()
);
EXEC sp_addextendedproperty 'MS_Description', 'Department', 'SCHEMA', 'dbo', 'TABLE', 'sjs_dept';

CREATE TABLE dbo.sjs_emp (
    id         BIGINT IDENTITY(1,1) PRIMARY KEY,
    dept_id    INT REFERENCES dbo.sjs_dept (id) ON DELETE CASCADE,
    name       NVARCHAR(100) NOT NULL,
    email      NVARCHAR(200) NOT NULL,
    salary     DECIMAL(10,2),
    active     BIT DEFAULT 1,
    hired      DATE,
    updated_at DATETIME2(3),
    photo      VARBINARY(MAX),
    profile    NVARCHAR(MAX),
    note       NVARCHAR(500) DEFAULT 'n/a',
    uid        UNIQUEIDENTIFIER
);
EXEC sp_addextendedproperty 'MS_Description', 'Employee name', 'SCHEMA', 'dbo', 'TABLE', 'sjs_emp', 'COLUMN', 'name';
CREATE UNIQUE INDEX uk_sjs_emp_email ON dbo.sjs_emp (email);
CREATE INDEX idx_sjs_emp_dept_name ON dbo.sjs_emp (dept_id, name);

CREATE TABLE dbo.sjs_emp_tag (
    emp_id   BIGINT       NOT NULL REFERENCES dbo.sjs_emp (id),
    tag_name NVARCHAR(50) NOT NULL,
    PRIMARY KEY (emp_id, tag_name)
);

IF NOT EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = 'pf_sjs_year') EXEC('CREATE PARTITION FUNCTION pf_sjs_year (INT) AS RANGE RIGHT FOR VALUES (2024, 2025)');
IF NOT EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = 'ps_sjs_year') EXEC('CREATE PARTITION SCHEME ps_sjs_year AS PARTITION pf_sjs_year ALL TO ([PRIMARY])');
CREATE TABLE dbo.sjs_sales (
    id        INT NOT NULL,
    sale_year INT NOT NULL,
    amount    DECIMAL(10,2),
    CONSTRAINT pk_sjs_sales PRIMARY KEY (id, sale_year)
) ON ps_sjs_year (sale_year);

IF OBJECT_ID('dbo.sjs_types', 'U') IS NOT NULL DROP TABLE dbo.sjs_types;
IF OBJECT_ID('dbo.sjs_order_seq', 'SO') IS NULL EXEC('CREATE SEQUENCE dbo.sjs_order_seq AS BIGINT START WITH 1000 INCREMENT BY 10') ELSE ALTER SEQUENCE dbo.sjs_order_seq RESTART WITH 1000;
CREATE TABLE dbo.sjs_types (
    id                 INT PRIMARY KEY,
    c_order_no         BIGINT DEFAULT (NEXT VALUE FOR dbo.sjs_order_seq),
    c_bit              BIT,
    c_tinyint          TINYINT,
    c_smallint         SMALLINT,
    c_int              INT,
    c_bigint           BIGINT,
    c_decimal          DECIMAL(20,6),
    c_numeric          NUMERIC(10,0),
    c_money            MONEY,
    c_smallmoney       SMALLMONEY,
    c_float            FLOAT,
    c_real             REAL,
    c_date             DATE,
    c_time             TIME(7),
    c_datetime         DATETIME,
    c_datetime2        DATETIME2(7),
    c_datetimeoffset   DATETIMEOFFSET(7),
    c_smalldatetime    SMALLDATETIME,
    c_char             CHAR(10),
    c_varchar          VARCHAR(255),
    c_varchar_max      VARCHAR(MAX),
    c_text             TEXT,
    c_nchar            NCHAR(10),
    c_nvarchar         NVARCHAR(255),
    c_nvarchar_max     NVARCHAR(MAX),
    c_ntext            NTEXT,
    c_binary           BINARY(4),
    c_varbinary        VARBINARY(64),
    c_varbinary_max    VARBINARY(MAX),
    c_image            IMAGE,
    c_uniqueidentifier UNIQUEIDENTIFIER,
    c_xml              XML,
    c_sql_variant      SQL_VARIANT,
    c_hierarchyid      HIERARCHYID,
    c_geography        GEOGRAPHY,
    c_geometry         GEOMETRY,
    c_datetime2_0      DATETIME2(0),
    c_time0            TIME(0),
    c_dto0             DATETIMEOFFSET(0),
    c_decimal38        DECIMAL(38,0),
    c_rowversion       ROWVERSION,
    c_computed         AS (CAST(c_int AS BIGINT) * 2),
    c_persisted        AS (CONCAT('#', id)) PERSISTED
);
INSERT INTO dbo.sjs_types (id, c_bit, c_tinyint, c_smallint, c_int, c_bigint, c_decimal, c_numeric, c_money,
    c_smallmoney, c_float, c_real, c_date, c_time, c_datetime, c_datetime2, c_datetimeoffset, c_smalldatetime, c_char,
    c_varchar, c_varchar_max, c_text, c_nchar, c_nvarchar, c_nvarchar_max, c_ntext, c_binary, c_varbinary,
    c_varbinary_max, c_image, c_uniqueidentifier, c_xml, c_sql_variant, c_hierarchyid, c_geography, c_geometry)
VALUES (1, 1, 255, -32768, -2147483648, -9223372036854775808, 12345678901234.123456, 1234567890, 1234.5678, 12.34,
    2.718281828459045, 3.5, '2024-02-29', '23:59:59.1234567', '2024-02-29 23:59:59.997', '2024-02-29 23:59:59.1234567',
    '2024-02-29 12:00:00.1234567 +08:00', '2024-02-29 12:00:00', 'char', 'varchar ''quoted'' \', 'varchar max', 'text',
    N'中文', N'nvarchar 中文', N'nvarchar max 中文', N'ntext 中文', 0x01020304, 0xDEADBEEF, 0x0102, 0x0304,
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '<a>1</a>', CAST(42 AS INT), hierarchyid::Parse('/1/2/'),
    geography::Point(47.65, -122.34, 4326), geometry::Point(1, 2, 0));
INSERT INTO dbo.sjs_types (id, c_bit, c_tinyint, c_smallint, c_int, c_bigint, c_decimal, c_numeric, c_money,
    c_smallmoney, c_float, c_real, c_date, c_time, c_datetime, c_datetime2, c_char, c_varchar, c_nvarchar, c_binary,
    c_varbinary)
VALUES (2, 0, 0, 32767, 2147483647, 9223372036854775807, -0.000001, 0, -0.0001, 0, -1E100, -3.5, '0001-01-01',
    '00:00:00', '1753-01-01 00:00:00', '9999-12-31 23:59:59.9999999', '', '', N'', 0x00000000, 0x);
INSERT INTO dbo.sjs_types (id) VALUES (3);
UPDATE dbo.sjs_types SET c_datetime2_0 = '2024-02-29 23:59:59', c_time0 = '23:59:59',
    c_dto0 = '2024-02-29 12:00:00 +08:00', c_decimal38 = 99999999999999999999999999999999999999 WHERE id = 1;
UPDATE dbo.sjs_types SET c_datetime2_0 = '0001-01-01 00:00:00', c_time0 = '00:00:00',
    c_dto0 = '1970-01-02 00:00:00 +00:00', c_decimal38 = -99999999999999999999999999999999999999 WHERE id = 2;
