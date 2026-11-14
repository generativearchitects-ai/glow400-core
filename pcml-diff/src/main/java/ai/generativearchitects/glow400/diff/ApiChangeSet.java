package ai.generativearchitects.glow400.diff;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The changes of one API that exists in both repositories.
 *
 * @param api              the API name
 * @param releasedVersion  its released version
 * @param currentVersion   its current version
 * @param impact           the highest impact of its changes; {@link Impact#NONE} when there are none
 * @param changes          the changes, in the order of the operations
 */
public record ApiChangeSet(String api, Optional<String> releasedVersion, Optional<String> currentVersion, Impact impact, List<Change> changes) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ApiChangeSet {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(releasedVersion, "releasedVersion");
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(impact, "impact");
        changes = List.copyOf(changes);
    }
}
