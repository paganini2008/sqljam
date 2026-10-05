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
package com.github.sqljam.jdbc.page;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;
import com.github.sqljam.impexp.Dialect;
import com.github.sqljam.impexp.PooledConnectionFactory;
import com.github.sqljam.jdbc.ConnectionFactory;
import com.github.sqljam.jdbc.JdbcUtils;
import com.github.sqljam.page.DefaultPageContent;
import com.github.sqljam.page.PageContent;
import com.github.sqljam.page.PageReader;
import com.github.sqljam.utils.CaseInsensitiveMap;

/**
 * @Description: MapBasedPageReader reads pages of rows by the pagination of the dialect
 * @Author: Fred Feng
 * @Date: 24/03/2023
 * @Version 1.0.0
 */
public class MapBasedPageReader implements PageReader<Map<String, Object>> {

    public MapBasedPageReader(DataSource dataSource, String sql, long maxTotalRecords) {
        this(new PooledConnectionFactory(dataSource), sql, maxTotalRecords);
    }

    public MapBasedPageReader(ConnectionFactory connectionFactory, String sql, long maxTotalRecords) {
        this(connectionFactory, sql, new Object[0], maxTotalRecords);
    }

    public MapBasedPageReader(DataSource dataSource, String sql, Object[] args, long maxTotalRecords) {
        this(new PooledConnectionFactory(dataSource), sql, args, maxTotalRecords);
    }

    public MapBasedPageReader(ConnectionFactory connectionFactory, String sql, Object[] args, long maxTotalRecords) {
        this(connectionFactory, sql, args, maxTotalRecords, null, null);
    }

    /**
     * @param dialect dialect of the database being read, used to build pagination statement
     * @param orderBy order by columns to keep pagination stable, may be null
     */
    public MapBasedPageReader(ConnectionFactory connectionFactory, String sql, Object[] args, long maxTotalRecords,
                              Dialect dialect, String orderBy) {
        this.connectionFactory = connectionFactory;
        this.sql = sql;
        this.args = args;
        this.maxTotalRecords = maxTotalRecords;
        this.dialect = dialect;
        this.orderBy = orderBy;
    }

    private final ConnectionFactory connectionFactory;
    private final String sql;
    private final Object[] args;
    private final long maxTotalRecords;
    private final Dialect dialect;
    private final String orderBy;
    private Long rowCount;

    @Override
    public PageContent<Map<String, Object>> list(int pageNumber, int offset, int limit,
                                                 Object nextToken) throws SQLException {
        Connection connection = null;
        try {
            connection = connectionFactory.getConnection();
            String boundSql = dialect != null ? dialect.getPageStatement(sql, orderBy, limit, offset)
                    : String.format("%s limit %d offset %d", sql, limit, offset);
            return new DefaultPageContent<>(fetchAll(connection, boundSql, args), null);
        } finally {
            connectionFactory.close(connection);
        }
    }

    private static List<Map<String, Object>> fetchAll(Connection connection, String sql, Object[] args)
            throws SQLException {
        List<Map<String, Object>> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            JdbcUtils.setValues(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData rsmd = rs.getMetaData();
                int columnCount = rsmd.getColumnCount();
                int[] columnTypes = new int[columnCount];
                String[] columnTypeNames = new String[columnCount];
                for (int i = 0; i < columnCount; i++) {
                    columnTypes[i] = rsmd.getColumnType(i + 1);
                    columnTypeNames[i] = rsmd.getColumnTypeName(i + 1);
                }
                while (rs.next()) {
                    Map<String, Object> row = new CaseInsensitiveMap<>(new LinkedHashMap<>(columnCount));
                    for (int i = 1; i <= columnCount; i++) {
                        String columnLabel = rsmd.getColumnLabel(i);
                        // Skip row number column of pagination
                        if ("rn__".equalsIgnoreCase(columnLabel)) {
                            continue;
                        }
                        row.put(columnLabel, JdbcUtils.getColumnValue(rs, i, columnTypes[i - 1], columnTypeNames[i - 1]));
                    }
                    list.add(row);
                }
            }
        }
        return list;
    }

    @Override
    public long rowCount() throws SQLException {
        if (maxTotalRecords > 0) {
            return Long.min(Integer.MAX_VALUE, maxTotalRecords);
        }
        if (rowCount == null) {
            Connection connection = null;
            try {
                connection = connectionFactory.getConnection();
                String countSql = String.format("SELECT COUNT(1) FROM (%s) T", sql);
                rowCount = JdbcUtils.fetchOne(connection, countSql, args, Long.class);
            } finally {
                connectionFactory.close(connection);
            }
        }
        return rowCount;
    }
}
