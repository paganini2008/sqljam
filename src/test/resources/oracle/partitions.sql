BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_ref_child CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_ref_parent CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_list CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_hash CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_interval CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_range_hash CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_range_list CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_list_auto CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/
BEGIN EXECUTE IMMEDIATE 'DROP TABLE sjo_pt_multi CASCADE CONSTRAINTS PURGE'; EXCEPTION WHEN OTHERS THEN NULL; END;
/

-- LIST with a DEFAULT partition
CREATE TABLE sjo_pt_list (
    id     NUMBER(10)   NOT NULL,
    region VARCHAR2(10) NOT NULL,
    amount NUMBER(10,2),
    PRIMARY KEY (id, region)
) PARTITION BY LIST (region) (
    PARTITION p_north VALUES ('N', 'NE', 'NW'),
    PARTITION p_south VALUES ('S'),
    PARTITION p_other VALUES (DEFAULT)
);
INSERT INTO sjo_pt_list VALUES (1, 'N', 1.5);
INSERT INTO sjo_pt_list VALUES (2, 'NE', 2.5);
INSERT INTO sjo_pt_list VALUES (3, 'S', 3.5);
INSERT INTO sjo_pt_list VALUES (4, 'E', 4.5);

CREATE TABLE sjo_pt_hash (
    id     NUMBER(10) NOT NULL PRIMARY KEY,
    amount NUMBER(10,2)
) PARTITION BY HASH (id) (PARTITION p_h1, PARTITION p_h2, PARTITION p_h3);
INSERT INTO sjo_pt_hash VALUES (1, 1.5);
INSERT INTO sjo_pt_hash VALUES (2, 2.5);
INSERT INTO sjo_pt_hash VALUES (3, 3.5);
INSERT INTO sjo_pt_hash VALUES (4, 4.5);
INSERT INTO sjo_pt_hash VALUES (5, 5.5);

-- INTERVAL: partitions of later months are created by the rows
CREATE TABLE sjo_pt_interval (
    id        NUMBER(10) NOT NULL,
    sale_date DATE       NOT NULL,
    amount    NUMBER(10,2),
    PRIMARY KEY (id, sale_date)
) PARTITION BY RANGE (sale_date) INTERVAL (NUMTOYMINTERVAL(1, 'MONTH')) (
    PARTITION p_before VALUES LESS THAN (DATE '2024-01-01')
);
INSERT INTO sjo_pt_interval VALUES (1, DATE '2023-12-15', 1.5);
INSERT INTO sjo_pt_interval VALUES (2, DATE '2024-01-15', 2.5);
INSERT INTO sjo_pt_interval VALUES (3, DATE '2024-03-15', 3.5);

-- Composite RANGE-HASH
CREATE TABLE sjo_pt_range_hash (
    id        NUMBER(10) NOT NULL,
    sale_year NUMBER(4)  NOT NULL,
    amount    NUMBER(10,2),
    PRIMARY KEY (id, sale_year)
) PARTITION BY RANGE (sale_year) SUBPARTITION BY HASH (id) SUBPARTITIONS 2 (
    PARTITION p2023 VALUES LESS THAN (2024),
    PARTITION pmax VALUES LESS THAN (MAXVALUE)
);
INSERT INTO sjo_pt_range_hash VALUES (1, 2023, 1.5);
INSERT INTO sjo_pt_range_hash VALUES (2, 2023, 2.5);
INSERT INTO sjo_pt_range_hash VALUES (3, 2024, 3.5);
INSERT INTO sjo_pt_range_hash VALUES (4, 2025, 4.5);

-- Composite RANGE-LIST with named subpartitions
CREATE TABLE sjo_pt_range_list (
    id        NUMBER(10)   NOT NULL,
    sale_year NUMBER(4)    NOT NULL,
    region    VARCHAR2(10) NOT NULL,
    amount    NUMBER(10,2),
    PRIMARY KEY (id, sale_year, region)
) PARTITION BY RANGE (sale_year) SUBPARTITION BY LIST (region) (
    PARTITION p2023 VALUES LESS THAN (2024) (
        SUBPARTITION p2023_n VALUES ('N'),
        SUBPARTITION p2023_other VALUES (DEFAULT)
    ),
    PARTITION pmax VALUES LESS THAN (MAXVALUE) (
        SUBPARTITION pmax_n VALUES ('N'),
        SUBPARTITION pmax_other VALUES (DEFAULT)
    )
);
INSERT INTO sjo_pt_range_list VALUES (1, 2023, 'N', 1.5);
INSERT INTO sjo_pt_range_list VALUES (2, 2023, 'S', 2.5);
INSERT INTO sjo_pt_range_list VALUES (3, 2024, 'N', 3.5);
INSERT INTO sjo_pt_range_list VALUES (4, 2024, 'E', 4.5);

-- AUTOMATIC LIST: partitions of new values are created by the rows
CREATE TABLE sjo_pt_list_auto (
    id     NUMBER(10)   NOT NULL,
    region VARCHAR2(10) NOT NULL,
    amount NUMBER(10,2),
    PRIMARY KEY (id, region)
) PARTITION BY LIST (region) AUTOMATIC (
    PARTITION p_north VALUES ('N')
);
INSERT INTO sjo_pt_list_auto VALUES (1, 'N', 1.5);
INSERT INTO sjo_pt_list_auto VALUES (2, 'S', 2.5);
INSERT INTO sjo_pt_list_auto VALUES (3, 'E', 3.5);

-- RANGE of two columns
CREATE TABLE sjo_pt_multi (
    id         NUMBER(10) NOT NULL,
    sale_year  NUMBER(4)  NOT NULL,
    sale_month NUMBER(2)  NOT NULL,
    amount     NUMBER(10,2),
    PRIMARY KEY (id, sale_year, sale_month)
) PARTITION BY RANGE (sale_year, sale_month) (
    PARTITION p_h1 VALUES LESS THAN (2024, 7),
    PARTITION p_h2 VALUES LESS THAN (2025, 1),
    PARTITION pmax VALUES LESS THAN (MAXVALUE, MAXVALUE)
);
INSERT INTO sjo_pt_multi VALUES (1, 2024, 1, 1.5);
INSERT INTO sjo_pt_multi VALUES (2, 2024, 7, 2.5);
INSERT INTO sjo_pt_multi VALUES (3, 2025, 3, 3.5);

-- REFERENCE: the child is partitioned like its parent by the foreign key
CREATE TABLE sjo_pt_ref_parent (
    id        NUMBER(10) NOT NULL PRIMARY KEY,
    sale_year NUMBER(4)  NOT NULL
) PARTITION BY RANGE (sale_year) (
    PARTITION p2023 VALUES LESS THAN (2024),
    PARTITION pmax VALUES LESS THAN (MAXVALUE)
);
CREATE TABLE sjo_pt_ref_child (
    id        NUMBER(10) NOT NULL PRIMARY KEY,
    parent_id NUMBER(10) NOT NULL,
    amount    NUMBER(10,2),
    CONSTRAINT fk_sjo_pt_ref FOREIGN KEY (parent_id) REFERENCES sjo_pt_ref_parent (id)
) PARTITION BY REFERENCE (fk_sjo_pt_ref);
INSERT INTO sjo_pt_ref_parent VALUES (1, 2023);
INSERT INTO sjo_pt_ref_parent VALUES (2, 2024);
INSERT INTO sjo_pt_ref_child VALUES (1, 1, 1.5);
INSERT INTO sjo_pt_ref_child VALUES (2, 2, 2.5);
INSERT INTO sjo_pt_ref_child VALUES (3, 2, 3.5);
COMMIT;
