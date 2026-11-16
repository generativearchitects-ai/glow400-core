package ai.generativearchitects.glow400.maven;

import ai.generativearchitects.glow400.tasks.BundleRequest;
import ai.generativearchitects.glow400.tasks.TaskResult;
import ai.generativearchitects.glow400.tasks.Tasks;
import java.io.File;
import javax.inject.Inject;
import java.util.Optional;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;

/** {@code pcml:bundle}: writes the bundle and attaches it to the project with the type {@code rpgapi}. */
@Mojo(name = "bundle", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class BundleMojo extends PcmlMojo {

    /** The type of the attached artifact. */
    static final String TYPE = "rpgapi";

    /** The name of the bundle in its manifest. */
    @Parameter(property = "pcml.bundleName", defaultValue = "${project.artifactId}")
    String bundleName;

    /** The version of the bundle in its manifest. */
    @Parameter(property = "pcml.bundleVersion", defaultValue = "${project.version}")
    String bundleVersion;

    /** The bundle file. */
    @Parameter(property = "pcml.bundleFile", defaultValue = "${project.build.directory}/${project.build.finalName}.rpgapi")
    File bundleFile;

    /** Whether the bundle is attached to the project. */
    @Parameter(property = "pcml.attach", defaultValue = "true")
    boolean attach = true;

    /** Where the repository comes from; recorded in the manifest when given. */
    @Parameter(property = "pcml.sourceRepository")
    String sourceRepository;

    /** The revision of the repository; recorded in the manifest when given. */
    @Parameter(property = "pcml.sourceRevision")
    String sourceRevision;

    private final MavenProjectHelper projectHelper;

    /** @param projectHelper how an artifact is attached to the project; Maven injects it */
    @Inject
    public BundleMojo(MavenProjectHelper projectHelper) {
        this.projectHelper = projectHelper;
    }

    @Override
    String goal() {
        return "bundle";
    }

    @Override
    TaskResult run(Tasks tasks) {
        BundleRequest request = new BundleRequest(bundleName, bundleVersion, bundleFile.toPath(), Optional.ofNullable(sourceRepository),
                Optional.ofNullable(sourceRevision));
        return tasks.bundle(repository.toPath(), request);
    }

    @Override
    void succeeded(TaskResult result) throws MojoExecutionException {
        if (attach && !result.written().isEmpty()) {
            projectHelper.attachArtifact(project, TYPE, bundleFile);
            getLog().info("pcml:bundle: attached " + bundleFile.getName() + " with type " + TYPE);
        }
    }
}
