package io.github.gmcnicol.kernel.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.gmcnicol.kernel.application.ApplicationVersion;
import io.github.gmcnicol.kernel.application.CanonicalCodec;
import io.github.gmcnicol.kernel.application.Intent;
import io.github.gmcnicol.kernel.application.IntentStatus;
import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntentWorkerTests {

    @Test
    void routesPollingThroughKernelSoGeneratedAndLegacyIntentsRun() {
        var kernel = mock(DefaultKernel.class);
        var policy = new IntentWorkerProperties();
        var clock = mock(Clock.class);
        var now = Instant.parse("2040-08-15T09:00:00Z");
        when(clock.instant()).thenReturn(now);

        new IntentWorker(kernel, policy, clock).poll();

        verify(kernel).processDue(org.mockito.ArgumentMatchers.eq(now), any());
    }

    @Test
    void alternatesGeneratedAndLegacyQueuesUnderSustainedBacklog() {
        var typed = mock(TypedActionService.class);
        var legacy = mock(IntentExecutionService.class);
        var worker = new IntentWorkerProperties();
        var at = Instant.parse("2040-08-15T09:00:00Z");
        var typedIntent = new Intent(UUID.randomUUID(), UUID.randomUUID(), IntentStatus.SUCCEEDED, at);
        var legacyIntent = new Intent(UUID.randomUUID(), UUID.randomUUID(), IntentStatus.SUCCEEDED, at);
        when(typed.processNext(at)).thenReturn(Optional.of(typedIntent));
        when(legacy.processNext(at)).thenReturn(Optional.of(legacyIntent));
        var kernel = new DefaultKernel(
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(org.springframework.transaction.support.TransactionOperations.class),
                mock(AuthorisationService.class), mock(IntentService.class), legacy, typed,
                mock(IntentQueryService.class), worker, Clock.systemUTC(), new ApplicationVersion("test", "1"),
                "1", new SemanticPackVersion("test", "checksum"), List.of(), List.of(), List.of(), List.of(),
                List.of(), CanonicalCodec.Limits.defaults(), mock(KernelTelemetry.class));

        org.assertj.core.api.Assertions.assertThat(List.of(
                kernel.processNext(at).orElseThrow(),
                kernel.processNext(at).orElseThrow(),
                kernel.processNext(at).orElseThrow()))
                .containsExactly(typedIntent, legacyIntent, typedIntent);
    }
}
