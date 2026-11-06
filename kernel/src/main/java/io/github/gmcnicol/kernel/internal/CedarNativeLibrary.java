package io.github.gmcnicol.kernel.internal;

import java.lang.foreign.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Supported fast-path ABI: Linux x86_64 glibc >= 2.36. Other SDK platforms stay explicit SDK. */
final class CedarNativeLibrary {
    private static final String RESOURCE = "/META-INF/saas-kernel/native/linux-x86_64/libkernel_cedar.so";
    private static boolean loaded;
    private CedarNativeLibrary() {}
    static boolean supported() {
        if (!System.getProperty("os.name").equals("Linux")
                || !java.util.Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))) return false;
        var linker = Linker.nativeLinker();
        var symbol = linker.defaultLookup().find("gnu_get_libc_version");
        if (symbol.isEmpty()) return false;
        try {
            var call = linker.downcallHandle(symbol.get(), FunctionDescriptor.of(ValueLayout.ADDRESS));
            MemorySegment value = (MemorySegment) call.invokeExact();
            String[] parts = value.reinterpret(64).getString(0).split("\\.");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
            return major > 2 || major == 2 && minor >= 36;
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            throw new IllegalStateException("Cannot determine Cedar native ABI support");
        }
    }
    static synchronized void load() {
        if (loaded) return;
        if (!supported()) throw new IllegalStateException("Prepared Cedar ABI is unsupported");
        Path directory = null;
        try (var binary = CedarNativeLibrary.class.getResourceAsStream(RESOURCE);
             var checksum = CedarNativeLibrary.class.getResourceAsStream(RESOURCE + ".sha256")) {
            if (binary == null || checksum == null) throw new IllegalStateException("Packaged Cedar native resource missing");
            String expected = new String(checksum.readNBytes(65), java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (!expected.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid packaged Cedar checksum");
            directory = Files.createTempDirectory("kernel-cedar-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path path = directory.resolve("libkernel_cedar.so");
            byte[] bytes = binary.readNBytes(50 * 1024 * 1024 + 1);
            if (bytes.length > 50 * 1024 * 1024) throw new IllegalStateException("Packaged Cedar binary exceeds bound");
            verify(bytes, expected);
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            System.load(path.toAbsolutePath().toString());
            if (!NativeCedar.identity().equals("kernel-cedar/1;cedar/09e21d967f1da2cf2abbe1e0b0b2067b41867735+patch.sha256:81eabc5b63e0c44a1f0ef937672f05f4cb07c57845a92dcc2dfa050f71592634")) throw new IllegalStateException("Packaged Cedar ABI mismatch");
            loaded = true;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot load packaged Cedar native resource"); }
        finally {
            if (directory != null) {
                // Linux keeps the loaded inode mapped; no persistent executable/temp payload is retained.
                try { Files.deleteIfExists(directory.resolve("libkernel_cedar.so")); Files.deleteIfExists(directory); }
                catch (java.io.IOException ignored) { directory.toFile().deleteOnExit(); }
            }
        }
    }
    static void verify(byte[] bytes, String expected) {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII), expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IllegalStateException("Packaged Cedar native integrity check failed");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
