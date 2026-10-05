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
package com.github.sqljam.impexp;

import java.sql.SQLException;

/**
 * @Description: MetaDataVisitor visits the metadata tree and collects statements
 * @Author: Fred Feng
 * @Date: 25/03/2023
 * @Version 1.0.0
 */
public interface MetaDataVisitor {

    void visit(ServerMetaData metaData) throws SQLException;

    void visit(CatalogMetaData metaData) throws SQLException;

    void visit(SchemaMetaData metaData) throws SQLException;

    void visit(TableMetaData metaData) throws SQLException;

    void visit(ColumnMetaData metaData) throws SQLException;

    void visit(PrimaryKeyMetaData metaData) throws SQLException;
    
    void visit(CommentMetaData metaData) throws SQLException;

    void visit(IndexMetaData metaData) throws SQLException;

    void visit(CombinedIndexMetaData metaData) throws SQLException;

    void visit(PartitionMetaData metaData) throws SQLException;

    void visit(PartitionTableMetaData metaData) throws SQLException;
    
    void visit(PartitionExpressionMetaData metaData) throws SQLException;

    void visit(ForeignKeyMetaData metaData) throws SQLException;

    void visit(SequenceMetaData metaData) throws SQLException;

    Exporter.ExportConfiguration getConfiguration();

    DdlScripter getDdlScripter();
}