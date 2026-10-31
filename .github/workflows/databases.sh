#!/bin/bash
#
# Starts the main database servers of the integration tests in Docker, set up like those of 192.168.0.63, for
# the ci environment (mvn verify -Dit.env=ci, it-databases-ci.properties and it-vdb-ci.properties):
#
#   PostgreSQL 17 with pgvector  localhost:15432   database demo, schemas sjit_src and sjit_dst, sjit_novector
#   MySQL 9                      localhost:13306   databases sjit_src and sjit_dst, lower_case_table_names=1
#   Oracle 23ai Free             localhost:11521   service FREEPDB1, user ADMIN with DBA, vector memory 512 MB
#   SQL Server 2025              localhost:11433   database demo (Chinese_PRC_CI_AS) with preview features (float16
#                                                  vectors), schemas sjit_src and sjit_dst
#   Qdrant 1.19                  localhost:16333   api key and read-only key
#   Weaviate 1.39                localhost:18080   api key
#
# Every server has the user admin with the password admin123, PostgreSQL, MySQL, Oracle and SQL Server also the
# user sjit_reader with few privileges. Also on a developer machine with Docker:
#
#   bash .github/workflows/databases.sh          start and set up
#   bash .github/workflows/databases.sh stop     remove the containers
#
set -e

NAMES="sj-postgresql sj-mysql sj-oracle sj-sqlserver sj-qdrant sj-weaviate"
SA_PASSWORD='Sqljam_ci_2026'

if [ "$1" = stop ]; then
    docker rm -f $NAMES >/dev/null 2>&1 || true
    echo "Removed $NAMES"
    exit 0
fi
docker rm -f $NAMES >/dev/null 2>&1 || true

# Started together, they get ready meanwhile
docker run -d --name sj-postgresql -p 15432:5432 -e POSTGRES_USER=admin -e POSTGRES_PASSWORD=admin123 \
    -e POSTGRES_DB=demo pgvector/pgvector:pg17 >/dev/null
docker run -d --name sj-mysql -p 13306:3306 -e MYSQL_ROOT_PASSWORD=admin123 -e MYSQL_USER=admin \
    -e MYSQL_PASSWORD=admin123 -e MYSQL_DATABASE=sjit_src mysql:9 --lower-case-table-names=1 >/dev/null
docker run -d --name sj-oracle -p 11521:1521 -e ORACLE_PASSWORD=admin123 -e APP_USER=admin \
    -e APP_USER_PASSWORD=admin123 gvenzl/oracle-free:23-slim-faststart >/dev/null
docker run -d --name sj-sqlserver -p 11433:1433 -e ACCEPT_EULA=Y -e "MSSQL_SA_PASSWORD=$SA_PASSWORD" \
    -e MSSQL_COLLATION=Chinese_PRC_CI_AS mcr.microsoft.com/mssql/server:2025-latest >/dev/null
docker run -d --name sj-qdrant -p 16333:6333 -e QDRANT__SERVICE__API_KEY=admin123 \
    -e QDRANT__SERVICE__READ_ONLY_API_KEY=readonly123 qdrant/qdrant:v1.19.2 >/dev/null
docker run -d --name sj-weaviate -p 18080:8080 -e AUTHENTICATION_APIKEY_ENABLED=true \
    -e AUTHENTICATION_APIKEY_ALLOWED_KEYS=admin123 -e AUTHENTICATION_APIKEY_USERS=admin \
    -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=false -e AUTHORIZATION_ADMINLIST_ENABLED=true \
    -e AUTHORIZATION_ADMINLIST_USERS=admin -e DEFAULT_VECTORIZER_MODULE=none -e CLUSTER_HOSTNAME=node1 \
    -e PERSISTENCE_DATA_PATH=/var/lib/weaviate cr.weaviate.io/semitechnologies/weaviate:1.39.11 >/dev/null

# Runs the command until it succeeds, 5 minutes at most
wait_for() {
    local name=$1
    shift
    for i in $(seq 1 150); do
        if "$@" >/dev/null 2>&1; then
            echo "$name is ready"
            return 0
        fi
        sleep 2
    done
    echo "$name is not ready" >&2
    docker logs --tail 50 "sj-$name" >&2 || true
    return 1
}

wait_for postgresql docker exec sj-postgresql psql -U admin -d demo -c 'SELECT 1'
docker exec sj-postgresql psql -q -U admin -d demo -c 'CREATE EXTENSION IF NOT EXISTS vector' \
    -c 'CREATE SCHEMA IF NOT EXISTS sjit_src' -c 'CREATE SCHEMA IF NOT EXISTS sjit_dst'
docker exec sj-postgresql psql -q -U admin -d demo -c 'CREATE DATABASE sjit_novector' \
    -c "CREATE ROLE sjit_reader LOGIN PASSWORD 'sjit_reader123'"

wait_for mysql docker exec sj-mysql mysql -uroot -padmin123 -e 'SELECT 1'
docker exec sj-mysql mysql -uroot -padmin123 -e "CREATE DATABASE IF NOT EXISTS sjit_dst;
    GRANT ALL PRIVILEGES ON *.* TO 'admin'@'%' WITH GRANT OPTION;
    CREATE USER 'sjit_reader'@'%' IDENTIFIED BY 'sjit_reader123'; GRANT SELECT ON sjit_src.* TO 'sjit_reader'@'%';
    FLUSH PRIVILEGES;"

wait_for qdrant curl -sf http://localhost:16333/collections -H 'api-key: admin123'
wait_for weaviate curl -sf http://localhost:18080/v1/meta -H 'Authorization: Bearer admin123'

SQLCMD="/opt/mssql-tools18/bin/sqlcmd -C -b -S localhost -U sa -P $SA_PASSWORD"
wait_for sqlserver docker exec sj-sqlserver $SQLCMD -Q 'SELECT 1'
docker exec sj-sqlserver $SQLCMD -Q "CREATE LOGIN admin WITH PASSWORD = 'admin123', CHECK_POLICY = OFF;
    ALTER SERVER ROLE sysadmin ADD MEMBER admin; CREATE DATABASE demo;
    CREATE LOGIN sjit_reader WITH PASSWORD = 'Ro#Vector2026x', CHECK_POLICY = OFF;"
docker exec sj-sqlserver $SQLCMD -d demo -Q "EXEC('CREATE SCHEMA sjit_src'); EXEC('CREATE SCHEMA sjit_dst');
    ALTER DATABASE SCOPED CONFIGURATION SET PREVIEW_FEATURES = ON; CREATE USER sjit_reader FOR LOGIN sjit_reader;"

wait_for oracle docker exec sj-oracle healthcheck.sh
printf '%s\n' 'GRANT DBA TO admin;' 'CREATE USER sjit_reader IDENTIFIED BY "Sjit_reader123";' \
    'GRANT CREATE SESSION, CREATE TABLE TO sjit_reader;' 'ALTER USER sjit_reader QUOTA UNLIMITED ON USERS;' | docker exec -i sj-oracle sqlplus -s 'sys/admin123@//localhost/FREEPDB1 as sysdba'
# Vector indexes need the vector memory, it is given at the start of the instance
echo 'ALTER SYSTEM SET vector_memory_size=512M SCOPE=SPFILE;' | docker exec -i sj-oracle sqlplus -s '/ as sysdba'
docker restart sj-oracle >/dev/null
wait_for oracle docker exec sj-oracle healthcheck.sh

echo "Databases are ready"
