package ai.generativearchitects.slay400.pcml.scaffold;

import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.overlay.OverlayText;
import java.util.Objects;
import java.util.Optional;

/**
 * One change to the entry of one top-level parameter: the keys that are present are set (or replaced); the others are
 * left as they are.
 *
 * @param operationId the id of the operation, as the loader derives it
 * @param parameter   the parameter's name in the PCML
 * @param direction   {@code direction}; never {@link Direction#INJECTED}
 * @param type        {@code type}
 * @param name        {@code name}: the exposed name, which must satisfy {@link OverlayText#isValidExposedName}
 */
public record ParameterEdit(String operationId, String parameter, Optional<Direction> direction,
                            Optional<SemanticType> type, Optional<String> name) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if nothing is set, the direction is {@code injected}, or the name is not valid
     */
    public ParameterEdit {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        if (direction.isEmpty() && type.isEmpty() && name.isEmpty()) {
            throw new IllegalArgumentException("An edit must set at least one of direction, type and name");
        }
        if (direction.isPresent() && direction.get() == Direction.INJECTED) {
            throw new IllegalArgumentException("A direction written in a parameter entry is never injected");
        }
        if (name.isPresent() && !OverlayText.isValidExposedName(name.get())) {
            throw new IllegalArgumentException("Not a valid exposed name");
        }
    }
}
