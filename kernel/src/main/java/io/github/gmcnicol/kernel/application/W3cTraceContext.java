package io.github.gmcnicol.kernel.application;

import java.util.regex.Pattern;

public record W3cTraceContext(String traceparent, String tracestate) {

    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    public W3cTraceContext {
        var match = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (match == null
                || !match.matches()
                || match.group(1).equals("00000000000000000000000000000000")
                || match.group(2).equals("0000000000000000")
                || (tracestate != null && (tracestate.length() > 512
                        || tracestate.chars().anyMatch(character -> character < 0x20 || character > 0x7e)))) {
            throw new IllegalArgumentException("Invalid W3C trace context");
        }
    }
}
