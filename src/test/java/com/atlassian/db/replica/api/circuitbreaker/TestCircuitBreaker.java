package com.atlassian.db.replica.api.circuitbreaker;

import com.atlassian.db.replica.api.DualConnection;
import com.atlassian.db.replica.api.mocks.ConnectionProviderMock;
import com.atlassian.db.replica.api.mocks.PermanentConsistency;
import com.atlassian.db.replica.impl.circuitbreaker.BreakOnNotSupportedOperations;
import org.junit.After;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static com.atlassian.db.replica.api.Queries.SIMPLE_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verify;

public class TestCircuitBreaker {

    @After
    public void after() {
        BreakOnNotSupportedOperations.reset();
    }

    @Test
    public void shouldServeOnlyMasterConnectionAfterUnimplementedMethodCall() throws SQLException {
        final Connection connection = DualConnection.builder(new ConnectionProviderMock(), new PermanentConsistency()).build();
        Throwable thrown = catchThrowable(() -> connection.prepareStatement(SIMPLE_QUERY).isCloseOnCompletion());
        final ConnectionProviderMock connectionProvider = new ConnectionProviderMock();
        final Connection newConnection = DualConnection.builder(connectionProvider, new PermanentConsistency()).build();

        newConnection.prepareStatement(SIMPLE_QUERY).isCloseOnCompletion();

        verify(connectionProvider.getPreparedStatements().get(0)).isCloseOnCompletion();
        assertThat(thrown).isInstanceOf(DualConnectionException.class);
    }
}
