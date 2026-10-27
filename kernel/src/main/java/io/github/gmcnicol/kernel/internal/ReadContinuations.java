package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CollectionReadException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Process-local authenticated opaque metadata. Restart invalidates tokens; no authority/payload cache. */
final class ReadContinuations {
    private final SecureRandom random = new SecureRandom();
    private final byte[] key = new byte[32];
    ReadContinuations() { random.nextBytes(key); }

    String issue(byte[] binding, long offset) {
        try {
            var buffer = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(buffer)) {
                out.writeLong(System.currentTimeMillis() + 300_000);
                out.writeLong(offset);
                out.write(binding);
            }
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(buffer.toByteArray());
            var token = new ByteArrayOutputStream(); token.write(nonce); token.write(encrypted);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray());
        } catch (Exception failure) { throw stale(); }
    }

    long resume(String token, byte[] binding) {
        try {
            if (token.length() > 256) throw stale();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length != 76) throw stale();
            byte[] plain = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(bytes, 12)).doFinal(Arrays.copyOfRange(bytes, 12, bytes.length));
            try (var in = new DataInputStream(new ByteArrayInputStream(plain))) {
                long expires = in.readLong(), offset = in.readLong();
                if (expires <= System.currentTimeMillis() || offset < 1 || offset > 1_000_000_000L
                        || !MessageDigest.isEqual(in.readNBytes(32), binding)) throw stale();
                return offset;
            }
        } catch (Exception failure) { throw stale(); }
    }

    private Cipher cipher(int mode, byte[] nonce) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("kernel-read-continuation-v1".getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    static byte[] digest(List<String> values) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return hash.digest();
        } catch (Exception failure) { throw stale(); }
    }
    private static CollectionReadException stale() { return new CollectionReadException(CollectionReadException.Reason.STALE); }
}
