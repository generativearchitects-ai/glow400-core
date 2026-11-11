package ai.generativearchitects.glow400.pcml.api;

import java.util.List;
import java.util.Objects;

/**
 * The API model of a customer repository: its defaults and every operation that could be built.
 *
 * @param defaults   repository-wide defaults from {@code gateway.yaml}
 * @param operations the operations, in repository order (folders sorted by name, then by position in the file)
 */
public record ApiRepositoryModel(GatewayDefaults defaults, List<ApiOperation> operations) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ApiRepositoryModel {
        Objects.requireNonNull(defaults, "defaults");
        operations = List.copyOf(operations);
    }
}
