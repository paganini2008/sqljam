-- Partitioned tables of both range directions and other key types, RANGE RIGHT of INT is the fixture table sales
DROP TABLE IF EXISTS dbo.sjs_pt_left;
DROP TABLE IF EXISTS dbo.sjs_pt_date;
IF NOT EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = 'pf_sjs_pt_left') EXEC('CREATE PARTITION FUNCTION pf_sjs_pt_left (INT) AS RANGE LEFT FOR VALUES (10, 20, 30)');
IF NOT EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = 'ps_sjs_pt_left') EXEC('CREATE PARTITION SCHEME ps_sjs_pt_left AS PARTITION pf_sjs_pt_left ALL TO ([PRIMARY])');
IF NOT EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = 'pf_sjs_pt_date') EXEC('CREATE PARTITION FUNCTION pf_sjs_pt_date (DATE) AS RANGE RIGHT FOR VALUES (''2024-01-01'', ''2025-01-01'')');
IF NOT EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = 'ps_sjs_pt_date') EXEC('CREATE PARTITION SCHEME ps_sjs_pt_date AS PARTITION pf_sjs_pt_date ALL TO ([PRIMARY])');

-- RANGE LEFT: a boundary value belongs to the partition on its left
CREATE TABLE dbo.sjs_pt_left (
    id     INT NOT NULL,
    amount DECIMAL(10,2),
    CONSTRAINT pk_sjs_pt_left PRIMARY KEY (id)
) ON ps_sjs_pt_left (id);
INSERT INTO dbo.sjs_pt_left VALUES (5, 1.5), (10, 2.5), (11, 3.5), (25, 4.5), (30, 5.5), (40, 6.5);

-- Heap partitioned by a date
CREATE TABLE dbo.sjs_pt_date (
    id        INT  NOT NULL,
    sale_date DATE NOT NULL,
    amount    DECIMAL(10,2)
) ON ps_sjs_pt_date (sale_date);
INSERT INTO dbo.sjs_pt_date VALUES (1, '2023-06-01', 1.5), (2, '2024-01-01', 2.5), (3, '2024-06-01', 3.5),
    (4, '2025-03-01', 4.5);
