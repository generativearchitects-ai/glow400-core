package ai.generativearchitects.glow400.maven;

import ai.generativearchitects.glow400.tasks.TaskResult;
import ai.generativearchitects.glow400.tasks.Tasks;
import java.io.File;
import java.io.IOException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** {@code pcml:generate}: writes the files of the emitters named in {@code gateway.yaml}. Nothing is written if there is an error. */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class GenerateMojo extends PcmlMojo {

    /** Where the emitted files go. */
    @Parameter(property = "pcml.outputDirectory", defaultValue = "${project.build.directory}/pcml")
    File outputDirectory;

    @Override
    String goal() {
        return "generate";
    }

    @Override
    TaskResult run(Tasks tasks) throws IOException {
        return tasks.generate(repository.toPath(), outputDirectory.toPath());
    }
}
