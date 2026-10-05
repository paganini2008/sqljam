DROP TABLE IF EXISTS sjl_emp_tag;
DROP TABLE IF EXISTS sjl_emp;
DROP TABLE IF EXISTS sjl_dept;
DROP TABLE IF EXISTS sjl_sales;

CREATE TABLE sjl_dept (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       VARCHAR(100) NOT NULL CONSTRAINT uk_sjl_dept_name UNIQUE,
    budget     NUMERIC(12,2) DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sjl_emp (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    dept_id    INTEGER REFERENCES sjl_dept (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(200) NOT NULL,
    salary     NUMERIC(10,2),
    active     BOOLEAN DEFAULT 1,
    hired      DATE,
    updated_at DATETIME,
    photo      BLOB,
    profile    TEXT,
    note       VARCHAR(500) DEFAULT 'n/a'
);
CREATE UNIQUE INDEX uk_sjl_emp_email ON sjl_emp (email);
CREATE INDEX idx_sjl_emp_dept_name ON sjl_emp (dept_id, name);

CREATE TABLE sjl_emp_tag (
    emp_id   INTEGER     NOT NULL REFERENCES sjl_emp (id),
    tag_name VARCHAR(50) NOT NULL,
    PRIMARY KEY (emp_id, tag_name)
);

CREATE TABLE sjl_sales (
    id        INTEGER NOT NULL,
    sale_year INTEGER NOT NULL,
    amount    NUMERIC(10,2),
    PRIMARY KEY (id, sale_year)
);

DROP TABLE IF EXISTS sjl_types;
CREATE TABLE sjl_types (
    id          INTEGER PRIMARY KEY,
    c_integer   INTEGER,
    c_int       INT,
    c_tinyint   TINYINT,
    c_smallint  SMALLINT,
    c_mediumint MEDIUMINT,
    c_bigint    BIGINT,
    c_int8      INT8,
    c_real      REAL,
    c_double    DOUBLE,
    c_float     FLOAT,
    c_numeric   NUMERIC,
    c_decimal   DECIMAL(10,5),
    c_boolean   BOOLEAN,
    c_date      DATE,
    c_datetime  DATETIME,
    c_timestamp TIMESTAMP,
    c_time      TIME,
    c_text      TEXT,
    c_character CHARACTER(20),
    c_varchar   VARCHAR(255),
    c_nchar     NCHAR(55),
    c_nvarchar  NVARCHAR(100),
    c_clob      CLOB,
    c_blob      BLOB,
    c_json      JSON,
    c_gen       INTEGER GENERATED ALWAYS AS (c_int * 2) VIRTUAL
);
INSERT INTO sjl_types (id, c_integer, c_int, c_tinyint, c_smallint, c_mediumint, c_bigint, c_int8, c_real, c_double,
    c_float, c_numeric, c_decimal, c_boolean, c_date, c_datetime, c_timestamp, c_time, c_text, c_character, c_varchar,
    c_nchar, c_nvarchar, c_clob, c_blob, c_json)
VALUES (1, -9223372036854775808, -2147483648, -128, -32768, -8388608, 9223372036854775807, 1, 3.5, 2.718281828459045,
    1.5, 12345.6789, 12345.12345, 1, '2024-02-29', '2024-02-29 23:59:59', '2024-02-29 23:59:59.123', '23:59:59',
    'text ''quoted'' \ 中文', 'character', 'varchar', 'nchar', 'nvarchar', 'clob', X'DEADBEEF', '{"k":"v"}');
INSERT INTO sjl_types (id, c_integer, c_int, c_real, c_boolean, c_date, c_datetime, c_text, c_blob)
VALUES (2, 0, 2147483647, -1E100, 0, '1970-01-01', '9999-12-31 23:59:59', '', X'');
INSERT INTO sjl_types (id) VALUES (3);
