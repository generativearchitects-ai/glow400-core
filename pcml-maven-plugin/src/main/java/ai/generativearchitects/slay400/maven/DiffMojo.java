package ai.generativearchitects.slay400.maven;

import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import java.io.File;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** {@code pcml:diff}: compares the repository with a released bundle. Writes nothing but the report file, if one is asked for. */
@Mojo(name = "diff", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class DiffMojo extends PcmlMojo {

    /** The released bundle to compare with. */
    @Parameter(property = "pcml.against", required = true)
    File against;

    @Override
    String goal() {
        return "diff";
    }

    @Override
    TaskResult run(Tasks tasks) throws MojoFailureException {
        if (against == null) {
            throw new MojoFailureException("The parameter 'against' is required by pcml:diff");
        }
        return tasks.diff(repository.toPath(), against.toPath());
    }
}
