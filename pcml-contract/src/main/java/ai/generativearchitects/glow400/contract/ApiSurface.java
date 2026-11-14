package ai.generativearchitects.glow400.contract;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The surface of an API: everything its consumers can see, and nothing else. Internal, injected and hidden fields are not
 * part of it. The OpenAPI emitter builds its document from it and the comparison of releases compares it, so the two
 * cannot disagree about what a consumer sees.
 *
 * @param name       the API name
 * @param title      the title, which is documentation
 * @param version    the version
 * @param operations the operations, in file order
 */
public record ApiSurface(String name, String title, String version, List<OperationSurface> operations) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ApiSurface {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(version, "version");
        operations = List.copyOf(operations);
    }

    /** The surface of an API. */
    public static ApiSurface of(ApiDefinition api) {
        Objects.requireNonNull(api, "api");
        List<OperationSurface> operations = new ArrayList<>();
        for (ApiOperation operation : api.operations()) {
            operations.add(operation(operation));
        }
        return new ApiSurface(api.name(), api.title(), api.version(), operations);
    }

    private static OperationSurface operation(ApiOperation operation) {
        List<FieldSurface> request = new ArrayList<>();
        List<FieldSurface> response = new ArrayList<>();
        for (FieldBinding binding : operation.bindings()) {
            if (binding.hidden()) {
                continue;
            }
            Direction direction = binding.direction();
            if ((direction == Direction.REQUEST || direction == Direction.BOTH) && binding.location().isPresent()) {
                request.add(field(binding));
            }
            if (direction == Direction.RESPONSE || direction == Direction.BOTH) {
                response.add(field(binding));
            }
        }
        Optional<ErrorMapping> mapping = operation.errorMapping();
        TreeSet<Integer> statuses = new TreeSet<>();
        mapping.ifPresent(m -> {
            statuses.add(m.httpStatus());
            statuses.addAll(m.statusByCode().values());
        });
        return new OperationSurface(operation.id(), operation.route(), operation.method(), operation.summary(), operation.description(),
                operation.tags(), request, response, mapping.map(ErrorMapping::httpStatus), List.copyOf(statuses));
    }

    private static FieldSurface field(FieldBinding binding) {
        WireType wire = binding.field().wire();
        boolean object = wire instanceof WireType.Struct;
        Count count = binding.field().count();
        boolean array = !(count instanceof Count.None);
        List<FieldSurface> children = new ArrayList<>();
        if (object) {
            for (FieldBinding child : binding.children()) {
                if (!child.hidden()) {
                    children.add(field(child));
                }
            }
        }
        Limits limits = object ? Limits.none() : limits(binding.semanticType().orElseThrow(), wire);
        if (count instanceof Count.Fixed fixed) {
            limits = limits.withMaxItems(fixed.value());
        }
        return new FieldSurface(binding.exposedName(), binding.location(), object, array,
                object ? Optional.empty() : binding.semanticType(), binding.required(), limits, children, binding.description(),
                binding.example(), new Origin(binding.fieldPath(), wire));
    }

    // ------------------------------------------------------------------ limits

    private static Limits limits(SemanticType type, WireType wire) {
        Optional<Integer> none = Optional.empty();
        return switch (type) {
            case STRING -> new Limits(fixedLength(wire), Optional.empty(), Optional.empty(), none, none, none, none, none);
            case INTEGER -> integerLimits(wire);
            case DECIMAL -> decimalLimits(wire);
            case BINARY -> {
                Optional<Integer> bytes = fixedLength(wire);
                yield new Limits(none, Optional.empty(), Optional.empty(), none, none, bytes, bytes, none);
            }
            case BOOLEAN, DATE, TIME, DATETIME -> Limits.none();
        };
    }

    private static Optional<Integer> fixedLength(WireType wire) {
        Length length = switch (wire) {
            case WireType.Char c -> c.length();
            case WireType.VarChar v -> v.length();
            case WireType.Byte b -> b.length();
            default -> null;
        };
        return length instanceof Length.Fixed fixed ? Optional.of(fixed.value()) : Optional.empty();
    }

    /** By size and sign for int, by digits for packed and zoned (signed), and non-negative by digits for text. */
    private static Limits integerLimits(WireType wire) {
        BigInteger minimum;
        Optional<BigInteger> maximum;
        switch (wire) {
            case WireType.Int i -> {
                int bits = i.lengthBytes() * 8;
                boolean unsigned = i.precision().isPresent() && i.precision().get() == bits;
                minimum = unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).negate();
                maximum = Optional.of(unsigned ? BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)
                        : BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE));
            }
            case WireType.Packed p -> {
                BigInteger largest = nines(p.length());
                minimum = largest.negate();
                maximum = Optional.of(largest);
            }
            case WireType.Zoned z -> {
                BigInteger largest = nines(z.length());
                minimum = largest.negate();
                maximum = Optional.of(largest);
            }
            default -> {
                minimum = BigInteger.ZERO;
                maximum = fixedLength(wire).map(ApiSurface::nines);
            }
        }
        return new Limits(Optional.empty(), Optional.of(minimum), maximum, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static BigInteger nines(int digits) {
        return BigInteger.TEN.pow(digits).subtract(BigInteger.ONE);
    }

    /** Digits and scale for packed and zoned; for text, its length as digits and a minimum of 0; nothing for float. */
    private static Limits decimalLimits(WireType wire) {
        Optional<Integer> none = Optional.empty();
        return switch (wire) {
            case WireType.Packed p -> new Limits(none, Optional.empty(), Optional.empty(), Optional.of(p.length()), Optional.of(p.precision()), none, none, none);
            case WireType.Zoned z -> new Limits(none, Optional.empty(), Optional.empty(), Optional.of(z.length()), Optional.of(z.precision()), none, none, none);
            case WireType.Char c -> new Limits(none, Optional.of(BigInteger.ZERO), Optional.empty(), fixedLength(wire), none, none, none, none);
            case WireType.VarChar v -> new Limits(none, Optional.of(BigInteger.ZERO), Optional.empty(), fixedLength(wire), none, none, none, none);
            default -> Limits.none();
        };
    }
}
