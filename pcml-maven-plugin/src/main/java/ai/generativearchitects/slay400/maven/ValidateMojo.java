package ai.generativearchitects.slay400.maven;

import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** {@code pcml:validate}: loads and validates the repository. Writes nothing but the report file, if one is asked for. */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true)
public class ValidateMojo extends PcmlMojo {

    @Override
    String goal() {
        return "validate";
    }

    @Override
    TaskResult run(Tasks tasks) {
        return tasks.validate(repository.toPath());
    }
}
