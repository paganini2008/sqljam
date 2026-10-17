-- Partition keys of expressions, a key of a column is the fixture table sales
DROP TABLE IF EXISTS sjc_pt_month;
DROP TABLE IF EXISTS sjc_pt_tuple;

CREATE TABLE sjc_pt_month (
    id      Int32,
    created Date,
    amount  Decimal(10,2)
) ENGINE = MergeTree PARTITION BY toYYYYMM(created) ORDER BY id;
INSERT INTO sjc_pt_month VALUES (1, '2024-01-15', 1.5), (2, '2024-01-20', 2.5), (3, '2024-02-01', 3.5),
    (4, '2024-03-01', 4.5);

CREATE TABLE sjc_pt_tuple (
    id      Int32,
    region  String,
    created Date,
    amount  Decimal(10,2)
) ENGINE = MergeTree PARTITION BY (region, toYear(created)) ORDER BY (region, id);
INSERT INTO sjc_pt_tuple VALUES (1, 'N', '2023-05-01', 1.5), (2, 'N', '2024-05-01', 2.5), (3, 'S', '2024-06-01', 3.5);
