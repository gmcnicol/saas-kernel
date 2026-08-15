package io.github.gmcnicol.kernel.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import io.github.gmcnicol.kernel.application.RetryableIntentException;
import io.github.gmcnicol.kernel.semanticpack.FactDerivation;
import io.github.gmcnicol.kernel.semanticpack.IntentHandler;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

public abstract class CurrentExecutionBasisTest {

    @MockitoSpyBean
    private CedarAuthoriser cedar;

    @MockitoSpyBean
    private SemanticPackVersion semanticPack;

    @MockitoSpyBean("recordInteractionHandler")
    private IntentHandler recordInteractionHandler;

    @MockitoSpyBean("followUpDueDerivation")
    private FactDerivation followUpDueDerivation;

    @MockitoSpyBean
    private Clock clock;

    protected final void changeCurrentSemanticPack() {
        doReturn("0".repeat(64)).when(semanticPack).checksum();
    }

    protected final void restoreCurrentSemanticPack() {
        reset(semanticPack);
    }

    protected final void revokeCurrentAuthorisation() {
        doReturn(false).when(cedar).allows(any(), any(), anyString());
    }

    protected final void failRecordInteractionTransiently() {
        org.mockito.Mockito.doThrow(new RetryableIntentException("temporary outage"))
                .when(recordInteractionHandler).handle(any(), any(), any());
    }

    protected final void failRecordInteractionDeterministically() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("invalid deterministic work"))
                .when(recordInteractionHandler).handle(any(), any(), any());
    }

    protected final void crashRecordInteractionHandler() {
        org.mockito.Mockito.doThrow(new AssertionError("simulated process crash"))
                .when(recordInteractionHandler).handle(any(), any(), any());
    }

    protected final void restoreRecordInteractionHandler() {
        reset(recordInteractionHandler);
    }

    protected final void expireReevaluationLeaseDuringDerivation(Instant claimedAt) {
        doReturn(claimedAt).when(clock).instant();
        org.mockito.Mockito.doAnswer(invocation -> {
            doReturn(claimedAt.plusSeconds(31)).when(clock).instant();
            return invocation.callRealMethod();
        }).when(followUpDueDerivation).derive(any(), any());
    }

    protected final void restoreDerivationAt(Instant currentTime) {
        reset(followUpDueDerivation, clock);
        doReturn(currentTime).when(clock).instant();
    }

    @AfterEach
    void restoreCurrentExecutionBasis() {
        reset(cedar, semanticPack, recordInteractionHandler, followUpDueDerivation, clock);
    }
}
