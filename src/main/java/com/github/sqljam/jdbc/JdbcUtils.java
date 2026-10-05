/*
 * Copyright 2023-2026 Fred Feng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.sqljam.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Types;
import java.sql.Blob;
import java.sql.Clob;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.StringUtils;
import com.github.sqljam.utils.CaseInsensitiveMap;
import com.github.sqljam.utils.ConvertUtils;
import com.github.sqljam.utils.Observable;
import lombok.experimental.UtilityClass;

/**
 * @Description: JdbcUtils executes statements, reads metadata and converts column values to portable java objects
 * @Author: Fred Feng
 * @Date: 24/03/2023
 * @Version 1.0.0
 */
@UtilityClass
public class JdbcUtils {

    public static Connection getConnection(String url, String user, String password)
            throws SQLException {
        if (StringUtils.isBlank(user) && StringUtils.isBlank(password)) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Executes any statement, including statements returning results (e.g. SELECT setval(...))
     */
    public boolean execute(Connection connection, String sql) throws SQLException {
        try (Statement stm = connection.createStatement()) {
            return stm.execute(sql);
        }
    }

    public int update(Connection connection, String sql) throws SQLException {
        Statement stm = null;
        try {
            stm = connection.createStatement();
            return stm.executeUpdate(sql);
        } finally {
            closeQuietly(stm);
        }
    }

    public int update(Connection connection, String sql, Object[] args) throws SQLException {
        return update(connection, sql, setValues(args));
    }

    public int update(Connection connection, String sql, PreparedStatementCallback callback)
            throws SQLException {
        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(sql);
            if (callback != null) {
                callback.setValues(ps);
            }
            return ps.executeUpdate();
        } finally {
            closeQuietly(ps);
        }
    }

    public int[] batchUpdate(Connection connection, String sql, List<Object[]> argsList)
            throws SQLException {
        return batchUpdate(connection, sql, setValues(argsList));
    }

