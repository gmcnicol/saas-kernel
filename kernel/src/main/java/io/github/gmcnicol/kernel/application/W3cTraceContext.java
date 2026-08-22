package io.github.gmcnicol.kernel.application;

import java.util.HashSet;
import java.util.regex.Pattern;

public record W3cTraceContext(String traceparent, String tracestate) {

    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");
    private static final Pattern TRACESTATE_KEY = Pattern.compile(
            "(?:[a-z][a-z0-9_\\-*/]{0,255}|[a-z0-9][a-z0-9_\\-*/]{0,240}@[a-z][a-z0-9_\\-*/]{0,13})");
    private static final Pattern TRACESTATE_VALUE = Pattern.compile(
            "[\\x20-\\x2b\\x2d-\\x3c\\x3e-\\x7e]{0,255}[\\x21-\\x2b\\x2d-\\x3c\\x3e-\\x7e]");

    public W3cTraceContext {
        var match = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (match == null
                || !match.matches()
                || match.group(1).equals("00000000000000000000000000000000")
                || match.group(2).equals("0000000000000000")
                || !validTracestate(tracestate)) {
            throw new IllegalArgumentException("Invalid W3C trace context");
        }
    }

    private static boolean validTracestate(String tracestate) {
        if (tracestate == null) {
            return true;
        }
        String[] members = tracestate.split(",", -1);
        if (tracestate.length() > 512 || members.length > 32) {
            return false;
        }
        var keys = new HashSet<String>();
        for (String raw : members) {
            String member = stripOptionalWhitespace(raw);
            if (member.isEmpty()) {
                continue;
            }
            int separator = member.indexOf('=');
            String key = separator < 0 ? "" : member.substring(0, separator);
            if (separator < 1
                    || !TRACESTATE_KEY.matcher(key).matches()
                    || !TRACESTATE_VALUE.matcher(member.substring(separator + 1)).matches()
                    || !keys.add(key)) {
                return false;
            }
        }
        return true;
    }

    private static String stripOptionalWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }
}
