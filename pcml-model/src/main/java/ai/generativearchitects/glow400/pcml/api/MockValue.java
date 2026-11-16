package ai.generativearchitects.glow400.pcml.api;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;

/**
 * A value in a mock file, as it is written there: the value a client would send or receive for the field, in the semantic
 * type of the field. Decimals, dates, times and binary values are text. Every value keeps the position it was read from;
 * the position is not part of equality, so that a definition read from {@code apis/<FOLDER>/mock/<id>.yaml} equals the
 * same definition read back from {@code mock/<api>.json}.
 */
public sealed interface MockValue {

    /** Where the value is written. */
    SourceLocation source();

    /** Text: a string, a decimal, a date, a time, a date-time or Base64. */
    record Text(String value, SourceLocation source) implements MockValue {
        /** @throws NullPointerException if an argument is {@code null} */
        public Text {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Text that && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash("text", value);
        }
    }

    /** A whole number. */
    record Whole(BigInteger value, SourceLocation source) implements MockValue {
        /** @throws NullPointerException if an argument is {@code null} */
        public Whole {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Whole that && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash("whole", value);
        }
    }

    /**
     * A number with a fraction or an exponent, as written. No field takes one (a decimal is written as text, decision 62):
     * it is kept so that validation can say so.
     */
    record Real(String literal, SourceLocation source) implements MockValue {
        /** @throws NullPointerException if an argument is {@code null} */
        public Real {
            Objects.requireNonNull(literal, "literal");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Real that && literal.equals(that.literal);
        }

        @Override
        public int hashCode() {
            return Objects.hash("real", literal);
        }
    }

    /** {@code true} or {@code false}. */
    record Bool(boolean value, SourceLocation source) implements MockValue {
        /** @throws NullPointerException if {@code source} is {@code null} */
        public Bool {
            Objects.requireNonNull(source, "source");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bool that && value == that.value;
        }

        @Override
        public int hashCode() {
            return Objects.hash("bool", value);
        }
    }

    /** A struct: its members by PCML name, in the order written. */
    record Struct(Map<String, MockValue> members, SourceLocation source) implements MockValue {
        /** @throws NullPointerException if an argument, a name or a member is {@code null} */
        public Struct {
            Map<String, MockValue> copy = new LinkedHashMap<>();
            members.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
            members = Collections.unmodifiableMap(copy);
            Objects.requireNonNull(source, "source");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Struct that && members.equals(that.members);
        }

        @Override
        public int hashCode() {
            return Objects.hash("struct", members);
        }
    }

    /** An array: its elements in order. */
    record Array(List<MockValue> elements, SourceLocation source) implements MockValue {
        /** @throws NullPointerException if an argument or an element is {@code null} */
        public Array {
            elements = List.copyOf(elements);
            Objects.requireNonNull(source, "source");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Array that && elements.equals(that.elements);
        }

        @Override
        public int hashCode() {
            return Objects.hash("array", elements);
        }
    }
}
