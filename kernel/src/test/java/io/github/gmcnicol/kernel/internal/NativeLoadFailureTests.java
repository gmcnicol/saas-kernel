package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.condition.EnabledIf("preparedRuntimeSupported")
class NativeLoadFailureTests {
    static boolean preparedRuntimeSupported() { return CedarNativeLibrary.supported(); }
    @TempDir Path resources;
    @Test void supportedNativeIntegrityFailureNeverFallsBackEvenInAutoMode() throws Exception {
        String entry = "META-INF/saas-kernel/native/linux-x86_64/libkernel_cedar.so";
        Path binary = resources.resolve(entry);
        Files.createDirectories(binary.getParent()); Files.write(binary, new byte[] {1, 2, 3});
        try (var checksum = CedarNativeLibrary.class.getResourceAsStream("/" + entry + ".sha256")) {
            Files.copy(checksum, resources.resolve(entry + ".sha256"));
        }
        for (String mode : new String[] {"auto", "prepared"}) {
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-cp", resources + java.io.File.pathSeparator + System.getProperty("java.class.path"),
                    NativeLoadProbe.class.getName(), mode).redirectErrorStream(true);
            builder.environment().clear();
            Process process = builder.start();
            try {
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.exitValue()).isNotZero();
                assertThat(output).contains("Packaged Cedar native integrity check failed").doesNotContain("ALLOW");
            } finally { process.destroyForcibly(); process.waitFor(); }
        }
    }
}
