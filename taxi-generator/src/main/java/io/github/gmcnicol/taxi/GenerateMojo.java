package io.github.gmcnicol.taxi;

import java.io.File;
import java.util.Objects;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
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
            var result = TaxiJavaGenerator.generate(sourceDirectory.toPath(), outputDirectory.toPath(), basePackage);
            result.warnings().forEach(getLog()::warn);
            project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
        } catch (Exception exception) {
            throw new MojoExecutionException(exception.getMessage(), exception);
        }
    }
}
