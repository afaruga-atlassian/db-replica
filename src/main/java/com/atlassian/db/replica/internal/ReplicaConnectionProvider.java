package com.atlassian.db.replica.internal;

import com.atlassian.db.replica.spi.ConnectionProvider;
import com.atlassian.db.replica.spi.ReplicaConsistency;
import io.atlassian.util.concurrent.ResettableLazyReference;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

public class ReplicaConnectionProvider implements AutoCloseable {
    private final ReplicaConsistency consistency;
    private final ConnectionProvider connectionProvider;
    private final Set<Connection> initializedConnections = new HashSet<>();
    private Boolean isAutoCommit;
    private Integer transactionIsolation;
    private Boolean isReadOnly;
    private String catalog;
    private Boolean isClosed = false;
    private final ResettableLazyReference<Connection> readConnection = new ResettableLazyReference<Connection>() {
        @Override
        protected Connection create() {
            if (connectionProvider.isReplicaAvailable()) {
                return connectionProvider.getReplicaConnection();
            } else {
                return writeConnection.get();
            }
        }
    };

    private final ResettableLazyReference<Connection> writeConnection = new ResettableLazyReference<Connection>() {
        @Override
        protected Connection create() {
            return connectionProvider.getMainConnection();
        }
    };

    public ReplicaConnectionProvider(
        ConnectionProvider connectionProvider,
        ReplicaConsistency consistency
    ) {
        this.connectionProvider = connectionProvider;
        this.consistency = consistency;
    }

    private void initialize(Connection connection) throws SQLException {
        if (!initializedConnections.contains(connection)) {
            if (isAutoCommit != null) {
                connection.setAutoCommit(isAutoCommit);
            }
            if (transactionIsolation != null) {
                connection.setTransactionIsolation(transactionIsolation);
            }
            if (isReadOnly != null) {
                connection.setReadOnly(isReadOnly);
            }
            if (catalog != null) {
                connection.setCatalog(catalog);
            }
            initializedConnections.add(connection);
        }
    }

    public void setTransactionIsolation(Integer transactionIsolation) {
        this.transactionIsolation = transactionIsolation;
        initializedConnections.clear();
    }

    public void setAutoCommit(Boolean autoCommit) {
        final Boolean autoCommitBefore = getAutoCommit();
        this.isAutoCommit = autoCommit;
        initializedConnections.clear();
        if (!autoCommitBefore.equals(getAutoCommit())) {
            recordCommit(autoCommitBefore);
        }
    }

    public boolean getAutoCommit() {
        return isAutoCommit == null || isAutoCommit;
    }

    public Boolean isClosed() {
        return this.isClosed;
    }

    public Boolean getReadOnly() {
        return isReadOnly;
    }

    public void setReadOnly(Boolean readOnly) {
        isReadOnly = readOnly;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    /**
     * Provides a connection that will be used for reading operation. Will use read-replica if possible.
     */
    public Connection getReadConnection() throws SQLException {
        if (transactionIsolation != null && transactionIsolation > Connection.TRANSACTION_READ_COMMITTED) {
            return getWriteConnection();
        }
        final Connection readConnection = this.readConnection.get();
        if (consistency.isConsistent(readConnection)) {
            initialize(readConnection);
            return readConnection;
        } else {
            if (writeConnection.isInitialized() && writeConnection.get().equals(readConnection)) {
                initialize(readConnection);
                return readConnection;
            }
            readConnection.close();
            this.readConnection.reset();
            return getWriteConnection();
        }
    }

    /**
     * Provides a connection that will be used for writing operation. It will always return a connection to the
     * main database.
     */
    public Connection getWriteConnection() throws SQLException {
        final Connection connection = writeConnection.get();
        if (readConnection.isInitialized() && !readConnection.get().equals(writeConnection.get())) {
            closeConnection(readConnection);
        }
        initialize(connection);
        return connection;
    }

    public boolean hasWriteConnection() {
        return writeConnection.isInitialized();
    }

    public void rollback() throws SQLException {
        if (writeConnection.isInitialized()) {
            writeConnection.get().rollback();
        }
        if (readConnection.isInitialized()) {
            if (writeConnection.isInitialized() && readConnection.get().equals(writeConnection.get())) {
                return;
            }
            readConnection.get().rollback();
        }
    }

    public void commit() throws SQLException {
        if (writeConnection.isInitialized()) {
            writeConnection.get().commit();
            recordCommit(isAutoCommit);
        }
        if (readConnection.isInitialized()) {
            if (writeConnection.isInitialized() && readConnection.get().equals(writeConnection.get())) {
                return;
            }
            readConnection.get().commit();
        }
    }

    private void recordCommit(Boolean autoCommit) {
        if (writeConnection.isInitialized() && autoCommit != null && !autoCommit) {
            consistency.write(writeConnection.get());
        }
    }

    @Override
    public void close() throws SQLException {
        Exception lastException = null;
        isClosed = true;
        if (readConnection.isInitialized()) {
            final boolean isWriteAndReadTheSameConnection = writeConnection.isInitialized() && readConnection.get().equals(
                writeConnection.get());
            try {
                closeConnection(readConnection);
            } catch (Exception e) {
                lastException = e;
            }
            if (isWriteAndReadTheSameConnection) {
                return;
            }
        }
        if (writeConnection.isInitialized()) {
            try {
                closeConnection(writeConnection);
            } catch (Exception e) {
                lastException = e;
            }
        }
        if (lastException != null) {
            throw new SQLException(lastException);
        }
    }

    private void closeConnection(ResettableLazyReference<Connection> connectionReference) throws SQLException {
        try {
            connectionReference.get().close();
        } finally {
            connectionReference.reset();
        }
    }
}
