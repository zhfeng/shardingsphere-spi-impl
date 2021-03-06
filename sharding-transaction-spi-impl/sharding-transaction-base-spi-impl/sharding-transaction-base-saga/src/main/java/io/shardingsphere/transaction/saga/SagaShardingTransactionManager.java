/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shardingsphere.transaction.saga;

import io.shardingsphere.transaction.saga.config.SagaConfiguration;
import io.shardingsphere.transaction.saga.config.SagaConfigurationLoader;
import io.shardingsphere.transaction.saga.resource.SagaResourceManager;
import io.shardingsphere.transaction.saga.servicecomb.transport.ShardingTransportFactory;
import lombok.SneakyThrows;

import org.apache.shardingsphere.core.constant.DatabaseType;
import org.apache.shardingsphere.core.executor.ShardingExecuteDataMap;
import org.apache.shardingsphere.transaction.core.TransactionType;
import org.apache.shardingsphere.transaction.spi.ShardingTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Sharding transaction manager for Saga.
 *
 * @author yangyi
 */
public final class SagaShardingTransactionManager implements ShardingTransactionManager {
    
    public static final String CURRENT_TRANSACTION_KEY = "current_transaction";
    
    private static final ThreadLocal<SagaTransaction> CURRENT_TRANSACTION = new ThreadLocal<>();
    
    private final SagaConfiguration sagaConfiguration;
    
    private final SagaResourceManager resourceManager;
    
    public SagaShardingTransactionManager() {
        sagaConfiguration = SagaConfigurationLoader.load();
        resourceManager = new SagaResourceManager(sagaConfiguration);
    }
    
    /**
     * Get saga transaction for current thread.
     *
     * @return saga transaction
     */
    public static SagaTransaction getCurrentTransaction() {
        return CURRENT_TRANSACTION.get();
    }
    
    @Override
    public void init(final DatabaseType databaseType, final Map<String, DataSource> dataSourceMap) {
        resourceManager.registerDataSourceMap(dataSourceMap);
    }
    
    @Override
    public TransactionType getTransactionType() {
        return TransactionType.BASE;
    }
    
    @Override
    public boolean isInTransaction() {
        return null != CURRENT_TRANSACTION.get();
    }
    
    @Override
    public Connection getConnection(final String dataSourceName) throws SQLException {
        Connection result = resourceManager.getConnection(dataSourceName);
        CURRENT_TRANSACTION.get().getConnections().putIfAbsent(dataSourceName, result);
        return result;
    }
    
    @Override
    public void begin() {
        if (null == CURRENT_TRANSACTION.get()) {
            SagaTransaction transaction = new SagaTransaction(sagaConfiguration, resourceManager.getSagaPersistence());
            ShardingExecuteDataMap.getDataMap().put(CURRENT_TRANSACTION_KEY, transaction);
            CURRENT_TRANSACTION.set(transaction);
            ShardingTransportFactory.getInstance().cacheTransport(transaction);
        }
    }
    
    @Override
    public void commit() {
        if (null != CURRENT_TRANSACTION.get() && CURRENT_TRANSACTION.get().isContainsException()) {
            submitToSagaEngine();
        }
        cleanTransaction();
    }
    
    @Override
    public void rollback() {
        if (null != CURRENT_TRANSACTION.get()) {
            submitToSagaEngine();
        }
        cleanTransaction();
    }
    
    @SneakyThrows
    private void submitToSagaEngine() {
        String json = CURRENT_TRANSACTION.get().getSagaDefinitionBuilder().build();
        resourceManager.getSagaExecutionComponent().run(json);
    }
    
    private void cleanTransaction() {
        if (null != CURRENT_TRANSACTION.get()) {
            CURRENT_TRANSACTION.get().cleanSnapshot();
        }
        ShardingTransportFactory.getInstance().remove();
        ShardingExecuteDataMap.getDataMap().remove(CURRENT_TRANSACTION_KEY);
        CURRENT_TRANSACTION.remove();
    }
    
    @Override
    public void close() {
        resourceManager.releaseDataSourceMap();
    }
}