    public int[] batchUpdate(Connection connection, String sql, PreparedStatementCallback callback)
            throws SQLException {
        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(sql);
            if (callback != null) {
                callback.setValues(ps);
            }
            return ps.executeBatch();
        } finally {
            closeQuietly(ps);
        }
    }

    public List<Map<String, Object>> fetchAll(Connection connection, String sql)
            throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        Statement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            rs = ps.executeQuery(sql);
            if (rs != null) {
                while (rs.next()) {
                    list.add(toMap(rs, false));
                }
            }
            return list;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public <T> List<T> fetchAll(Connection connection, String sql, Class<T> resultClass)
            throws SQLException {
        Statement sm = null;
        ResultSet rs = null;
        List<T> list = new ArrayList<>();
        try {
            sm = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            rs = sm.executeQuery(sql);
            if (rs != null) {
                while (rs.next()) {
                    list.add(toObject(rs, resultClass));
                }
            }
            return list;
        } finally {
            closeQuietly(rs);
            closeQuietly(sm);
        }
    }

    public List<Map<String, Object>> fetchAll(Connection connection, String sql, Object[] args)
            throws SQLException {
        return fetchAll(connection, sql, setValues(args));
    }

    public List<Map<String, Object>> fetchAll(Connection connection, String sql,
            PreparedStatementCallback callback) throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(sql);
            if (callback != null) {
                callback.setValues(ps);
            }
            rs = ps.executeQuery();
            if (rs != null) {
                while (rs.next()) {
                    list.add(toMap(rs, false));
                }
            }
            return list;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public <T> List<T> fetchAll(Connection connection, String sql, Object[] args,
            Class<T> resultClass) throws SQLException {
        return fetchAll(connection, sql, setValues(args), resultClass);
    }

    public <T> List<T> fetchAll(Connection connection, String sql,
            PreparedStatementCallback callback, Class<T> resultClass) throws SQLException {
        List<T> list = new ArrayList<>();
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(sql);
            if (callback != null) {
                callback.setValues(ps);
            }
            rs = ps.executeQuery();
            if (rs != null) {
                while (rs.next()) {
                    list.add(toObject(rs, resultClass));
                }
            }
            return list;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public static <T> T fetchOne(Connection connection, String sql, Class<T> requiredType)
            throws SQLException {
        Map<String, Object> one = fetchOne(connection, sql);
        if (one == null || one.isEmpty()) {
            return null;
        }
        return ConvertUtils.convert(one.values().toArray()[0], requiredType);
    }

    public static Map<String, Object> fetchOne(Connection connection, String sql)
            throws SQLException {
        Statement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.createStatement();
            rs = ps.executeQuery(sql);
            if (rs != null && rs.next()) {
                return toMap(rs, false);
            }
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public static <T> T fetchOne(Connection connection, String sql, Object[] args,
            Class<T> requiredType) throws SQLException {
        return fetchOne(connection, sql, setValues(args), requiredType);
    }

    public static <T> T fetchOne(Connection connection, String sql,
            PreparedStatementCallback callback, Class<T> requiredType) throws SQLException {
        Map<String, Object> one = fetchOne(connection, sql, callback);
        if (one == null || one.isEmpty()) {
            return null;
        }
        return ConvertUtils.convert(one.values().toArray()[0], requiredType);
    }

    public static Map<String, Object> fetchOne(Connection connection, String sql, Object[] args)
            throws SQLException {
        return fetchOne(connection, sql, setValues(args));
    }

    public static Map<String, Object> fetchOne(Connection connection, String sql,
            PreparedStatementCallback callback) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = connection.prepareStatement(sql);
            if (callback != null) {
                callback.setValues(ps);
            }
            rs = ps.executeQuery();
            if (rs != null && rs.next()) {
                return toMap(rs, false);
            }
            return null;
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public Cursor<Map<String, Object>> getCursor(Connection connection, String sql, Object[] args)
            throws SQLException {
        return getCursor(connection, sql, setValues(args));
    }

    public Cursor<Map<String, Object>> getCursor(Connection connection, String sql,
            PreparedStatementCallback callback) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            ps = connection.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            if (callback != null) {
                callback.setValues(ps);
            }
            rs = ps.executeQuery();
            return new MapCursor(rs, ob, true);
        } finally {
            closeLazily(ob, rs, ps, null);
        }
    }

    public <T> Cursor<T> getCursor(Connection connection, String sql, Object[] args,
            Class<T> resultClass) throws SQLException {
        return getCursor(connection, sql, setValues(args), resultClass);
    }

    public <T> Cursor<T> getCursor(Connection connection, String sql,
            PreparedStatementCallback callback, Class<T> resultClass) throws SQLException {
        PreparedStatement ps = null;
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            ps = connection.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            if (callback != null) {
                callback.setValues(ps);
            }
            rs = ps.executeQuery();
            return new ObjectCursor<T>(rs, ob, resultClass);
        } finally {
            closeLazily(ob, rs, ps, null);
        }
    }

    private PreparedStatementCallback setValues(Object[] args) {
        return ps -> {
            setValues(ps, args);
        };
    }

    private PreparedStatementCallback setValues(List<Object[]> argsList) {
        return ps -> {
            for (Object[] args : argsList) {
                if (args != null && args.length > 0) {
                    setValues(ps, args);
                    ps.addBatch();
                }
            }
        };
    }

    public static void setValues(PreparedStatement ps, Object[] args) throws SQLException {
        if (args != null && args.length > 0) {
            int parameterIndex = 1;
            for (Object arg : args) {
                ps.setObject(parameterIndex++, arg);
            }
        }
    }

    /**
     * Reads column value as a portable java object, LOBs are materialized so that the value is still available after
     * the result set closed.
     */
    public static Object getColumnValue(ResultSet rs, int columnIndex, int columnType) throws SQLException {
        return getColumnValue(rs, columnIndex, columnType, null);
    }

    /**
     * Reads column value as a portable java object, LOBs are materialized so that the value is still available after
     * the result set closed.
     *
     * @param typeName database specific type name, used for types which jdbc type code is not enough
     */
    public static Object getColumnValue(ResultSet rs, int columnIndex, int columnType, String typeName)
            throws SQLException {
        String lowerTypeName = typeName != null ? typeName.toLowerCase(java.util.Locale.ENGLISH) : "";
        switch (lowerTypeName) {
            case "money":
                // PostgreSQL money is formatted with currency symbol and group separators, e.g. $1,234.56
                return parseMoney(rs.getString(columnIndex));
            case "timetz":
            case "uuid":
            case "uniqueidentifier":
            case "xmltype":
            case "json":
            case "rowid":
            case "urowid":
            case "interval":
                return rs.getString(columnIndex);
            case "java_object":
                return rs.getBytes(columnIndex);
            default:
                break;
        }
        if (lowerTypeName.endsWith("xmltype")) {
            return rs.getString(columnIndex);
        }
        if (lowerTypeName.startsWith("interval") || lowerTypeName.startsWith("_")) {
            // Oracle intervals and PostgreSQL arrays keep their text representation
            return rs.getString(columnIndex);
        }
        Object value;
        switch (columnType) {
            case Types.CLOB:
            case Types.NCLOB:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                value = rs.getString(columnIndex);
                break;
            case Types.BLOB:
            case Types.LONGVARBINARY:
            case Types.VARBINARY:
            case Types.BINARY:
                value = rs.getBytes(columnIndex);
                break;
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
            case -102: // oracle TIMESTAMP WITH LOCAL TIME ZONE
                try {
                    // Date/time without time zone are read as local values to keep their wall clock time
                    value = columnType == Types.DATE ? getTemporal(rs, columnIndex, java.time.LocalDate.class)
                            : columnType == Types.TIME ? getTemporal(rs, columnIndex, java.time.LocalTime.class)
                            : getTemporal(rs, columnIndex, java.time.LocalDateTime.class);
                } catch (SQLException e) {
                    // Dates stored as text (SQLite) in other formats
                    value = rs.getString(columnIndex);
                }
                break;
            case Types.TIME_WITH_TIMEZONE:
                value = rs.getObject(columnIndex);
                break;
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case -101: // oracle TIMESTAMP WITH TIME ZONE
            case -155: // sql server DATETIMEOFFSET
                value = rs.getObject(columnIndex, java.time.OffsetDateTime.class);
                break;
            case Types.SQLXML:
                SQLXML xml = rs.getSQLXML(columnIndex);
                value = xml != null ? xml.getString() : null;
                break;
            default:
                value = rs.getObject(columnIndex);
                break;
        }
        return normalizeValue(value);
    }

    private static Object getTemporal(ResultSet rs, int columnIndex, Class<?> type) throws SQLException {
        try {
            return rs.getObject(columnIndex, type);
        } catch (SQLException | RuntimeException e) {
            if (type == java.time.LocalDate.class) {
                return rs.getDate(columnIndex);
            } else if (type == java.time.LocalTime.class) {
                return rs.getTime(columnIndex);
            }
            return rs.getTimestamp(columnIndex);
        }
    }

    static java.math.BigDecimal parseMoney(String text) {
        if (text == null) {
            return null;
        }
        String number = text.trim();
        boolean negative = number.startsWith("-") || (number.startsWith("(") && number.endsWith(")"));
        number = number.replaceAll("[^0-9.]", "");
        if (number.isEmpty()) {
            return null;
        }
        java.math.BigDecimal value = new java.math.BigDecimal(number);
        return negative ? value.negate() : value;
    }

    public static Object normalizeValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            return clob.getSubString(1, (int) clob.length());
        } else if (value instanceof Blob) {
            Blob blob = (Blob) value;
            return blob.getBytes(1, (int) blob.length());
        } else if (value instanceof SQLXML) {
            return ((SQLXML) value).getString();
        } else if (value instanceof java.sql.Array) {
            Object array = ((java.sql.Array) value).getArray();
            return array instanceof Object[] ? array : String.valueOf(array);
        } else if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime();
        } else if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        } else if (value instanceof java.sql.Time) {
            return ((java.sql.Time) value).toLocalTime();
        } else if (value instanceof java.math.BigInteger) {
            // BIGINT UNSIGNED of MySQL
            return new java.math.BigDecimal((java.math.BigInteger) value);
        } else if (value instanceof java.util.UUID) {
            return value.toString();
        } else if (value instanceof java.time.Year) {
            return ((java.time.Year) value).getValue();
        }
        String className = value.getClass().getName();
        if (className.startsWith("org.postgresql.") || className.startsWith("oracle.") || className.startsWith(
                "microsoft.sql.")) {
            // PGobject (json, uuid, inet ...), oracle.sql.* and DateTimeOffset
            return value.toString();
        }
        return value;
    }

    public Map<String, Object> toMap(ResultSet rs, boolean caseSensitive) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        int columnCount = rsmd.getColumnCount();
        Map<String, Object> delegate = new LinkedHashMap<>(columnCount);
        Map<String, Object> info =
                caseSensitive ? delegate : new CaseInsensitiveMap<Object>(delegate);
        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
            String columnLabel = rsmd.getColumnLabel(columnIndex);
            Object value = rs.getObject(columnIndex);
            info.put(columnLabel, value);
        }
        return info;
    }

    public <T> T toObject(ResultSet rs, Class<T> resultClass) throws SQLException {
        return ConvertUtils.convert(rs.getObject(1), resultClass);
    }

    public Cursor<Map<String, Object>> getCatalogInfos(Connection connection) throws SQLException {
        return getCatalogInfos(connection.getMetaData());
    }

    public Cursor<Map<String, Object>> getCatalogInfos(DatabaseMetaData databaseMetaData)
            throws SQLException {
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            rs = databaseMetaData.getCatalogs();
            return new MapCursor(rs, ob, false);
        } finally {
            closeLazily(ob, rs, null, null);
        }
    }

    public Cursor<Map<String, Object>> getSchemaInfos(Connection connection, String catalog)
            throws SQLException {
        return getSchemaInfos(connection.getMetaData(), catalog);
    }

    public Cursor<Map<String, Object>> getSchemaInfos(DatabaseMetaData databaseMetaData,
            String catalog) throws SQLException {
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            rs = databaseMetaData.getSchemas(catalog, "%");
            return new MapCursor(rs, ob, false);
        } finally {
            closeLazily(ob, rs, null, null);
        }
    }

    public Cursor<Map<String, Object>> getTableInfos(Connection connection, String catalog,
            String schema) throws SQLException {
        return getTableInfos(connection.getMetaData(), catalog, schema);
    }

    public Cursor<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData,
            String catalog, String schema) throws SQLException {
        return getTableInfos(databaseMetaData, catalog, schema, new String[] {"TABLE"});
    }

    public Cursor<Map<String, Object>> getTableInfos(DatabaseMetaData databaseMetaData,
            String catalog, String schema, String[] tableTypes) throws SQLException {
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            rs = databaseMetaData.getTables(catalog, schema, "%", tableTypes);
            return new MapCursor(rs, ob, false);
        } finally {
            closeLazily(ob, rs, null, null);
        }
    }

    public Cursor<Map<String, Object>> getColumnInfos(Connection connection, String catalog,
            String schema, String tableName) throws SQLException {
        return getColumnInfos(connection.getMetaData(), catalog, schema, tableName);
    }

    public Cursor<Map<String, Object>> getColumnInfos(DatabaseMetaData databaseMetaData,
            String catalog, String schema, String tableName) throws SQLException {
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            rs = databaseMetaData.getColumns(catalog, schema, tableName, null);
            return new MapCursor(rs, ob, false);
        } finally {
            closeLazily(ob, rs, null, null);
        }
    }

    public Cursor<Map<String, Object>> getPrimaryKeyInfos(Connection connection, String catalog,
            String schema, String tableName) throws SQLException {
        return getPrimaryKeyInfos(connection.getMetaData(), catalog, schema, tableName);
    }

    public Cursor<Map<String, Object>> getPrimaryKeyInfos(DatabaseMetaData databaseMetaData,
            String catalog, String schema, String tableName) throws SQLException {
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            rs = databaseMetaData.getPrimaryKeys(catalog, schema, tableName);
            return new MapCursor(rs, ob, false);
        } finally {
            closeLazily(ob, rs, null, null);
        }
    }

    public Cursor<Map<String, Object>> getImportedKeyInfos(Connection connection, String catalog,
            String schema, String tableName) throws SQLException {
        return getImportedKeyInfos(connection.getMetaData(), catalog, schema, tableName);
    }

    public Cursor<Map<String, Object>> getImportedKeyInfos(DatabaseMetaData databaseMetaData,
            String catalog, String schema, String tableName) throws SQLException {
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            rs = databaseMetaData.getImportedKeys(catalog, schema, tableName);
            return new MapCursor(rs, ob, false);
        } finally {
            closeLazily(ob, rs, null, null);
        }
    }

    public Cursor<Map<String, Object>> getIndexInfos(Connection connection, String catalog,
            String schema, String tableName) throws SQLException {
        return getIndexInfos(connection.getMetaData(), catalog, schema, tableName);
    }

    public Cursor<Map<String, Object>> getIndexInfos(DatabaseMetaData databaseMetaData,
            String catalog, String schema, String tableName) throws SQLException {
        ResultSet rs = null;
        DisposableObservable ob = new DisposableObservable();
        try {
            rs = databaseMetaData.getIndexInfo(catalog, schema, tableName, false, false);
            return new MapCursor(rs, ob, false);
        } finally {
            closeLazily(ob, rs, null, null);
        }
    }

    private static void closeLazily(Observable observable, final ResultSet rs, final Statement sm,
            final Connection connection) {
        observable.addObserver((ob, arg) -> {
            closeQuietly(rs);
            closeQuietly(sm);
            closeQuietly(connection);
        });
    }

    public void close(Connection connection) throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    public void close(ResultSet rs) throws SQLException {
        if (rs != null) {
            rs.close();
        }
    }

    public void close(Statement stmt) throws SQLException {
        if (stmt != null) {
            stmt.close();
        }
    }

    public void closeQuietly(Connection connection) {
        try {
            close(connection);
        } catch (SQLException e) {
        }
    }

    public void closeQuietly(ResultSet rs) {
        try {
            close(rs);
        } catch (SQLException e) {
        }
    }

    public void closeQuietly(Statement stmt) {
        try {
            close(stmt);
        } catch (SQLException e) {
        }
    }

    public void commit(Connection connection) throws SQLException {
        if (connection != null) {
            connection.commit();
        }
    }

    public void commitQuietly(Connection connection) {
        try {
            commit(connection);
        } catch (SQLException e) {
        }
    }

    public static void setPath(Connection connection, String catalog, String schema)
            throws SQLException {
        if (StringUtils.isNotBlank(catalog)) {
            if (!StringUtils.equals(connection.getCatalog(), catalog)) {
                connection.setCatalog(catalog);
            }
        }
        if (StringUtils.isNotBlank(schema)) {
            if (!StringUtils.equals(connection.getSchema(), schema)) {
                connection.setSchema(schema);
            }
        }
    }

    /**
     * @Description: MapCursor
     * @Author: Fred Feng
     * @Date: 24/03/2023
     * @Version 1.0.0
     */
    private static class MapCursor implements Cursor<Map<String, Object>> {

        private final ResultSet rs;
        private final DisposableObservable ob;
        private final boolean caseSensitive;
        private final AtomicBoolean opened;

        MapCursor(ResultSet rs, DisposableObservable ob, boolean caseSensitive) {
            this.rs = rs;
            this.ob = ob;
            this.caseSensitive = caseSensitive;
            this.opened = new AtomicBoolean(true);
        }

        public boolean isOpened() {
            return opened.get();
        }

        @Override
        public boolean hasNext() {
            try {
                opened.set(rs.next());
                return opened.get();
            } catch (SQLException e) {
                opened.set(false);
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                if (!isOpened()) {
                    ob.notifyObservers();
                }
            }
        }

        @Override
        public Map<String, Object> next() {
            try {
                return toMap(rs, caseSensitive);
            } catch (SQLException e) {
                opened.set(false);
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                if (!isOpened()) {
                    ob.notifyObservers();
                }
            }
        }
    }

    /**
     * @Description: ObjectCursor
     * @Author: Fred Feng
     * @Date: 24/03/2023
     * @Version 1.0.0
     */
    private static class ObjectCursor<T> implements Cursor<T> {

        private final ResultSet rs;
        private final DisposableObservable ob;
        private final Class<T> resultClass;
        private final AtomicBoolean opened;

        ObjectCursor(ResultSet rs, DisposableObservable ob, Class<T> resultClass) {
            this.rs = rs;
            this.ob = ob;
            this.resultClass = resultClass;
            this.opened = new AtomicBoolean(true);
        }

        public boolean isOpened() {
            return opened.get();
        }

        @Override
        public boolean hasNext() {
            try {
                opened.set(rs.next());
                return opened.get();
            } catch (SQLException e) {
                opened.set(false);
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                if (!isOpened()) {
                    ob.notifyObservers();
                }
            }
        }

        @Override
        public T next() {
            try {
                return toObject(rs, resultClass);
            } catch (SQLException e) {
                opened.set(false);
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                if (!isOpened()) {
                    ob.notifyObservers();
                }
            }
        }
    }

    /**
     * @Description: DisposableObservable
     * @Author: Fred Feng
     * @Date: 24/03/2023
     * @Version 1.0.0
     */
    private static class DisposableObservable extends Observable {

        @Override
        public void notifyObservers(Object arg) {
            super.setChanged();
            super.notifyObservers(arg);
            clearChanged();
            deleteObservers();
        }
    }
}
