/**
 * Copyright 2016 Hortonworks.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.hortonworks.registries.storage.impl.jdbc.provider.phoenix.query;

import com.hortonworks.registries.storage.impl.jdbc.config.ExecutionConfig;
import com.hortonworks.registries.storage.impl.jdbc.connection.ConnectionBuilder;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.query.AbstractSqlQuery;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.statement.PreparedStatementBuilder;
import com.hortonworks.registries.storage.impl.jdbc.provider.sql.statement.StorageDataTypeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Query to get next sequence id in phoenix for a given name space.
 */
public class PhoenixSequenceIdQuery {

    private static final Logger log = LoggerFactory.getLogger(PhoenixSequenceIdQuery.class);
    private static final String ID = "id";
    private static final String VALUE = "value";
    private static final String SEQUENCE_TABLE = "sequence_table";
    private final String namespace;
    private final StorageDataTypeContext phoenixDataTypeContext;
    private final ConnectionBuilder connectionBuilder;
    private final int queryTimeoutSecs;

    public PhoenixSequenceIdQuery(String namespace, ConnectionBuilder connectionBuilder, int queryTimeoutSecs, StorageDataTypeContext storageDataTypeContext) {
        this.namespace = namespace;
        this.connectionBuilder = connectionBuilder;
        this.queryTimeoutSecs = queryTimeoutSecs;
        this.phoenixDataTypeContext = storageDataTypeContext;
    }

    public Long getNextID() {
        // this is kind of work around as there is no direct support in phoenix to get next sequence-id without using any tables,
        // it involves 3 roundtrips to phoenix/hbase (inefficient but there is a limitation from phoenix!).
        // SEQUENCE can be used for such columns in UPSERT queries directly but to get a simple sequence-id involves all this.
        // create sequence for each namespace and insert it into with a value uuid.
        // get the id for inserted uuid.
        // delete that entry from the table.
        long nextId = 0;
        UUID uuid = UUID.randomUUID();
        PhoenixSqlQuery updateQuery = new PhoenixSqlQuery("UPSERT INTO " + SEQUENCE_TABLE + "(\""+ID+"\", \"" + namespace + "\") VALUES('" + uuid + "', NEXT VALUE FOR " + namespace + "_sequence)");
        PhoenixSqlQuery selectQuery = new PhoenixSqlQuery("SELECT \"" + namespace + "\" FROM " + SEQUENCE_TABLE + " WHERE \"" + ID + "\"='" + uuid + "'");
        PhoenixSqlQuery deleteQuery = new PhoenixSqlQuery("DELETE FROM " + SEQUENCE_TABLE + " WHERE \"id\"='" + uuid + "'");

        try (Connection connection = connectionBuilder.getConnection()) {
            int upsertResult = PreparedStatementBuilder.of(connection, new ExecutionConfig(queryTimeoutSecs), phoenixDataTypeContext, updateQuery).getPreparedStatement(updateQuery).executeUpdate();
            log.debug("Query [{}] is executed and returns result with [{}]", updateQuery, upsertResult);

            ResultSet selectResultSet = PreparedStatementBuilder.of(connection, new ExecutionConfig(queryTimeoutSecs), phoenixDataTypeContext, selectQuery).getPreparedStatement(selectQuery).executeQuery();
            if (selectResultSet.next()) {
                nextId = selectResultSet.getLong(namespace);
            } else {
                throw new RuntimeException("No sequence-id created for the current sequence of [" + namespace + "]");
            }
            log.debug("Generated sequence id [{}] for [{}]", nextId, namespace);
            int deleteResult = PreparedStatementBuilder.of(connection, new ExecutionConfig(queryTimeoutSecs), phoenixDataTypeContext, deleteQuery).getPreparedStatement(deleteQuery).executeUpdate();
            if (deleteResult == 0) {
                log.error("Could not delete entry in " + SEQUENCE_TABLE + " for value [{}]", namespace, uuid);
            } else {
                log.debug("Deleted entry with id [{}] and value [{}] successfully",uuid, nextId);
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

        return nextId;
    }

    static class PhoenixSqlQuery extends AbstractSqlQuery {

        public PhoenixSqlQuery(String sql) {
            this.sql = sql;
        }

        @Override
        protected void initParameterizedSql() {
        }
    }
}
