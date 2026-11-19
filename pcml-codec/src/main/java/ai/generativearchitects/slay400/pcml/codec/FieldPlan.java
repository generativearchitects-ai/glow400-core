package ai.generativearchitects.slay400.pcml.codec;

import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.api.Trim;
import ai.generativearchitects.slay400.pcml.model.Count;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** How one field, with its members and elements, is encoded and decoded: a binding made ready for use. */
final class FieldPlan {

    /** The fields next to one another, with their returned values while decoding, and the top level for absolute references. */
    record Scope(List<FieldPlan> plans, List<WireValue> values, Scope outermost, String program) {
        /** The top level, which is the scope itself when it has no outer one. */
        Scope top() {
            return outermost == null ? this : outermost;
        }
    }

    record Found(FieldPlan plan, WireValue value) {
    }

    final FieldBinding binding;
    final FieldDef field;
    /** The codec of a scalar; {@code null} for a struct. */
    final ScalarCodec scalar;
    final List<FieldPlan> children;

    private FieldPlan(FieldBinding binding, ScalarCodec scalar, List<FieldPlan> children) {
        this.binding = binding;
        this.field = binding.field();
        this.scalar = scalar;
        this.children = children;
    }

    /** Builds the plan of a binding and of everything under it; configuration problems are added to {@code issues}. */
    static FieldPlan build(FieldBinding binding, List<ConfigurationIssue> issues) {
        FieldDef field = binding.field();
        boolean requestSide = binding.direction() == Direction.REQUEST || binding.direction() == Direction.BOTH;
        if (requestSide && field.count() instanceof Count.Ref) {
            issues.add(new ConfigurationIssue(binding.fieldPath(), "an array whose count is given by another field is not supported yet on the request side"));
        }
        if (field.wire() instanceof WireType.Struct) {
            if (binding.codec().trueValue().isPresent() || binding.codec().falseValue().isPresent()) {
                issues.add(new ConfigurationIssue(binding.fieldPath(), "trueValue and falseValue apply only to boolean fields"));
            }
            List<FieldPlan> children = new ArrayList<>();
            for (FieldBinding child : binding.children()) {
                children.add(build(child, issues));
            }
            return new FieldPlan(binding, null, children);
        }
        List<String> own = new ArrayList<>();
        ScalarCodec codec = ScalarCodecs.create(binding, own);
        own.forEach(message -> issues.add(new ConfigurationIssue(binding.fieldPath(), message)));
        return new FieldPlan(binding, codec, List.of());
    }

    boolean isStruct() {
        return scalar == null;
    }

    boolean isArray() {
        return !(field.count() instanceof Count.None);
    }

    String exposedName() {
        return binding.exposedName();
    }

    Where where(Where parent) {
        return parent.member(field.name(), binding.exposedName());
    }

    // ------------------------------------------------------------------ blanks

    /**
     * The value for a field nobody gave a value for: the whole array, struct or scalar, blank. Every element of a
     * fixed array is the same blank value, so they share one instance instead of each being built separately
     * (kickoff 14, Part D): cheap even for the largest declared arrays, and still the full declared size, which a
     * request needs and which trimming (Part B) then shortens on the way out.
     */
    WireValue blankValue() {
        if (isArray()) {
            if (field.count() instanceof Count.Fixed fixed) {
                return new WireValue.Array(Collections.nCopies(fixed.value(), blankElement()));
            }
            return new WireValue.Array(List.of());
        }
        return blankElement();
    }

    /** The blank of one element: a struct of blanks, or a blank scalar. */
    WireValue blankElementValue() {
        return blankElement();
    }

    private WireValue blankElement() {
        if (isStruct()) {
            List<WireValue> members = new ArrayList<>();
            children.forEach(child -> members.add(child.blankValue()));
            return new WireValue.Struct(members);
        }
        return scalar.blank();
    }

    // ------------------------------------------------------------------ encoding

    /** Encodes a request field. A problem is reported and a blank returned in its place. */
    WireValue encodeRequest(ApiValue value, Where where, Problems problems) {
        return isArray() ? encodeArray(value, where, problems) : encodeElement(value, where, problems, true);
    }

