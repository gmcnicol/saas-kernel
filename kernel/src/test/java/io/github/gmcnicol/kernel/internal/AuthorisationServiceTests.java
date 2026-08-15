package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.gmcnicol.kernel.application.AuthorisationDeniedException;
import io.github.gmcnicol.kernel.application.Principal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class AuthorisationServiceTests {

    @Test
    void deniesWhenTheApplicationAuthorisationAdapterFails() {
        var jdbc = mock(JdbcTemplate.class);
        var transactions = mock(TransactionOperations.class);
        var cedar = mock(CedarAuthoriser.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(String.class), any()))
                .thenReturn("tenant-one");
        doThrow(new IllegalStateException("adapter failed")).when(cedar).fields();

        var service = new AuthorisationService(
                jdbc, transactions, cedar, mock(EvaluationStore.class), mock(TaxiPayloadValidator.class),
                mock(KernelTelemetry.class));

        assertThatThrownBy(() -> service.authorise(
                "tenant-one", UUID.randomUUID(), new Principal("Owner", "gareth"), Instant.EPOCH))
                .isInstanceOf(AuthorisationDeniedException.class)
                .hasMessage("Authorisation denied");
    }
}
