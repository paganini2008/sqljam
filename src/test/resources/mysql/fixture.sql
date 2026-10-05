DROP TABLE IF EXISTS sjm_emp_tag;
DROP TABLE IF EXISTS sjm_emp;
DROP TABLE IF EXISTS sjm_dept;
DROP TABLE IF EXISTS sjm_sales;

CREATE TABLE sjm_dept (
    id         INT           NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100)  NOT NULL,
    budget     DECIMAL(12,2) DEFAULT 0.00,
    created_at DATETIME      DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sjm_dept_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Department';

CREATE TABLE sjm_emp (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    dept_id    INT,
    name       VARCHAR(100)  NOT NULL COMMENT 'Employee name',
    email      VARCHAR(200)  NOT NULL,
    salary     DECIMAL(10,2),
    active     TINYINT(1)    DEFAULT 1,
    hired      DATE,
    updated_at DATETIME(3),
    photo      BLOB,
    profile    TEXT,
    note       VARCHAR(500)  DEFAULT 'n/a',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sjm_emp_email (email),
    KEY idx_sjm_emp_dept_name (dept_id, name),
    CONSTRAINT fk_sjm_emp_dept FOREIGN KEY (dept_id) REFERENCES sjm_dept (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sjm_emp_tag (
    emp_id   BIGINT      NOT NULL,
    tag_name VARCHAR(50) NOT NULL,
    PRIMARY KEY (emp_id, tag_name),
    CONSTRAINT fk_sjm_tag_emp FOREIGN KEY (emp_id) REFERENCES sjm_emp (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sjm_sales (
    id        INT           NOT NULL,
    sale_year INT           NOT NULL,
    amount    DECIMAL(10,2),
    PRIMARY KEY (id, sale_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE (sale_year) (
    PARTITION p2023 VALUES LESS THAN (2024),
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION pmax VALUES LESS THAN MAXVALUE
);

DROP TABLE IF EXISTS sjm_types;
CREATE TABLE sjm_types (
    id            INT NOT NULL PRIMARY KEY,
    c_tinyint     TINYINT,
    c_tinyint_u   TINYINT UNSIGNED,
    c_smallint    SMALLINT,
    c_smallint_u  SMALLINT UNSIGNED,
    c_mediumint   MEDIUMINT,
    c_mediumint_u MEDIUMINT UNSIGNED,
    c_int         INT,
    c_int_u       INT UNSIGNED,
    c_bigint      BIGINT,
    c_bigint_u    BIGINT UNSIGNED,
    c_decimal     DECIMAL(20,6),
    c_numeric     NUMERIC(10,0),
    c_float       FLOAT,
    c_double      DOUBLE,
    c_real        REAL,
    c_bit1        BIT(1),
    c_bit8        BIT(8),
    c_bool        BOOLEAN,
    c_date        DATE,
    c_datetime    DATETIME,
    c_datetime6   DATETIME(6),
    c_timestamp   TIMESTAMP NULL,
    c_time        TIME,
    c_time3       TIME(3),
    c_year        YEAR,
    c_char        CHAR(10),
    c_varchar     VARCHAR(255),
    c_tinytext    TINYTEXT,
    c_text        TEXT,
    c_mediumtext  MEDIUMTEXT,
    c_longtext    LONGTEXT,
    c_binary      BINARY(4),
    c_varbinary   VARBINARY(64),
    c_tinyblob    TINYBLOB,
    c_blob        BLOB,
    c_mediumblob  MEDIUMBLOB,
    c_longblob    LONGBLOB,
    c_enum        ENUM('red','green','blue'),
    c_set         SET('a','b','c'),
    c_json        JSON,
    c_geometry    GEOMETRY,
    c_point       POINT,
    c_gen_stored  BIGINT GENERATED ALWAYS AS (c_int * 2) STORED,
    c_gen_virtual VARCHAR(20) GENERATED ALWAYS AS (CONCAT('#', id)) VIRTUAL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sjm_types (id, c_tinyint, c_tinyint_u, c_smallint, c_smallint_u, c_mediumint, c_mediumint_u, c_int, c_int_u,
    c_bigint, c_bigint_u, c_decimal, c_numeric, c_float, c_double, c_real, c_bit1, c_bit8, c_bool, c_date, c_datetime,
    c_datetime6, c_timestamp, c_time, c_time3, c_year, c_char, c_varchar, c_tinytext, c_text, c_mediumtext, c_longtext,
    c_binary, c_varbinary, c_tinyblob, c_blob, c_mediumblob, c_longblob, c_enum, c_set, c_json, c_geometry, c_point)
VALUES (1, -128, 255, -32768, 65535, -8388608, 16777215, -2147483648, 4294967295, -9223372036854775808,
    18446744073709551615, 12345678901234.123456, 1234567890, 3.5, 2.718281828459045, 1.25, b'1', b'10101010', TRUE,
    '2024-02-29', '2024-02-29 23:59:59', '2024-02-29 23:59:59.123456', '2024-02-29 12:00:00', '23:59:59',
    '12:34:56.789', 2024, 'char', 'varchar ''quoted'' \\ 中文', 'tiny', 'text', 'medium', 'long', X'01020304',
    X'DEADBEEF', X'00', X'0102', X'0304', X'0506', 'red', 'a,c', '{"k": "v", "n": [1, 2]}',
    ST_GeomFromText('POINT(1 2)'), ST_GeomFromText('POINT(3 4)'));
INSERT INTO sjm_types (id, c_tinyint, c_tinyint_u, c_smallint, c_smallint_u, c_mediumint, c_mediumint_u, c_int, c_int_u,
    c_bigint, c_bigint_u, c_decimal, c_numeric, c_float, c_double, c_real, c_bit1, c_bit8, c_bool, c_date, c_datetime,
    c_datetime6, c_timestamp, c_time, c_time3, c_year, c_char, c_varchar, c_tinytext, c_text, c_mediumtext, c_longtext,
    c_binary, c_varbinary, c_tinyblob, c_blob, c_mediumblob, c_longblob, c_enum, c_set, c_json, c_geometry, c_point)
VALUES (2, 127, 0, 32767, 0, 8388607, 0, 2147483647, 0, 9223372036854775807, 0, -0.000001, 0, -3.5, -1E100, 0,
    b'0', b'00000000', FALSE, '1970-01-01', '1000-01-01 00:00:00', '9999-12-31 23:59:59.999999', '1970-01-02 00:00:00',
    '00:00:00', '00:00:00.001', 1901, '', '', '', '', '', '', X'00000000', X'', X'', X'', X'', X'', 'blue', '',
    '[]', ST_GeomFromText('LINESTRING(0 0, 1 1)'), ST_GeomFromText('POINT(0 0)'));
INSERT INTO sjm_types (id) VALUES (3);
