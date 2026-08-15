package io.github.gmcnicol.kernel.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import io.github.gmcnicol.kernel.application.SemanticPackVersion;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

public abstract class CurrentExecutionBasisTest {

    @MockitoSpyBean
    private CedarAuthoriser cedar;

    @MockitoSpyBean
    private SemanticPackVersion semanticPack;

    protected final void changeCurrentSemanticPack() {
        doReturn("0".repeat(64)).when(semanticPack).checksum();
    }

    protected final void restoreCurrentSemanticPack() {
        reset(semanticPack);
    }

    protected final void revokeCurrentAuthorisation() {
        doReturn(false).when(cedar).allows(any(), any(), anyString());
    }

    @AfterEach
    void restoreCurrentExecutionBasis() {
        reset(cedar, semanticPack);
    }
}
