package ai.generativearchitects.glow400.pcml.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A value as the HTTP side reads and writes it, before any JSON is involved: what a client sends in a request and
 * receives in a response.
 */
public sealed interface ApiValue {

    /** Text ({@code STRING}). */
    record Text(String value) implements ApiValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A whole number of any size ({@code INTEGER}). */
    record Integer(BigInteger value) implements ApiValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Integer {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * An exact decimal number ({@code DECIMAL}).
     *
     * <p>In JSON a decimal is text, so that no caller loses precision (decision 56). Whoever maps JSON to
     * {@code ApiValue} reads a decimal from a JSON string only: a JSON number for a decimal field is {@code VAL002}.
     * On output a decimal is written in plain notation, with exactly the field's scale for {@code packed} and
     * {@code zoned} fields and as decoded for fields held as text. Whole numbers ({@code INTEGER}) remain JSON numbers.
     */
    record Decimal(BigDecimal value) implements ApiValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Decimal {
            Objects.requireNonNull(value, "value");
        }
    }

    /** True or false ({@code BOOLEAN}). */
    record Bool(boolean value) implements ApiValue {
    }

    /** A calendar date ({@code DATE}). */
    record Date(LocalDate value) implements ApiValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Date {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A time of day ({@code TIME}). */
    record Time(LocalTime value) implements ApiValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Time {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A date and a time of day, without a time zone ({@code DATETIME}). */
    record DateTime(LocalDateTime value) implements ApiValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public DateTime {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Bytes ({@code BINARY}). The array is copied on the way in and on the way out. */
    record Binary(byte[] value) implements ApiValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Binary {
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Binary that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "Binary[" + value.length + " bytes]";
        }
    }

    /** A struct: members by exposed name, in order. */
    record Obj(Map<String, ApiValue> members) implements ApiValue {
        /** @throws NullPointerException if the map, a key or a value is {@code null} */
        public Obj {
            Map<String, ApiValue> copy = new LinkedHashMap<>();
            members.forEach((name, value) -> copy.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
            members = Collections.unmodifiableMap(copy);
        }
    }

    /** An array: its elements in order. */
    record Arr(List<ApiValue> elements) implements ApiValue {
        /** @throws NullPointerException if the list or an element is {@code null} */
        public Arr {
            elements = List.copyOf(elements);
        }
    }

    /** No value: a missing field, or the "no date" a program returns as blank. */
    record Absent() implements ApiValue {
    }
}
