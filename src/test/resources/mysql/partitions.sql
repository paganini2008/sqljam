-- Partitioned tables of every partitioning type, RANGE is the fixture table sales
DROP TABLE IF EXISTS sjm_pt_range_columns;
DROP TABLE IF EXISTS sjm_pt_list;
DROP TABLE IF EXISTS sjm_pt_list_columns;
DROP TABLE IF EXISTS sjm_pt_hash;
DROP TABLE IF EXISTS sjm_pt_linear_hash;
DROP TABLE IF EXISTS sjm_pt_key;
DROP TABLE IF EXISTS sjm_pt_sub;

CREATE TABLE sjm_pt_range_columns (
    id        INT          NOT NULL,
    sale_date DATE         NOT NULL,
    region    VARCHAR(10)  NOT NULL,
    amount    DECIMAL(10,2),
    PRIMARY KEY (id, sale_date, region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE COLUMNS (sale_date, region) (
    PARTITION p2023 VALUES LESS THAN ('2024-01-01', MAXVALUE),
    PARTITION p2024_n VALUES LESS THAN ('2024-12-31', 'N'),
    PARTITION pmax VALUES LESS THAN (MAXVALUE, MAXVALUE)
);
INSERT INTO sjm_pt_range_columns VALUES (1, '2023-05-01', 'S', 1.5), (2, '2023-12-31', 'N', 2.5),
    (3, '2024-03-01', 'E', 3.5), (4, '2024-06-01', 'S', 4.5), (5, '2025-01-01', 'N', 5.5);

CREATE TABLE sjm_pt_list (
    id        INT NOT NULL,
    region_id INT NOT NULL,
    amount    DECIMAL(10,2),
    PRIMARY KEY (id, region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY LIST (region_id) (
    PARTITION p_north VALUES IN (1, 2),
    PARTITION p_south VALUES IN (3),
    PARTITION p_other VALUES IN (4, 5, 6)
);
INSERT INTO sjm_pt_list VALUES (1, 1, 1.5), (2, 2, 2.5), (3, 3, 3.5), (4, 4, 4.5), (5, 6, 5.5);

CREATE TABLE sjm_pt_list_columns (
    id     INT         NOT NULL,
    region VARCHAR(10) NOT NULL,
    amount DECIMAL(10,2),
    PRIMARY KEY (id, region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY LIST COLUMNS (region) (
    PARTITION p_north VALUES IN ('N', 'NE', 'NW'),
    PARTITION p_south VALUES IN ('S', 'SE'),
    PARTITION p_other VALUES IN ('E', 'W')
);
INSERT INTO sjm_pt_list_columns VALUES (1, 'N', 1.5), (2, 'NE', 2.5), (3, 'S', 3.5), (4, 'E', 4.5), (5, 'W', 5.5);

CREATE TABLE sjm_pt_hash (
    id     INT NOT NULL PRIMARY KEY,
    amount DECIMAL(10,2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY HASH (id) PARTITIONS 4;
INSERT INTO sjm_pt_hash VALUES (1, 1.5), (2, 2.5), (3, 3.5), (4, 4.5), (5, 5.5), (6, 6.5), (7, 7.5);

CREATE TABLE sjm_pt_linear_hash (
    id     INT NOT NULL,
    hired  DATE NOT NULL,
    amount DECIMAL(10,2),
    PRIMARY KEY (id, hired)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY LINEAR HASH (YEAR(hired)) PARTITIONS 3;
INSERT INTO sjm_pt_linear_hash VALUES (1, '2021-01-01', 1.5), (2, '2022-01-01', 2.5), (3, '2023-01-01', 3.5),
    (4, '2024-01-01', 4.5), (5, '2025-01-01', 5.5);

CREATE TABLE sjm_pt_key (
    code   VARCHAR(20) NOT NULL PRIMARY KEY,
    amount DECIMAL(10,2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY KEY (code) PARTITIONS 3;
INSERT INTO sjm_pt_key VALUES ('a', 1.5), ('b', 2.5), ('c', 3.5), ('d', 4.5), ('e', 5.5), ('f', 6.5);

CREATE TABLE sjm_pt_sub (
    id        INT  NOT NULL,
    sale_date DATE NOT NULL,
    amount    DECIMAL(10,2),
    PRIMARY KEY (id, sale_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE (YEAR(sale_date))
SUBPARTITION BY HASH (id) SUBPARTITIONS 2 (
    PARTITION p2023 VALUES LESS THAN (2024),
    PARTITION p2024 VALUES LESS THAN (2025),
    PARTITION pmax VALUES LESS THAN MAXVALUE
);
INSERT INTO sjm_pt_sub VALUES (1, '2023-01-01', 1.5), (2, '2023-06-01', 2.5), (3, '2024-01-01', 3.5),
    (4, '2024-06-01', 4.5), (5, '2025-01-01', 5.5), (6, '2026-01-01', 6.5);
