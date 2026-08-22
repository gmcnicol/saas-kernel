package io.github.gmcnicol.kernel.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class W3cTraceContextTests {

    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void validatesTracestateStructure() {
        assertThatCode(() -> new W3cTraceContext(TRACEPARENT, "tenant@vendor=value,other=opaque value"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new W3cTraceContext(TRACEPARENT, "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new W3cTraceContext(TRACEPARENT, "vendor=one,vendor=two"))
                .isInstanceOf(IllegalArgumentException.class);
        String tooMany = IntStream.range(0, 33)
                .mapToObj(index -> "vendor" + index + "=value")
                .collect(Collectors.joining(","));
        assertThatThrownBy(() -> new W3cTraceContext(TRACEPARENT, tooMany))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