    private WireValue encodeArray(ApiValue value, Where where, Problems problems) {
        int size = ((Count.Fixed) field.count()).value();
        if (value instanceof ApiValue.Absent) {
            if (binding.required()) {
                problems.add("VAL001", where, "A required field is missing");
                return blankValue();
            }
            return blankValue();
        }
        if (!(value instanceof ApiValue.Arr arr)) {
            problems.add("VAL002", where, "A list was expected");
            return blankValue();
        }
        if (arr.elements().size() > size) {
            problems.add("VAL005", where, "The list has more elements than the field holds: at most " + size);
            return blankValue();
        }
        List<WireValue> elements = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            elements.add(i < arr.elements().size()
                    ? encodeElement(arr.elements().get(i), where.element(i), problems, false)
                    : blankElement());
        }
        return new WireValue.Array(elements);
    }

    /** One value, or one element of an array. {@code enforceRequired} is false for array elements: a missing one is a blank. */
    private WireValue encodeElement(ApiValue value, Where where, Problems problems, boolean enforceRequired) {
        if (isStruct()) {
            return encodeStruct(value, where, problems, enforceRequired);
        }
        if (value instanceof ApiValue.Absent) {
            if (!enforceRequired) {
                return scalar.blank();
            }
            if (binding.required()) {
                problems.add("VAL001", where, "A required field is missing");
                return scalar.blank();
            }
            Optional<WireValue> absent = scalar.absent();
            if (absent.isEmpty()) {
                problems.add("VAL001", where, "A value is required: this type has no blank");
                return scalar.blank();
            }
            return absent.get();
        }
        if (value instanceof ApiValue.Obj || value instanceof ApiValue.Arr) {
            problems.add("VAL002", where, "A single value was expected");
            return scalar.blank();
        }
        return scalar.encode(value, where, problems).orElseGet(scalar::blank);
    }

    private WireValue encodeStruct(ApiValue value, Where where, Problems problems, boolean enforceRequired) {
        if (value instanceof ApiValue.Absent) {
            if (enforceRequired && binding.required()) {
                problems.add("VAL001", where, "A required field is missing");
            }
            return blankElement();
        }
        if (!(value instanceof ApiValue.Obj obj)) {
            problems.add("VAL002", where, "An object was expected");
            return blankElement();
        }
        Set<String> known = new HashSet<>();
        children.stream().filter(c -> !c.binding.hidden()).forEach(c -> known.add(c.exposedName()));
        for (String name : obj.members().keySet()) {
            if (!known.contains(name)) {
                problems.add("VAL006", where.unknown(name), "The field is not known");
            }
        }
        List<WireValue> members = new ArrayList<>();
        for (FieldPlan child : children) {
            if (child.binding.hidden()) {
                members.add(child.blankValue());
            } else {
                ApiValue member = obj.members().getOrDefault(child.exposedName(), new ApiValue.Absent());
                members.add(child.encodeRequest(member, child.where(where), problems));
            }
        }
        return new WireValue.Struct(members);
    }

    // ------------------------------------------------------------------ the shape of what a program returned

    /**
     * Checks the shape of a returned value against this field, at any depth: an array where there is an array, with the number
     * of elements of a fixed count, a struct where there is a struct, with its members. A mismatch is a VAL101 and everything
     * under it is left alone. Scalars are not looked at here: their own codec reads them. A {@link WireValue.Blank} fits any
     * shape. Decoding, trimming and the error mapping rely on this having been done for the whole output.
     *
     * <p>Trim is applied here too, before any element beyond the kept part is looked at (kickoff 14, Part B): the
     * declared size is still checked against the field (the program always returns it in full), but the expensive
     * per-element shape check runs only for the elements {@link #decodeArray} will go on to decode. A
     * {@code countField} trim is read the same way {@link #decodeArray} reads it, but silently: a problem reading
     * the count field itself is not reported twice, it is left for {@link #decodeArray}'s own read to report; until
     * then, the full declared size is checked, same as before this narrowing existed.
     */
    void checkShape(WireValue value, Where where, Problems problems, Scope scope) {
        if (value instanceof WireValue.Blank) {
            return;
        }
        if (!isArray()) {
            checkElementShape(value, where, problems, scope);
            return;
        }
        if (!(value instanceof WireValue.Array array)) {
            problems.add("VAL101", where, "A list was expected");
            return;
        }
        if (field.count() instanceof Count.Fixed fixed && array.elements().size() != fixed.value()) {
            problems.add("VAL101", where, "The list does not have the number of elements of the field");
            return;
        }
        int limit = array.elements().size();
        if (binding.trim().isPresent() && binding.trim().get() instanceof Trim.DropBlank) {
            limit = trimmedLength(array.elements());
        } else if (binding.trim().isPresent() && binding.trim().get() instanceof Trim.CountField countField) {
            limit = probeCount(countField.path(), array.elements().size(), scope).orElse(limit);
        }
        for (int i = 0; i < limit; i++) {
            checkElementShape(array.elements().get(i), where.element(i), problems, scope);
        }
    }

    /** How many elements are left once a trailing run of blanks is dropped, found directly on the wire values. */
    private int trimmedLength(List<WireValue> elements) {
        int end = elements.size();
        while (end > 0 && isBlankElement(elements.get(end - 1))) {
            end--;
        }
        return end;
    }

    /** {@link #readCount}, but silent: a problem reading the count field is left for {@link #decodeArray} to report. */
    private Optional<Integer> probeCount(String path, int size, Scope scope) {
        return readCount(path, size, scope, true, Where.ROOT, new Problems());
    }

    private void checkElementShape(WireValue value, Where where, Problems problems, Scope scope) {
        if (!isStruct() || value instanceof WireValue.Blank) {
            return;
        }
        if (!(value instanceof WireValue.Struct struct) || struct.members().size() != children.size()) {
            problems.add("VAL101", where, "A struct with " + children.size() + " members was expected");
            return;
        }
        Scope inner = new Scope(children, struct.members(), scope.top(), scope.top().program());
        for (int i = 0; i < children.size(); i++) {
            children.get(i).checkShape(struct.members().get(i), children.get(i).where(where), problems, inner);
        }
    }

    // ------------------------------------------------------------------ decoding

    /** Decodes a returned field; empty, after a problem was reported, if it cannot be read. */
    Optional<ApiValue> decodeField(WireValue value, Where where, Problems problems, Scope scope, ValueBudget budget) {
        if (value instanceof WireValue.Blank) {
            return Optional.of(isArray() ? new ApiValue.Arr(List.of()) : isStruct() ? new ApiValue.Obj(Map.of()) : new ApiValue.Absent());
        }
        return isArray() ? decodeArray(value, where, problems, scope, budget) : decodeElement(value, where, problems, scope, budget);
    }

    private Optional<ApiValue> decodeArray(WireValue value, Where where, Problems problems, Scope scope, ValueBudget budget) {
        // checkShape has seen that this is an array, and of the right size for a fixed count
        List<WireValue> elements = ((WireValue.Array) value).elements();
        if (field.count() instanceof Count.Ref ref) {
            Optional<Integer> n = readCount(ref.field(), elements.size(), scope, false, where, problems);
            if (n.isEmpty()) {
                return Optional.empty();
            }
            elements = elements.subList(0, n.get());
        }
        Optional<Trim> trim = binding.trim();
        if (trim.isPresent() && trim.get() instanceof Trim.CountField countField) {
            Optional<Integer> n = readCount(countField.path(), elements.size(), scope, true, where, problems);
            if (n.isEmpty()) {
                return Optional.empty();
            }
            elements = elements.subList(0, n.get());
        } else if (trim.isPresent()) {
            elements = elements.subList(0, trimmedLength(elements));
        }
        List<ApiValue> decoded = new ArrayList<>();
        boolean ok = true;
        for (int i = 0; i < elements.size(); i++) {
            if (budget.exceeded()) {
                break;
            }
            Optional<ApiValue> element = decodeElement(elements.get(i), where.element(i), problems, scope, budget);
            if (element.isPresent()) {
                decoded.add(element.get());
            } else {
                ok = false;
            }
        }
        return ok ? Optional.of(new ApiValue.Arr(decoded)) : Optional.empty();
    }

    private Optional<ApiValue> decodeElement(WireValue value, Where where, Problems problems, Scope outer, ValueBudget budget) {
        if (!isStruct()) {
            if (value instanceof WireValue.Blank) {
                return Optional.of(new ApiValue.Absent());
            }
            if (!budget.take()) {
                if (budget.reportOnce()) {
                    problems.add("VAL104", where, "The response has more values than the limit allows; it is not built beyond that point");
                }
                return Optional.empty();
            }
            return scalar.decode(value, where, problems);
        }
        if (value instanceof WireValue.Blank) {
            return Optional.of(new ApiValue.Obj(Map.of()));
        }
        // checkShape has seen that this is a struct with the members of the field
        WireValue.Struct struct = (WireValue.Struct) value;
        Scope scope = new Scope(children, struct.members(), outer.top(), outer.top().program());
        Map<String, ApiValue> members = new LinkedHashMap<>();
        boolean ok = true;
        for (int i = 0; i < children.size(); i++) {
            if (budget.exceeded()) {
                break;
            }
            FieldPlan child = children.get(i);
            if (child.binding.hidden()) {
                continue;
            }
            Optional<ApiValue> member = child.decodeField(struct.members().get(i), child.where(where), problems, scope, budget);
            if (member.isEmpty()) {
                ok = false;
            } else if (!(member.get() instanceof ApiValue.Absent)) {
                members.put(child.exposedName(), member.get());
            }
        }
        return ok ? Optional.of(new ApiValue.Obj(members)) : Optional.empty();
    }

    /** Blank means every scalar inside is blank text, zero or zero bytes. Safe to call before the shape has been
     *  checked (kickoff 14, Part B, trimming ahead of {@link #checkShape}): a value of the wrong shape is simply
     *  not blank, leaving the real problem for {@link #checkElementShape} to report. */
    private boolean isBlankElement(WireValue value) {
        if (value instanceof WireValue.Blank) {
            return true;
        }
        if (isStruct()) {
            if (!(value instanceof WireValue.Struct struct) || struct.members().size() != children.size()) {
                return false;
            }
            for (int i = 0; i < children.size(); i++) {
                if (!children.get(i).isBlankValue(struct.members().get(i))) {
                    return false;
                }
            }
            return true;
        }
        return scalar.isBlank(value);
    }

    private boolean isBlankValue(WireValue value) {
        if (!isArray()) {
            return isBlankElement(value);
        }
        return value instanceof WireValue.Blank
                || value instanceof WireValue.Array array && array.elements().stream().allMatch(this::isBlankElement);
    }

    // ------------------------------------------------------------------ counts

    /**
     * Reads the number of elements to keep from a field that holds it. {@code fromTop} is true for an overlay path,
     * which starts at the program's parameters; a PCML reference starts next to the array.
     */
    private Optional<Integer> readCount(String path, int size, Scope scope, boolean fromTop, Where where, Problems problems) {
        Found found = resolve(path, fromTop ? scope.top() : scope, scope);
        if (found == null) {
            // check() has seen that the field is there; what is missing is its value, because a struct on the way is blank
            problems.add("VAL101", where, "The field that holds the number of elements cannot be read: the output does not have its struct");
            return Optional.empty();
        }
        Problems own = new Problems();
        Optional<ApiValue> read = found.value() instanceof WireValue.Blank ? Optional.empty()
                : found.plan().scalar.decode(found.value(), where, own);
        if (read.isEmpty() || !(read.get() instanceof ApiValue.Integer count)) {
            problems.add("VAL103", where, "The field that holds the number of elements is not a number");
            return Optional.empty();
        }
        BigInteger n = count.value();
        if (n.signum() < 0) {
            problems.add("VAL103", where, "The number of elements is negative");
            return Optional.empty();
        }
        if (n.compareTo(BigInteger.valueOf(size)) > 0) {
            problems.add("VAL103", where, "The number of elements is larger than the list: at most " + size);
            return Optional.empty();
        }
        return Optional.of(n.intValueExact());
    }

    /**
     * Finds a field by its dotted path, with its returned value if there are values. The path starts in {@code scope};
     * if it starts with the program's name it may also start at the top.
     */
    static Found resolve(String path, Scope start, Scope current) {
        String[] parts = path.split("\\.");
        Found found = find(start.plans(), start.values(), parts, 0);
        if (found == null && parts.length > 1 && parts[0].equals(current.top().program())) {
            found = find(current.top().plans(), current.top().values(), parts, 1);
        }
        return found;
    }

    private static Found find(List<FieldPlan> plans, List<WireValue> values, String[] parts, int from) {
        for (int i = 0; i < plans.size(); i++) {
            FieldPlan plan = plans.get(i);
            if (!plan.field.name().equals(parts[from])) {
                continue;
            }
            if (values != null && i >= values.size()) {
                // A struct on the way has fewer wire values than its model expects: unresolvable, same as a blank
                // one. Only reachable from checkShape's own, silent probe of a countField trim (kickoff 14, Part B),
                // before that struct's own shape has necessarily been checked yet; every other caller runs after a
                // clean checkShape, where plans and values always agree in length.
                return null;
            }
            WireValue value = values == null ? null : values.get(i);
            if (from == parts.length - 1) {
                return new Found(plan, value);
            }
            if (!plan.isStruct() || plan.isArray()) {
                return null;
            }
            List<WireValue> members = value instanceof WireValue.Struct struct ? struct.members() : null;
            if (values != null && members == null) {
                return null;
            }
            return find(plan.children, members, parts, from + 1);
        }
        return null;
    }
}
