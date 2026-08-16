package io.github.gmcnicol.taxi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipFile;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public final class GenerateMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.basedir}/src/main/taxi", required = true)
    private File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/taxi", readonly = true, required = true)
    private File outputDirectory;

    @Parameter(required = true)
    private String basePackage;

    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String pluginVersion;

    @Parameter
    private List<TaxiImport> imports = List.of();

    @Override
    public void execute() throws MojoExecutionException {
        try {
            var runtime = project.getDependencies().stream()
                    .filter(dependency -> dependency.getGroupId().equals("io.github.gmcnicol"))
                    .filter(dependency -> dependency.getArtifactId().equals("saas-kernel"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Application must depend on saas-kernel " + pluginVersion));
            if (!Objects.equals(runtime.getVersion(), pluginVersion)) {
                throw new IllegalArgumentException(
                        "saas-kernel and saas-kernel-taxi-generator versions must match: "
                                + runtime.getVersion() + " != " + pluginVersion);
            }
            var result = TaxiJavaGenerator.generate(
                    sourceDirectory.toPath(), outputDirectory.toPath(), basePackage, pluginVersion,
                    imports.stream().map(this::resolve).toList());
            result.warnings().forEach(getLog()::warn);
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
            var generatedIndex = new Resource();
            generatedIndex.setDirectory(outputDirectory.getAbsolutePath());
            generatedIndex.addInclude("META-INF/saas-kernel/**");
            project.addResource(generatedIndex);
        } catch (Exception exception) {
            throw new MojoExecutionException(exception.getMessage(), exception);
        }
    }

    private TaxiJavaGenerator.ImportedSource resolve(TaxiImport specification) {
        if (specification == null || specification.checksum == null) {
            throw new IllegalArgumentException("Taxi import requires a SHA-256 checksum");
        }
        byte[] content;
        String coordinate;
        String resource;
        if (specification.file != null && specification.coordinate == null) {
            Path base = project.getBasedir().toPath().toAbsolutePath().normalize();
            Path file = base.resolve(specification.file).normalize();
            if (!file.startsWith(base)) throw new IllegalArgumentException("Taxi import file must be inside project");
            try {
                content = Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Cannot read imported Taxi file: " + file, exception);
            }
            coordinate = "file:" + base.relativize(file).toString().replace('\\', '/');
            resource = file.getFileName().toString();
        } else if (specification.file == null && specification.coordinate != null) {
            String[] parts = specification.coordinate.split(":", -1);
            String version = parts.length == 3 ? parts[2].toUpperCase(java.util.Locale.ROOT) : "";
            if (parts.length != 3 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)
                    || version.contains("SNAPSHOT") || version.equals("LATEST") || version.equals("RELEASE")
                    || parts[2].matches(".*[\\[\\](),+].*")) {
                throw new IllegalArgumentException("Taxi import requires pinned group:artifact:version coordinate");
            }
            resource = safeResource(specification.resource);
            Artifact artifact = project.getArtifacts().stream()
                    .filter(candidate -> candidate.getGroupId().equals(parts[0])
                            && candidate.getArtifactId().equals(parts[1])
                            && candidate.getVersion().equals(parts[2]))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Taxi import must be a resolved project dependency: " + specification.coordinate));
            content = readResource(artifact.getFile().toPath(), resource);
            coordinate = specification.coordinate;
        } else {
            throw new IllegalArgumentException("Taxi import requires exactly one of file or coordinate");
        }
        String actual = sha256(content);
        if (!actual.equals(specification.checksum)) {
            throw new IllegalArgumentException("Imported Taxi checksum mismatch: " + coordinate + ":" + resource);
        }
        return new TaxiJavaGenerator.ImportedSource(
                coordinate, resource, new String(content, StandardCharsets.UTF_8), specification.checksum);
    }

    private static byte[] readResource(Path artifact, String resource) {
        try {
            if (Files.isDirectory(artifact)) {
                Path file = artifact.resolve(resource).normalize();
                if (!file.startsWith(artifact)) throw new IllegalArgumentException("Invalid Taxi import resource");
                return Files.readAllBytes(file);
            }
            try (var zip = new ZipFile(artifact.toFile())) {
                var entry = zip.getEntry(resource);
                if (entry == null || entry.isDirectory()) {
                    throw new IllegalArgumentException("Taxi import resource is missing: " + resource);
                }
                try (var input = zip.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot read imported Taxi resource: " + resource, exception);
        }
    }

    private static String safeResource(String resource) {
        if (resource == null || resource.isBlank() || resource.startsWith("/")
                || Path.of(resource).normalize().startsWith("..")) {
            throw new IllegalArgumentException("Taxi import requires a safe packaged resource path");
        }
        return resource.replace('\\', '/');
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static final class TaxiImport {
        private String coordinate;
        private String file;
        private String resource;
        private String checksum;

        public void setCoordinate(String value) { coordinate = value; }
        public void setFile(String value) { file = value; }
        public void setResource(String value) { resource = value; }
        public void setChecksum(String value) { checksum = value; }
    }
}
