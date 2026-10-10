DROP TABLE IF EXISTS sjd_emp_tag;
DROP TABLE IF EXISTS sjd_emp;
DROP TABLE IF EXISTS sjd_dept;
DROP TABLE IF EXISTS sjd_sales;
DROP TABLE IF EXISTS sjd_types;
DROP SEQUENCE IF EXISTS sjd_dept_id_seq;
DROP SEQUENCE IF EXISTS sjd_emp_id_seq;
DROP SEQUENCE IF EXISTS sjd_order_seq;

-- Identity columns of DuckDB are sequences named table_column_seq with nextval defaults
CREATE SEQUENCE sjd_dept_id_seq START 1;
CREATE TABLE sjd_dept (
    id         INTEGER DEFAULT nextval('sjd_dept_id_seq') NOT NULL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    budget     DECIMAL(12,2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE sjd_dept IS 'Department';

CREATE SEQUENCE sjd_emp_id_seq START 1;
CREATE TABLE sjd_emp (
    id         BIGINT DEFAULT nextval('sjd_emp_id_seq') NOT NULL PRIMARY KEY,
    dept_id    INTEGER REFERENCES sjd_dept (id),
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(200) NOT NULL,
    salary     DECIMAL(10,2),
    active     BOOLEAN DEFAULT true,
    hired      DATE,
    updated_at TIMESTAMP,
    photo      BLOB,
    profile    TEXT,
    note       VARCHAR(500) DEFAULT 'n/a'
);
COMMENT ON COLUMN sjd_emp.name IS 'Employee name';
CREATE UNIQUE INDEX uk_sjd_emp_email ON sjd_emp (email);
CREATE INDEX idx_sjd_emp_dept_name ON sjd_emp (dept_id, name);

CREATE TABLE sjd_emp_tag (
    emp_id   BIGINT      NOT NULL REFERENCES sjd_emp (id),
    tag_name VARCHAR(50) NOT NULL,
    PRIMARY KEY (emp_id, tag_name)
);

CREATE TABLE sjd_sales (
    id        INTEGER NOT NULL,
    sale_year INTEGER NOT NULL,
    amount    DECIMAL(10,2),
    PRIMARY KEY (id, sale_year)
);

CREATE SEQUENCE sjd_order_seq START 1000 INCREMENT BY 10;
CREATE TABLE sjd_types (
    id               INTEGER PRIMARY KEY,
    c_order_no       BIGINT DEFAULT nextval('sjd_order_seq'),
    c_boolean        BOOLEAN,
    c_tinyint        TINYINT,
    c_smallint       SMALLINT,
    c_integer        INTEGER,
    c_bigint         BIGINT,
    c_hugeint        HUGEINT,
    c_utinyint       UTINYINT,
    c_usmallint      USMALLINT,
    c_uinteger       UINTEGER,
    c_ubigint        UBIGINT,
    c_float          FLOAT,
    c_double         DOUBLE,
    c_decimal        DECIMAL(18,4),
    c_decimal38      DECIMAL(38,10),
    c_varchar        VARCHAR,
    c_varchar_n      VARCHAR(20),
    c_text           TEXT,
    c_blob           BLOB,
    c_date           DATE,
    c_time           TIME,
    c_timetz         TIMETZ,
    c_timestamp      TIMESTAMP,
    c_timestamp_s    TIMESTAMP_S,
    c_timestamp_ms   TIMESTAMP_MS,
    c_timestamp_ns   TIMESTAMP_NS,
    c_timestamptz    TIMESTAMPTZ,
    c_interval       INTERVAL,
    c_uuid           UUID,
    c_json           JSON,
    c_enum           ENUM('red', 'green', 'blue'),
    c_bit            BIT,
    c_int_list       INTEGER[],
    c_varchar_list   VARCHAR[],
    c_struct         STRUCT(a INTEGER, b VARCHAR),
    c_map            MAP(VARCHAR, INTEGER),
    c_uhugeint       UHUGEINT,
    c_int_array3     INTEGER[3],
    c_union          UNION(num INTEGER, str VARCHAR),
    c_gen            INTEGER GENERATED ALWAYS AS (CAST(c_smallint AS INTEGER) * 2) VIRTUAL
);
INSERT INTO sjd_types (id, c_boolean, c_tinyint, c_smallint, c_integer, c_bigint, c_hugeint, c_utinyint,
    c_usmallint, c_uinteger, c_ubigint, c_float, c_double, c_decimal, c_decimal38, c_varchar, c_varchar_n, c_text,
    c_blob, c_date, c_time, c_timetz, c_timestamp, c_timestamp_s, c_timestamp_ms, c_timestamp_ns, c_timestamptz,
    c_interval, c_uuid, c_json, c_enum, c_bit, c_int_list, c_varchar_list, c_struct, c_map)
VALUES (1, true, -128, -32768, -2147483648, -9223372036854775808, 99999999999999999999999999999999999999, 255,
    65535, 4294967295, 18446744073709551615, 3.5, 2.718281828459045, 12345678901234.1234,
    1234567890123456789012345678.0123456789, 'varchar ''quoted'' \ 中文', 'short', 'text',
    '\xDE\xAD\xBE\xEF'::BLOB, DATE '2024-02-29', TIME '23:59:59.123456', TIMETZ '12:00:00+08:00',
    TIMESTAMP '2024-02-29 23:59:59.123456', TIMESTAMP_S '2024-02-29 23:59:59', TIMESTAMP_MS '2024-02-29 23:59:59.123',
    TIMESTAMP_NS '2024-02-29 23:59:59.123456789', TIMESTAMPTZ '2024-02-29 12:00:00+08:00', INTERVAL '1 year 2 months 3 days',
    'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '{"k": "v", "n": [1, 2]}', 'green', '101101', [1, 2, 3], ['a', 'b'],
    {'a': 1, 'b': 'x'}, MAP {'k1': 1, 'k2': 2});
INSERT INTO sjd_types (id, c_boolean, c_tinyint, c_smallint, c_integer, c_bigint, c_hugeint, c_utinyint,
    c_usmallint, c_uinteger, c_ubigint, c_float, c_double, c_decimal, c_decimal38, c_varchar, c_varchar_n, c_text,
    c_blob, c_date, c_time, c_timestamp, c_timestamptz)
VALUES (2, false, 127, 32767, 2147483647, 9223372036854775807, -99999999999999999999999999999999999999, 0, 0, 0, 0,
    -3.5, -1E100, -0.0001, 0, '', '', '', ''::BLOB, DATE '1970-01-01', TIME '00:00:00', TIMESTAMP '9999-12-31 23:59:59.999999',
    TIMESTAMPTZ '1970-01-02 00:00:00+00:00');
INSERT INTO sjd_types (id) VALUES (3);
UPDATE sjd_types SET c_uhugeint = 99999999999999999999999999999999999999, c_int_array3 = [1, 2, 3],
    c_union = union_value(str := 'text') WHERE id = 1;
UPDATE sjd_types SET c_uhugeint = 0, c_int_array3 = [0, 0, 0], c_union = union_value(num := 7) WHERE id = 2;
