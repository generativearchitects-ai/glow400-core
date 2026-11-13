package ai.generativearchitects.glow400.pcml.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A value as a program call takes and returns it, typed by PCML wire type but before any byte conversion: text for char
 * and varchar, whole numbers for int, exact decimals for packed and zoned, and so on.
 */
public sealed interface WireValue {

    /** Text of a char or varchar field, with its padding. */
    record Text(String value) implements WireValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A whole number of an {@code int} field. */
    record Integer(BigInteger value) implements WireValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Integer {
            Objects.requireNonNull(value, "value");
        }
    }

    /** An exact decimal of a {@code packed} or {@code zoned} field. */
    record Decimal(BigDecimal value) implements WireValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Decimal {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A {@code float} field. */
    record Float(double value) implements WireValue {
    }

    /** The bytes of a {@code byte} field. The array is copied on the way in and on the way out. */
    record Bytes(byte[] value) implements WireValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Bytes {
            value = Objects.requireNonNull(value, "value").clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "Bytes[" + value.length + " bytes]";
        }
    }

    /** A {@code date} field. */
    record Date(LocalDate value) implements WireValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Date {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A {@code time} field. */
    record Time(LocalTime value) implements WireValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Time {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A {@code timestamp} field. */
    record Timestamp(LocalDateTime value) implements WireValue {
        /** @throws NullPointerException if {@code value} is {@code null} */
        public Timestamp {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A struct: its members in PCML order. */
    record Struct(List<WireValue> members) implements WireValue {
        /** @throws NullPointerException if the list or a member is {@code null} */
        public Struct {
            members = List.copyOf(members);
        }
    }

    /** An array: its elements in order. */
    record Array(List<WireValue> elements) implements WireValue {
        /** @throws NullPointerException if the list or an element is {@code null} */
        public Array {
            elements = List.copyOf(elements);
        }
    }

    /**
     * A value the program does not read. It stands where there is no real value to give and the type has no blank of
     * its own: a date, time or timestamp in an output or internal parameter, in a hidden struct member or in a filler
     * element of an array. The call layer decides what to send.
     */
    record Blank() implements WireValue {
    }
}
