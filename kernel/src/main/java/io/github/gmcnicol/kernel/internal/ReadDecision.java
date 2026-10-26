package io.github.gmcnicol.kernel.internal;

/** Strict read outcome: warnings, validation failures and policy errors never become permits. */
enum ReadDecision {
    ALLOW, DENY, INVALID;
    static ReadDecision decode(long result) {
        if ((result >>> 32) != 0x4b750001L || (result & 0xf8L) != 0) throw new IllegalStateException("Invalid Cedar ABI response");
        if ((result & 0xffff_ff00L) != 0) return INVALID;
        return switch ((int) (result & 7)) {
            case 0 -> DENY;
            case 1 -> ALLOW;
            case 2, 3, 4 -> INVALID;
            default -> throw new IllegalStateException("Invalid Cedar ABI outcome");
        };
    }
}
