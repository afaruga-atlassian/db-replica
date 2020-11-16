package com.atlassian.db.replica.internal;

import com.atlassian.db.replica.*;
import com.atlassian.db.replica.spi.*;
import io.atlassian.util.concurrent.*;

import java.sql.*;
import java.util.*;
import java.util.stream.*;

public class ReplicaStatement implements Statement {
    private final ReplicaConnectionProvider connectionProvider;
    private final Integer resultSetType;
    private final Integer resultSetConcurrency;
    private final Integer resultSetHoldability;
    private Statement currentStatement;
    private final List<Operation> operations = new ArrayList<>();
    private final ReplicaConsistency consistency;
    private final LazyReference<Statement> readStatement = new LazyReference<Statement>() {
        @Override
        protected Statement create() throws Exception {
            return createStatement(connectionProvider.getReadConnection());
        }
    };

    private final LazyReference<Statement> writeStatement = new LazyReference<Statement>() {
        @Override
        protected Statement create() throws Exception {
            return createStatement(connectionProvider.getWriteConnection());
        }
    };

    public ReplicaStatement(
        ReplicaConsistency consistency,
        ReplicaConnectionProvider connectionProvider,
        Integer resultSetType,
        Integer resultSetConcurrency,
        Integer resultSetHoldability
    ) {
        this.consistency = consistency;
        this.connectionProvider = connectionProvider;
        this.resultSetType = resultSetType;
        this.resultSetConcurrency = resultSetConcurrency;
        this.resultSetHoldability = resultSetHoldability;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        final Statement statement = getReadStatement(sql);
        return SimpleMetricCollector.measure(isReadOnly(), () -> statement.executeQuery(sql));
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        final Statement statement = getWriteStatement();
        final Integer returnValue = SimpleMetricCollector.measure(isReadOnly(), () -> statement.executeUpdate(sql));
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public void close() throws SQLException {
        for (final Statement statement : allStatements()) {
            statement.close();
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return getWriteStatement().getMaxFieldSize();
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        getWriteStatement().setMaxFieldSize(max); //TODO: should I set it for both write and read statements?
        // I guess it's a bug. It probably should be postponed and run
        // only when we know which statement to use
    }

    @Override
    public int getMaxRows() throws SQLException {
        return getWriteStatement().getMaxRows();
    }

    @Override
    public void setMaxRows(int max) {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void setEscapeProcessing(boolean enable) {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public int getQueryTimeout() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void setQueryTimeout(int seconds) {
        addOperation(
            new Operation<Statement, Integer>(
                (statement, args) -> {
                    try {
                        statement.setQueryTimeout(args.getValue());
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                },
                new Args<>(1, seconds) //hah a wrong abstraction! (TODO: fix it)
            )
        );
    }

    @Override
    public void cancel() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public SQLWarning getWarnings() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void clearWarnings() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void setCursorName(String name) {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        final Statement statement = getWriteStatement();
        final Boolean returnValue = SimpleMetricCollector.measure(isReadOnly(), () -> statement.execute(sql));
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        if (getCurrentStatement() == null) {
            throw new ReadReplicaUnsupportedOperationException();
        }
        return getCurrentStatement().getResultSet();
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return getCurrentStatement().getUpdateCount();
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return getCurrentStatement().getMoreResults();
    }

    @Override
    public void setFetchDirection(int direction) {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public int getFetchDirection() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void setFetchSize(int rows) {
        addOperation(
            new Operation<Statement, Integer>(
                (statement, args) -> {
                    try {
                        statement.setFetchSize(args.getValue());
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                },
                new Args<>(1, rows) //hah a wrong abstraction! (TODO: fix it)
            )
        );
    }

    @Override
    public int getFetchSize() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public int getResultSetConcurrency() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public int getResultSetType() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void addBatch(String sql) {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void clearBatch() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        final Statement statement = getWriteStatement();
        final int[] returnValue = SimpleMetricCollector.measure(isReadOnly(), statement::executeBatch);
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connectionProvider.getWriteConnection();
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return currentStatement.getMoreResults(current);
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return currentStatement.getGeneratedKeys();
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        final Statement statement = getWriteStatement();
        final Integer returnValue = SimpleMetricCollector.measure(
            isReadOnly(),
            () -> statement.executeUpdate(sql, autoGeneratedKeys)
        );
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        final Statement statement = getWriteStatement();
        final Integer returnValue = SimpleMetricCollector.measure(
            isReadOnly(),
            () -> statement.executeUpdate(sql, columnIndexes)
        );
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        final Statement statement = getWriteStatement();
        final Integer returnValue = SimpleMetricCollector.measure(
            isReadOnly(),
            () -> statement.executeUpdate(sql, columnNames)
        );
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        final Statement statement = getWriteStatement();
        final Boolean returnValue = SimpleMetricCollector.measure(
            isReadOnly(),
            () -> statement.execute(sql, autoGeneratedKeys)
        );
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        final Statement state = getWriteStatement();
        final Boolean returnValue = SimpleMetricCollector.measure(
            isReadOnly(),
            () -> state.execute(sql, columnIndexes)
        );
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        final Statement statement = getWriteStatement();
        final Boolean returnValue = SimpleMetricCollector.measure(
            isReadOnly(),
            () -> statement.execute(sql, columnNames)
        );
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return currentStatement.getResultSetHoldability();
    }

    @Override
    public boolean isClosed() throws SQLException {
        return currentStatement.isClosed();
    }

    @Override
    public void setPoolable(boolean poolable) {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public boolean isPoolable() throws SQLException {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public void closeOnCompletion() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public boolean isCloseOnCompletion() {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (isWrapperFor(iface)) {
            //noinspection unchecked
            return (T) this;
        }
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        return currentStatement.getLargeUpdateCount();
    }

    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        throw new ReadReplicaUnsupportedOperationException();
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        final Statement statement = getWriteStatement();
        final long[] returnValue = SimpleMetricCollector.measure(isReadOnly(), statement::executeLargeBatch);
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        final Statement statement = getWriteStatement();
        final long returnValue = SimpleMetricCollector.measure(isReadOnly(), () -> statement.executeLargeUpdate(sql));
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public long executeLargeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        final Statement statement = getWriteStatement();
        final long returnValue = SimpleMetricCollector.measure(isReadOnly(), () -> statement.executeLargeUpdate(sql, autoGeneratedKeys));
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public long executeLargeUpdate(String sql, int[] columnIndexes) throws SQLException {
        final Statement statement = getWriteStatement();
        final long returnValue = SimpleMetricCollector.measure(isReadOnly(), () -> statement.executeLargeUpdate(sql, columnIndexes));
        logMainLogSequenceNumber();
        return returnValue;
    }

    @Override
    public long executeLargeUpdate(String sql, String[] columnNames) throws SQLException {
        final Statement statement = getWriteStatement();
        final long returnValue = SimpleMetricCollector.measure(isReadOnly(), () -> statement.executeLargeUpdate(sql, columnNames));
        logMainLogSequenceNumber();
        return returnValue;
    }

    public void performOperations() {
        for (Operation<Statement, Void> operation : operations) {
            try {
                operation.run(getCurrentStatement());
            } catch (Exception e) {
                throw new ReadReplicaUnsupportedOperationException();
            }
        }
        operations.clear();
    }

    public Statement getCurrentStatement() {
        return this.currentStatement;
    }

    public void setCurrentStatement(Statement statement) {
        this.currentStatement = statement;
    }

    public void addOperation(Operation operation) {
        operations.add(operation);
    }

    public void clearOperations() {
        operations.clear();
    }

    public static Builder builder(ReplicaConnectionProvider connectionProvider, ReplicaConsistency consistency) {
        return new Builder(connectionProvider, consistency);
    }

    void logMainLogSequenceNumber() throws SQLException {
        consistency.write(currentStatement.getConnection());
    }

    public Statement getReadStatement(String sql) {
        // if write connection is already initialized, but the current statement is null
        // we should use a write statement, regardless of the fact that readStatement has been called.
        if (connectionProvider.hasWriteConnection() || isSelectForUpdate(sql) || isFunctionCall(sql)) {
            return getWriteStatement();
        }
        setCurrentStatement(getCurrentStatement() != null ? getCurrentStatement() : readStatement.get());
        performOperations();
        return getCurrentStatement();
    }

    private boolean isSelectForUpdate(String sql) {
        return sql != null && (sql.endsWith("for update") || sql.endsWith("FOR UPDATE"));
    }

    private boolean isFunctionCall(String sql) {
        if (sql == null){
            return false;
        }
        return sql.split("WHERE")[0].contains("(");
    }

    public Statement getWriteStatement() {
        setCurrentStatement(writeStatement.get());
        performOperations();
        return getCurrentStatement();
    }

    public Statement createStatement(Connection connection) throws SQLException {
        if (resultSetType == null) {
            return connection.createStatement();
        } else if (resultSetHoldability == null) {
            return connection.createStatement(resultSetType, resultSetConcurrency);
        } else {
            return connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }
    }

    public boolean isReadOnly() {
        return !(writeStatement.isInitialized() && getCurrentStatement().equals(writeStatement.get()));
    }

    private Collection<Statement> allStatements() {
        return Stream.of(readStatement, writeStatement)
            .filter(LazyReference::isInitialized)
            .map(LazyReference::get)
            .collect(Collectors.toList());
    }

    public static class Builder {
        private final ReplicaConnectionProvider connectionProvider;
        private final ReplicaConsistency consistency;
        private Integer resultSetType;
        private Integer resultSetConcurrency;
        private Integer resultSetHoldability;

        private Builder(
            ReplicaConnectionProvider connectionProvider,
            ReplicaConsistency consistency
        ) {
            this.connectionProvider = connectionProvider;
            this.consistency = consistency;
        }

        public Builder resultSetType(int resultSetType) {
            this.resultSetType = resultSetType;
            return this;
        }

        public Builder resultSetConcurrency(int resultSetConcurrency) {
            this.resultSetConcurrency = resultSetConcurrency;
            return this;
        }

        public Builder resultSetHoldability(int resultSetHoldability) {
            this.resultSetHoldability = resultSetHoldability;
            return this;
        }

        public ReplicaStatement build() {
            return new ReplicaStatement(
                consistency,
                connectionProvider,
                resultSetType,
                resultSetConcurrency,
                resultSetHoldability
            );
        }
    }
}
