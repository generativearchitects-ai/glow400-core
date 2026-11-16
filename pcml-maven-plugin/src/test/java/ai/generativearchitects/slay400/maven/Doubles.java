package ai.generativearchitects.slay400.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

/** A log that records, a project that has a folder, and a helper that records what is attached. */
final class Doubles {

    private Doubles() {
    }

    /** A log that keeps every line with its level. */
    static final class RecordingLog implements Log {
        final List<String> debug = new ArrayList<>();
        final List<String> info = new ArrayList<>();
        final List<String> warn = new ArrayList<>();
        final List<String> error = new ArrayList<>();

        @Override public boolean isDebugEnabled() { return true; }
        @Override public void debug(CharSequence message) { debug.add(String.valueOf(message)); }
        @Override public void debug(CharSequence message, Throwable error) { debug.add(String.valueOf(message)); }
        @Override public void debug(Throwable error) { debug.add(String.valueOf(error)); }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(CharSequence message) { info.add(String.valueOf(message)); }
        @Override public void info(CharSequence message, Throwable error) { info.add(String.valueOf(message)); }
        @Override public void info(Throwable error) { info.add(String.valueOf(error)); }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public void warn(CharSequence message) { warn.add(String.valueOf(message)); }
        @Override public void warn(CharSequence message, Throwable error) { warn.add(String.valueOf(message)); }
        @Override public void warn(Throwable error) { warn.add(String.valueOf(error)); }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public void error(CharSequence message) { error.add(String.valueOf(message)); }
        @Override public void error(CharSequence message, Throwable error) { this.error.add(String.valueOf(message)); }
        @Override public void error(Throwable error) { this.error.add(String.valueOf(error)); }
    }

    /** What was attached. */
    record Attached(MavenProject project, String type, String classifier, File file) {
    }

    /** A helper that only records {@code attachArtifact}. */
    static final class RecordingHelper implements MavenProjectHelper {
        final List<Attached> attached = new ArrayList<>();

        @Override
        public void attachArtifact(MavenProject project, String artifactType, String artifactClassifier, File artifactFile) {
            attached.add(new Attached(project, artifactType, artifactClassifier, artifactFile));
        }

        @Override
        public void attachArtifact(MavenProject project, String artifactType, File artifactFile) {
            attached.add(new Attached(project, artifactType, null, artifactFile));
        }

        @Override
        public void attachArtifact(MavenProject project, File artifactFile, String artifactClassifier) {
            attached.add(new Attached(project, null, artifactClassifier, artifactFile));
        }

        @Override
        public void addResource(MavenProject project, String resourceDirectory, List<String> includes, List<String> excludes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addTestResource(MavenProject project, String resourceDirectory, List<String> includes, List<String> excludes) {
            throw new UnsupportedOperationException();
        }
    }

    /** A project whose base folder is {@code dir} and whose build folder is {@code dir/target}. */
    static MavenProject project(Path dir) {
        MavenProject project = new MavenProject();
        project.setFile(dir.resolve("pom.xml").toFile());
        Build build = new Build();
        build.setDirectory(dir.resolve("target").toString());
        build.setFinalName("orders-1.0");
        project.getModel().setBuild(build);
        project.setGroupId("g");
        project.setArtifactId("orders");
        project.setVersion("1.0");
        return project;
    }
}
