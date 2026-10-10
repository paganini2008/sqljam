DROP TABLE IF EXISTS sjc_emp_tag;
DROP TABLE IF EXISTS sjc_emp;
DROP TABLE IF EXISTS sjc_dept;
DROP TABLE IF EXISTS sjc_sales;
DROP TABLE IF EXISTS sjc_types;

-- ClickHouse has no identity columns, foreign keys and unique indexes, the primary key is the sorting key
CREATE TABLE sjc_dept (
    id         Int32,
    name       String,
    budget     Nullable(Decimal(12,2)) DEFAULT 0,
    created_at Nullable(DateTime) DEFAULT now(),
    PRIMARY KEY (id)
) ENGINE = MergeTree COMMENT 'Department';

CREATE TABLE sjc_emp (
    id         Int64,
    dept_id    Nullable(Int32),
    name       String COMMENT 'Employee name',
    email      String,
    salary     Nullable(Decimal(10,2)),
    active     Nullable(Bool) DEFAULT true,
    hired      Nullable(Date32),
    updated_at Nullable(DateTime64(3)),
    photo      Nullable(String),
    profile    Nullable(String),
    note       Nullable(String) DEFAULT 'n/a',
    PRIMARY KEY (id)
) ENGINE = MergeTree;

CREATE TABLE sjc_emp_tag (
    emp_id   Int64,
    tag_name String
) ENGINE = MergeTree ORDER BY (emp_id, tag_name);

CREATE TABLE sjc_sales (
    id        Int32,
    sale_year Int32,
    amount    Nullable(Decimal(10,2))
) ENGINE = MergeTree PARTITION BY sale_year ORDER BY (id, sale_year);

CREATE TABLE sjc_types (
    id             Int32,
    c_bool         Nullable(Bool),
    c_int8         Nullable(Int8),
    c_int16        Nullable(Int16),
    c_int32        Nullable(Int32),
    c_int64        Nullable(Int64),
    c_int128       Nullable(Int128),
    c_int256       Nullable(Int256),
    c_uint8        Nullable(UInt8),
    c_uint16       Nullable(UInt16),
    c_uint32       Nullable(UInt32),
    c_uint64       Nullable(UInt64),
    c_float32      Nullable(Float32),
    c_float64      Nullable(Float64),
    c_decimal      Nullable(Decimal(18,4)),
    c_decimal38    Nullable(Decimal(38,10)),
    c_decimal76    Nullable(Decimal(76,10)),
    c_string       Nullable(String),
    c_fixed        Nullable(FixedString(4)),
    c_low          LowCardinality(Nullable(String)),
    c_date         Nullable(Date),
    c_date32       Nullable(Date32),
    c_datetime     Nullable(DateTime),
    c_datetime64   Nullable(DateTime64(6)),
    c_datetime64_9 Nullable(DateTime64(9)),
    c_uuid         Nullable(UUID),
    c_ipv4         Nullable(IPv4),
    c_ipv6         Nullable(IPv6),
    c_enum         Enum8('red' = 1, 'green' = 2, 'blue' = 3) DEFAULT 'red',
    c_array        Array(Int32),
    c_array_str    Array(Nullable(String)),
    c_map          Map(String, Int32),
    c_tuple        Tuple(a Int32, b String),
    c_enum16       Enum16('low' = 1, 'high' = 1000) DEFAULT 'low',
    c_datetime_tz  Nullable(DateTime('Asia/Shanghai')),
    c_decimal32    Nullable(Decimal32(2)),
    c_variant      Variant(Int64, String),
    c_point        Point,
    c_gen          Int32 MATERIALIZED id * 2,
    c_alias        Int32 ALIAS id + 1
) ENGINE = MergeTree ORDER BY id COMMENT 'All types';

INSERT INTO sjc_types (id, c_bool, c_int8, c_int16, c_int32, c_int64, c_int128, c_int256, c_uint8, c_uint16,
    c_uint32, c_uint64, c_float32, c_float64, c_decimal, c_decimal38, c_decimal76, c_string, c_fixed, c_low, c_date,
    c_date32, c_datetime, c_datetime64, c_datetime64_9, c_uuid, c_ipv4, c_ipv6, c_enum, c_array, c_array_str, c_map,
    c_tuple)
VALUES (1, true, -128, -32768, -2147483648, -9223372036854775808, 99999999999999999999999999999999999999,
    -57896044618658097711785492504343953926634992332820282019728792003956564819968, 255, 65535, 4294967295,
    18446744073709551615, 3.5, 2.718281828459045, 12345678901234.1234, 1234567890123456789012345678.0123456789,
    123456789012345678901234567890123456789012345678901234567890123456.0123456789, 'string ''quoted'' \\ 中文',
    'abcd', 'low', '2024-02-29', '1900-01-01', '2024-02-29 23:59:59', '2024-02-29 23:59:59.123456',
    '2024-02-29 23:59:59.123456789', 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '192.168.1.1', '2001:db8::1', 'green',
    [1, 2, 3], ['a', NULL], {'k1': 1, 'k2': 2}, (1, 'x'));
INSERT INTO sjc_types (id, c_bool, c_int8, c_int16, c_int32, c_int64, c_uint64, c_float32, c_float64, c_decimal,
    c_string, c_fixed, c_date, c_date32, c_datetime, c_datetime64)
VALUES (2, false, 127, 32767, 2147483647, 9223372036854775807, 0, -3.5, -1E100, -0.0001, '', 'ab', '1970-01-01',
    '2299-12-31', '1970-01-01 00:00:00', '2299-12-31 23:59:59.999999');
INSERT INTO sjc_types (id) VALUES (3);
ALTER TABLE sjc_types UPDATE c_enum16 = 'high', c_datetime_tz = toDateTime('2024-02-29 20:00:00', 'Asia/Shanghai'),
    c_decimal32 = 1234567.89, c_variant = 'text', c_point = (1.5, 2.5) WHERE id = 1 SETTINGS mutations_sync = 1;
ALTER TABLE sjc_types UPDATE c_variant = 42::Int64 WHERE id = 2 SETTINGS mutations_sync = 1;
