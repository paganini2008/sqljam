-- Partitioned tables of every partitioning type, RANGE is the fixture table sales
DROP TABLE IF EXISTS sjit_src.sjp_pt_list;
DROP TABLE IF EXISTS sjit_src.sjp_pt_hash;
DROP TABLE IF EXISTS sjit_src.sjp_pt_multi;
DROP TABLE IF EXISTS sjit_src.sjp_pt_sub;
DROP TABLE IF EXISTS sjit_src.sjp_pt_expr;

-- LIST with a DEFAULT partition
CREATE TABLE sjit_src.sjp_pt_list (
    id     INTEGER     NOT NULL,
    region VARCHAR(10) NOT NULL,
    amount NUMERIC(10,2),
    PRIMARY KEY (id, region)
) PARTITION BY LIST (region);
CREATE TABLE sjit_src.sjp_pt_list_north PARTITION OF sjit_src.sjp_pt_list FOR VALUES IN ('N', 'NE', 'NW');
CREATE TABLE sjit_src.sjp_pt_list_south PARTITION OF sjit_src.sjp_pt_list FOR VALUES IN ('S');
CREATE TABLE sjit_src.sjp_pt_list_other PARTITION OF sjit_src.sjp_pt_list DEFAULT;
INSERT INTO sjit_src.sjp_pt_list VALUES (1, 'N', 1.5), (2, 'NE', 2.5), (3, 'S', 3.5), (4, 'E', 4.5), (5, 'W', 5.5);

CREATE TABLE sjit_src.sjp_pt_hash (
    id     INTEGER NOT NULL PRIMARY KEY,
    amount NUMERIC(10,2)
) PARTITION BY HASH (id);
CREATE TABLE sjit_src.sjp_pt_hash_0 PARTITION OF sjit_src.sjp_pt_hash FOR VALUES WITH (MODULUS 3, REMAINDER 0);
CREATE TABLE sjit_src.sjp_pt_hash_1 PARTITION OF sjit_src.sjp_pt_hash FOR VALUES WITH (MODULUS 3, REMAINDER 1);
CREATE TABLE sjit_src.sjp_pt_hash_2 PARTITION OF sjit_src.sjp_pt_hash FOR VALUES WITH (MODULUS 3, REMAINDER 2);
INSERT INTO sjit_src.sjp_pt_hash VALUES (1, 1.5), (2, 2.5), (3, 3.5), (4, 4.5), (5, 5.5), (6, 6.5), (7, 7.5);

-- RANGE of two columns
CREATE TABLE sjit_src.sjp_pt_multi (
    id         INTEGER  NOT NULL,
    sale_year  SMALLINT NOT NULL,
    sale_month SMALLINT NOT NULL,
    amount     NUMERIC(10,2),
    PRIMARY KEY (id, sale_year, sale_month)
) PARTITION BY RANGE (sale_year, sale_month);
CREATE TABLE sjit_src.sjp_pt_multi_h1 PARTITION OF sjit_src.sjp_pt_multi FOR VALUES FROM (2024, 1) TO (2024, 7);
CREATE TABLE sjit_src.sjp_pt_multi_h2 PARTITION OF sjit_src.sjp_pt_multi FOR VALUES FROM (2024, 7) TO (2025, 1);
INSERT INTO sjit_src.sjp_pt_multi VALUES (1, 2024, 1, 1.5), (2, 2024, 6, 2.5), (3, 2024, 7, 3.5),
    (4, 2024, 12, 4.5);

-- Two levels: years, the year 2024 is partitioned by region
CREATE TABLE sjit_src.sjp_pt_sub (
    id        INTEGER     NOT NULL,
    sale_date DATE        NOT NULL,
    region    VARCHAR(10) NOT NULL,
    amount    NUMERIC(10,2),
    PRIMARY KEY (id, sale_date, region)
) PARTITION BY RANGE (sale_date);
CREATE TABLE sjit_src.sjp_pt_sub_2023 PARTITION OF sjit_src.sjp_pt_sub
    FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE sjit_src.sjp_pt_sub_2024 PARTITION OF sjit_src.sjp_pt_sub
    FOR VALUES FROM ('2024-01-01') TO ('2025-01-01') PARTITION BY LIST (region);
CREATE TABLE sjit_src.sjp_pt_sub_2024_n PARTITION OF sjit_src.sjp_pt_sub_2024 FOR VALUES IN ('N');
CREATE TABLE sjit_src.sjp_pt_sub_2024_s PARTITION OF sjit_src.sjp_pt_sub_2024 FOR VALUES IN ('S');
INSERT INTO sjit_src.sjp_pt_sub VALUES (1, '2023-05-01', 'N', 1.5), (2, '2023-08-01', 'S', 2.5),
    (3, '2024-02-01', 'N', 3.5), (4, '2024-03-01', 'S', 4.5), (5, '2024-04-01', 'S', 5.5);

-- LIST of an expression
CREATE TABLE sjit_src.sjp_pt_expr (
    id     INTEGER     NOT NULL,
    code   VARCHAR(10) NOT NULL,
    amount NUMERIC(10,2)
) PARTITION BY LIST (lower(code));
CREATE TABLE sjit_src.sjp_pt_expr_a PARTITION OF sjit_src.sjp_pt_expr FOR VALUES IN ('a');
CREATE TABLE sjit_src.sjp_pt_expr_b PARTITION OF sjit_src.sjp_pt_expr FOR VALUES IN ('b');
INSERT INTO sjit_src.sjp_pt_expr VALUES (1, 'A', 1.5), (2, 'a', 2.5), (3, 'B', 3.5);
