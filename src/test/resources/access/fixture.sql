-- Tables are created in a new file (ItDatabase deletes the files of an earlier run)

CREATE TABLE sja_dept (
    id         COUNTER       NOT NULL PRIMARY KEY,
    name       TEXT(100)     NOT NULL,
    budget     DECIMAL(12,2) DEFAULT 0,
    created_at DATETIME      DEFAULT NOW()
);
CREATE UNIQUE INDEX uk_sja_dept_name ON sja_dept (name);

CREATE TABLE sja_emp (
    id         COUNTER       NOT NULL PRIMARY KEY,
    dept_id    LONG,
    name       TEXT(100)     NOT NULL,
    email      TEXT(200)     NOT NULL,
    salary     DECIMAL(10,2),
    active     YESNO,
    hired      DATETIME,
    updated_at DATETIME,
    photo      OLE,
    profile    MEMO,
    note       TEXT(255)     DEFAULT 'n/a'
);
CREATE UNIQUE INDEX uk_sja_emp_email ON sja_emp (email);
CREATE INDEX idx_sja_emp_dept_name ON sja_emp (dept_id, name);
ALTER TABLE sja_emp ADD CONSTRAINT fk_sja_emp_dept FOREIGN KEY (dept_id) REFERENCES sja_dept (id) ON DELETE CASCADE;

CREATE TABLE sja_emp_tag (
    emp_id   LONG     NOT NULL,
    tag_name TEXT(50) NOT NULL,
    PRIMARY KEY (emp_id, tag_name)
);
ALTER TABLE sja_emp_tag ADD CONSTRAINT fk_sja_emp_tag_emp FOREIGN KEY (emp_id) REFERENCES sja_emp (id);

CREATE TABLE sja_sales (
    id        LONG          NOT NULL,
    sale_year SMALLINT      NOT NULL,
    amount    DECIMAL(10,2),
    PRIMARY KEY (id, sale_year)
);

CREATE TABLE sja_types (
    id          LONG           NOT NULL PRIMARY KEY,
    c_yesno     YESNO,
    c_byte      BYTE,
    c_smallint  SMALLINT,
    c_integer   SMALLINT,
    c_long      LONG,
    c_bigint    BIGINT,
    c_currency  CURRENCY,
    c_single    SINGLE,
    c_double    DOUBLE,
    c_decimal   DECIMAL(18,4),
    c_decimal28 DECIMAL(28,10),
    c_text      TEXT(255),
    c_text10    TEXT(10),
    c_memo      MEMO,
    c_datetime  DATETIME,
    c_date      DATETIME,
    c_binary    VARBINARY(16),
    c_ole       OLE
);
INSERT INTO sja_types (id, c_yesno, c_byte, c_smallint, c_integer, c_long, c_bigint, c_currency, c_single, c_double, c_decimal,
    c_decimal28, c_text, c_text10, c_memo, c_datetime, c_date, c_binary, c_ole)
VALUES (1, TRUE, 255, -32768, 32767, -2147483648, 9223372036854775807, 1234.5678, 3.5, 2.718281828459045,
    12345678901234.1234, 123456789012345678.1234567891, 'text ''quoted'' \ 中文', 'short', 'memo 中文',
    '2024-02-29 23:59:59', '2024-02-29 00:00:00', X'DEADBEEF', X'CAFEBABE');
INSERT INTO sja_types (id, c_yesno, c_byte, c_smallint, c_integer, c_long, c_bigint, c_currency, c_single, c_double, c_decimal,
    c_decimal28, c_text, c_text10, c_memo, c_datetime, c_date, c_binary, c_ole)
VALUES (2, FALSE, 0, 32767, -32768, 2147483647, -9223372036854775808, -9999.9999, -3.5, -12345678901234.5, -0.0001, 0, '', ' ', '',
    '1970-01-01 00:00:00', '9999-12-31 00:00:00', X'00', X'');
INSERT INTO sja_types (id) VALUES (3);
