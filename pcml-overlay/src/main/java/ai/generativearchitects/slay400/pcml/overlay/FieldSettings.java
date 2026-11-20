package ai.generativearchitects.slay400.pcml.overlay;

import ai.generativearchitects.slay400.pcml.api.CodecSettings;
import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.Location;
import ai.generativearchitects.slay400.pcml.api.Pad;
import ai.generativearchitects.slay400.pcml.api.SemanticType;
import ai.generativearchitects.slay400.pcml.api.Trim;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The overlay settings for one program parameter (an entry under {@code parameters}) or one struct member
 * (an entry under {@code fields}), exactly as authored: nothing is defaulted yet.
 *
 * <p>{@code direction} and {@code in} are accepted for parameters only, and {@code hidden} for struct members
 * only; the loader reports the other combinations as unknown keys. {@code trim} is accepted at any depth
 * (kickoff 14, decisions 112-113): a struct member that is itself an array may carry its own {@code trim},
 * trimmed independently of any {@code trim} on an array it is nested inside.
 *
 * @param direction   {@code direction}; never {@link Direction#INJECTED}
 * @param in          {@code in}
 * @param trim        {@code trim}: how an array is shortened, for a parameter or a struct member
 * @param name        {@code name}: the exposed name
 * @param type        {@code type}: the semantic type
 * @param format      {@code format}
 * @param pad         {@code pad}
 * @param padChar     {@code padChar}
 * @param nullAs      {@code nullAs}
 * @param required    {@code required}
 * @param description {@code description}
 * @param example     {@code example}, as text
 * @param hidden      {@code hidden}
 * @param trueValue   {@code trueValue}: the text that stands for true
 * @param falseValue  {@code falseValue}: the text that stands for false
 * @param fields      settings per struct member, by PCML name, in file order
 * @param source      position of the entry's key; also what identifies the entry in diagnostics
 * @see CodecSettings
 */
public record FieldSettings(
        Optional<Located<Direction>> direction,
        Optional<Located<Location>> in,
        Optional<Located<Trim>> trim,
        Optional<Located<String>> name,
        Optional<Located<SemanticType>> type,
        Optional<Located<String>> format,
        Optional<Located<Pad>> pad,
        Optional<Located<Character>> padChar,
        Optional<Located<String>> nullAs,
        Optional<Located<Boolean>> required,
        Optional<Located<String>> description,
        Optional<Located<String>> example,
        Optional<Located<Boolean>> hidden,
        Optional<Located<String>> trueValue,
        Optional<Located<String>> falseValue,
        Map<String, FieldSettings> fields,
        SourceLocation source) {

    /** @throws NullPointerException if an argument is {@code null} */
    public FieldSettings {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(trim, "trim");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(pad, "pad");
        Objects.requireNonNull(padChar, "padChar");
        Objects.requireNonNull(nullAs, "nullAs");
        Objects.requireNonNull(required, "required");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(example, "example");
        Objects.requireNonNull(hidden, "hidden");
        Objects.requireNonNull(trueValue, "trueValue");
        Objects.requireNonNull(falseValue, "falseValue");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        Objects.requireNonNull(source, "source");
    }
}
