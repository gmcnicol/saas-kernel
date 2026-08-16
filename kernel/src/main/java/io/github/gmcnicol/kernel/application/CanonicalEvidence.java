package io.github.gmcnicol.kernel.application;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exact versioned canonical bytes and checksum for one durable semantic value. */
public final class CanonicalEvidence {
    private final String qualifiedType;
    private final int contractVersion;
    private final int formatVersion;
    private final byte[] canonicalUtf8;
    private final String checksum;

    public CanonicalEvidence(
            String qualifiedType,
            int contractVersion,
            int formatVersion,
            byte[] canonicalUtf8,
            String checksum) {
        if (qualifiedType == null || qualifiedType.isBlank() || contractVersion < 1 || formatVersion < 1) {
            throw new IllegalArgumentException("Semantic evidence requires type and positive versions");
        }
        this.qualifiedType = qualifiedType;
        this.contractVersion = contractVersion;
        this.formatVersion = formatVersion;
        this.canonicalUtf8 = Objects.requireNonNull(canonicalUtf8, "canonicalUtf8").clone();
        this.checksum = Objects.requireNonNull(checksum, "checksum");
    }

    public String qualifiedType() {
        return qualifiedType;
    }

    public int contractVersion() {
        return contractVersion;
    }

    public int formatVersion() {
        return formatVersion;
    }

    public byte[] canonicalUtf8() {
        return canonicalUtf8.clone();
    }

    public String canonicalJson() {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(canonicalUtf8))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("Semantic evidence is not valid UTF-8", exception);
        }
    }

    public String checksum() {
        return checksum;
    }
}
