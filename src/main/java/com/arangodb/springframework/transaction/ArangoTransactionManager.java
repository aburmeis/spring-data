/*
 * DISCLAIMER
 *
 * Copyright 2017 ArangoDB GmbH, Cologne, Germany
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
 *
 * Copyright holder is ArangoDB GmbH, Cologne, Germany
 */

package com.arangodb.springframework.transaction;

import com.arangodb.ArangoDBException;
import com.arangodb.ArangoDatabase;
import com.arangodb.DbName;
import com.arangodb.springframework.core.ArangoOperations;
import com.arangodb.springframework.repository.query.QueryTransactionBridge;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.lang.Nullable;
import org.springframework.transaction.*;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Function;

/**
 * Transaction manager using ArangoDB stream transactions on the
 * {@linkplain ArangoOperations#getDatabaseName() current database} of the
 * template. A {@linkplain ArangoTransactionObject transaction object} using
 * a shared {@linkplain ArangoTransactionHolder holder} is used for the
 * {@link DefaultTransactionStatus}. Neither
 * {@linkplain TransactionDefinition#getPropagationBehavior() propagation}
 * {@linkplain TransactionDefinition#PROPAGATION_NESTED nested} nor
 * {@linkplain TransactionDefinition#getIsolationLevel() isolation}
 * {@linkplain TransactionDefinition#ISOLATION_SERIALIZABLE serializable} are
 * supported.
 */
public class ArangoTransactionManager extends AbstractPlatformTransactionManager implements InitializingBean {

    private final ArangoOperations operations;
    private final QueryTransactionBridge bridge;

    public ArangoTransactionManager(ArangoOperations operations, QueryTransactionBridge bridge) {
        this.operations = operations;
        this.bridge = bridge;
        super.setGlobalRollbackOnParticipationFailure(true);
        super.setTransactionSynchronization(SYNCHRONIZATION_ON_ACTUAL_TRANSACTION);
    }

    /**
     * Check for supported property settings.
     */
    @Override
    public void afterPropertiesSet() {
        if (isNestedTransactionAllowed()) {
            throw new IllegalStateException("Nested transactions must not be allowed");
        }
        if (!isGlobalRollbackOnParticipationFailure()) {
            throw new IllegalStateException("Global rollback on participating failure is needed");
        }
        if (getTransactionSynchronization() == SYNCHRONIZATION_NEVER) {
            throw new IllegalStateException("Transaction synchronization is needed always");
        }
    }

    /**
     * Creates a new transaction object. Any holder bound will be reused.
     */
    @Override
    protected ArangoTransactionObject doGetTransaction() {
        DbName database = operations.getDatabaseName();
        ArangoTransactionHolder holder = (ArangoTransactionHolder) TransactionSynchronizationManager.getResource(database);
        try {
            return new ArangoTransactionObject(operations.driver().db(database), getDefaultTimeout(), holder);
        } catch (ArangoDBException error) {
            throw new TransactionSystemException("Cannot create transaction object", error);
        }
    }

    /**
     * Connect the new transaction object to the query bridge.
     *
     * @see QueryTransactionBridge#setCurrentTransaction(Function)
     * @see #prepareSynchronization(DefaultTransactionStatus, TransactionDefinition)
     * @throws InvalidIsolationLevelException for {@link TransactionDefinition#ISOLATION_SERIALIZABLE}
     */
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) throws InvalidIsolationLevelException {
        int isolationLevel = definition.getIsolationLevel();
        if (isolationLevel != TransactionDefinition.ISOLATION_DEFAULT && (isolationLevel & TransactionDefinition.ISOLATION_SERIALIZABLE) != 0) {
            throw new InvalidIsolationLevelException("ArangoDB does not support isolation level serializable");
        }
        ArangoTransactionObject tx = (ArangoTransactionObject) transaction;
        bridge.setCurrentTransaction(collections -> {
            try {
                return tx.getOrBegin(collections).getStreamTransactionId();
            } catch (ArangoDBException error) {
                throw new TransactionSystemException("Cannot begin transaction", error);
            }
        });
    }

    /**
     * Commit the current stream transaction. The query bridge is cleared
     * afterwards.
     *
     * @see ArangoDatabase#commitStreamTransaction(String)
     * @see QueryTransactionBridge#clearCurrentTransaction()
     */
    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        ArangoTransactionObject tx = (ArangoTransactionObject) status.getTransaction();
        if (logger.isDebugEnabled()) {
            logger.debug("Commit stream transaction " + tx);
        }
        try {
            tx.commit();
        } catch (ArangoDBException error) {
            throw new TransactionSystemException("Cannot commit transaction " + tx, error);
        } finally {
            bridge.clearCurrentTransaction();
        }
    }

    /**
     * Roll back the current stream transaction. The query bridge is cleared
     * afterwards.
     *
     * @see ArangoDatabase#abortStreamTransaction(String)
     * @see QueryTransactionBridge#clearCurrentTransaction()
     */
    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        ArangoTransactionObject tx = (ArangoTransactionObject) status.getTransaction();
        if (logger.isDebugEnabled()) {
            logger.debug("Rollback stream transaction " + tx);
        }
        try {
            tx.rollback();
        } catch (ArangoDBException error) {
            throw new TransactionSystemException("Cannot roll back transaction " + tx, error);
        } finally {
            bridge.clearCurrentTransaction();
        }
    }

    /**
     * Check if the transaction object has the bound holder. For new
     * transactions the holder will be bound afterwards.
     */
    @Override
    protected boolean isExistingTransaction(Object transaction) throws TransactionException {
        ArangoTransactionHolder holder = ((ArangoTransactionObject) transaction).getHolder();
        return holder == TransactionSynchronizationManager.getResource(operations.getDatabaseName());
    }

    /**
     * Mark the transaction as global rollback only.
     *
     * @see #isGlobalRollbackOnParticipationFailure()
     */
    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
        ArangoTransactionObject tx = (ArangoTransactionObject) status.getTransaction();
        tx.getHolder().setRollbackOnly();
    }

    /**
     * Any transaction object is configured according to the definition upfront.
     *
     * @see ArangoTransactionObject#configure(TransactionDefinition)
     */
    @Override
    protected DefaultTransactionStatus newTransactionStatus(TransactionDefinition definition, @Nullable Object transaction, boolean newTransaction, boolean newSynchronization, boolean debug, @Nullable Object suspendedResources) {
        if (transaction instanceof ArangoTransactionObject) {
            ((ArangoTransactionObject) transaction).configure(definition);
        }
        return super.newTransactionStatus(definition, transaction, newTransaction, newSynchronization, debug, suspendedResources);
    }

    /**
     * Bind the holder for the first new transaction created.
     *
     * @see ArangoTransactionHolder
     */
    @Override
    protected void prepareSynchronization(DefaultTransactionStatus status, TransactionDefinition definition) {
        super.prepareSynchronization(status, definition);
        if (status.isNewSynchronization()) {
            ArangoTransactionHolder holder = ((ArangoTransactionObject) status.getTransaction()).getHolder();
            TransactionSynchronizationManager.bindResource(operations.getDatabaseName(), holder);
        }
    }

    /**
     * Unbind the holder from the last transaction completed.
     *
     * @see ArangoTransactionHolder
     */
    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        TransactionSynchronizationManager.unbindResource(operations.getDatabaseName());
    }
}
