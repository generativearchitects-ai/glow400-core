package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The spellings the overlay format uses for enumerated values, and the patterns of its identifiers. */
final class Vocabulary {

    static final Map<String, Direction> DIRECTION = YamlReader.choices(
            List.of("request", "response", "both", "internal"),
            List.of(Direction.REQUEST, Direction.RESPONSE, Direction.BOTH, Direction.INTERNAL));
    static final Map<String, Location> IN = YamlReader.choices(
            List.of("path", "query", "header", "body"),
            List.of(Location.PATH, Location.QUERY, Location.HEADER, Location.BODY));
    static final Map<String, SemanticType> TYPE = YamlReader.choices(
            List.of("string", "integer", "decimal", "boolean", "date", "time", "datetime", "binary"),
            List.of(SemanticType.STRING, SemanticType.INTEGER, SemanticType.DECIMAL, SemanticType.BOOLEAN,
                    SemanticType.DATE, SemanticType.TIME, SemanticType.DATETIME, SemanticType.BINARY));
    static final Map<String, Pad> PAD = YamlReader.choices(
            List.of("left", "right"), List.of(Pad.LEFT, Pad.RIGHT));
    static final Map<String, CallStrategy> CALL = YamlReader.choices(
            List.of("auto", "pcml", "raw"), List.of(CallStrategy.AUTO, CallStrategy.PCML, CallStrategy.RAW));
    static final Map<String, HttpMethod> METHOD = YamlReader.choices(
            List.of("GET", "POST", "PUT", "PATCH", "DELETE"),
            List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE));
    static final Map<String, InputOutputPolicy> INPUT_OUTPUT = YamlReader.choices(
            List.of("both", "fail"), List.of(InputOutputPolicy.BOTH, InputOutputPolicy.FAIL));

    /** Semantic version 2.0.0. */
    static final Pattern SEMVER = Pattern.compile(
            "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?");

    /** An operation id: letters, digits, dot, dash and underscore, starting with a letter or digit. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** A {@code ${...}} expression whose root is {@code environment} or {@code tenant}. */
    static final Pattern EXPRESSION = Pattern.compile(
            "\\$\\{(environment|tenant)\\.[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*}");

    private Vocabulary() {
    }
}
