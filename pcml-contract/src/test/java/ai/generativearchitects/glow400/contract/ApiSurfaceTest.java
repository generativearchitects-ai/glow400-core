package ai.generativearchitects.glow400.contract;

import static ai.generativearchitects.glow400.contract.Apis.data;
import static ai.generativearchitects.glow400.contract.Apis.header;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The surface: which fields a consumer sees, their shape, and each kind of limit. */
class ApiSurfaceTest {

    private static FieldSurface field(Path dir, String attributes, String overlayField) {
        OperationSurface op = Apis.operation(dir, data("A", attributes, "input"), header("/x", "POST") + "parameters:\n  A: { name: a" + overlayField + " }\n");
        return op.request().get(0);
    }

    private static List<String> names(List<FieldSurface> fields) {
        return fields.stream().map(FieldSurface::exposedName).toList();
    }

    // ---- what is in the surface

    @Test
    void theApiAndTheOperationCarryTheirIdentityAndDocumentation(@TempDir Path dir) {
        ApiDefinition api = Apis.api(dir, data("A", "type=\"char\" length=\"4\"", "input"),
                header("/x/{a}", "GET") + "title: A title\nsummary: Sum\ndescription: Desc\ntags: [t1, t2]\nparameters:\n  A: { name: a, in: path }\n");
        ApiSurface surface = ApiSurface.of(api);
        assertThat(surface.name()).isEqualTo("t");
        assertThat(surface.title()).isEqualTo("A title");
        assertThat(surface.version()).isEqualTo("1.0.0");
        OperationSurface op = surface.operations().get(0);
        assertThat(op.id()).isEqualTo("t");
        assertThat(op.route()).isEqualTo("/x/{a}");
        assertThat(op.method()).isEqualTo(HttpMethod.GET);
        assertThat(op.summary()).contains("Sum");
        assertThat(op.description()).contains("Desc");
        assertThat(op.tags()).containsExactly("t1", "t2");
        assertThat(op.request().get(0).location()).contains(Location.PATH);
        assertThat(op.hasContent()).isFalse();
        assertThat(op.defaultErrorStatus()).isEmpty();
        assertThat(op.errorStatuses()).isEmpty();
    }

    @Test
    void requestAndResponseSidesAndBothFields(@TempDir Path dir) {
        String pcml = data("I", "type=\"char\" length=\"4\"", "input") + data("O", "type=\"char\" length=\"4\"", "output")
                + data("B", "type=\"char\" length=\"4\"", "inputoutput");
        OperationSurface op = Apis.operation(dir, pcml, header("/x", "POST") + "parameters:\n  I: { name: i }\n  O: { name: o }\n  B: { name: b, direction: both }\n");
        assertThat(names(op.request())).containsExactly("i", "b");
        assertThat(names(op.response())).containsExactly("o", "b");
        assertThat(op.hasContent()).isTrue();
        assertThat(op.request().get(0).location()).contains(Location.BODY);
        assertThat(op.response().get(0).location()).isEmpty();
    }

    @Test
    void internalInjectedAndHiddenFieldsAreNotInTheSurface(@TempDir Path dir) {
        String pcml = data("A", "type=\"char\" length=\"4\"", "input") + data("N", "type=\"char\" length=\"4\"", "output")
                + data("J", "type=\"char\" length=\"4\"", "input") + data("R", "type=\"struct\" struct=\"ROW\"", "output");
        OperationSurface op = Apis.operation(dir, pcml, header("/x", "POST") + "inject:\n  J: fix\nparameters:\n  A: { name: a }\n  N: { direction: internal }\n"
                + "  R:\n    name: row\n    direction: response\n    fields:\n      X: { name: x }\n      Y: { name: y }\n      H: { hidden: true }\n");
        assertThat(names(op.request())).containsExactly("a");
        assertThat(names(op.response())).containsExactly("row");
        assertThat(names(op.response().get(0).children())).containsExactly("x", "y");
    }

    @Test
    void theShapeIsScalarObjectOrArrayOfEither(@TempDir Path dir) {
        String pcml = data("S", "type=\"char\" length=\"4\"", "input") + data("T", "type=\"char\" length=\"4\" count=\"3\"", "input")
                + data("R", "type=\"struct\" struct=\"ROW\"", "input") + data("Q", "type=\"struct\" struct=\"ROW\" count=\"2\"", "input");
        OperationSurface op = Apis.operation(dir, pcml, header("/x", "POST") + "parameters:\n  S: { name: s }\n  T: { name: t }\n  R: { name: r }\n  Q: { name: q }\n");
        FieldSurface scalar = op.request().get(0);
        FieldSurface scalars = op.request().get(1);
        FieldSurface object = op.request().get(2);
        FieldSurface objects = op.request().get(3);
        assertThat(List.of(scalar.object(), scalar.array())).containsExactly(false, false);
        assertThat(List.of(scalars.object(), scalars.array())).containsExactly(false, true);
        assertThat(List.of(object.object(), object.array())).containsExactly(true, false);
        assertThat(List.of(objects.object(), objects.array())).containsExactly(true, true);
        assertThat(scalar.type()).contains(SemanticType.STRING);
        assertThat(scalars.type()).contains(SemanticType.STRING);
        assertThat(object.type()).isEmpty();
        assertThat(object.limits()).isEqualTo(Limits.none());
        assertThat(scalars.limits().maxItems()).contains(3);
        assertThat(objects.limits().maxItems()).contains(2);
        assertThat(scalar.limits().maxItems()).isEmpty();
        assertThat(object.children()).hasSize(3);
    }

    @Test
    void requiredDescriptionExampleAndOriginAreKept(@TempDir Path dir) {
        FieldSurface f = field(dir, "type=\"packed\" length=\"7\" precision=\"2\"", ", required: true, description: \"Desc\", example: \"1.50\"");
        assertThat(f.required()).isTrue();
        assertThat(f.description()).contains("Desc");
        assertThat(f.example()).contains("1.50");
        assertThat(f.origin().fieldPath()).isEqualTo("A");
        assertThat(f.origin().wire()).isEqualTo(new WireType.Packed(7, 2));
        FieldSurface plain = field(dir.resolve("p"), "type=\"char\" length=\"4\"", "");
        assertThat(plain.required()).isFalse();
        assertThat(plain.description()).isEmpty();
        assertThat(plain.example()).isEmpty();
    }

    @Test
    void errorStatusesAreTheDefaultAndEveryStatusByCodeSortedAndWithoutDuplicates(@TempDir Path dir) {
        String pcml = data("A", "type=\"char\" length=\"4\"", "input") + data("R", "type=\"char\" length=\"1\"", "output")
                + data("C", "type=\"char\" length=\"4\"", "output");
        OperationSurface op = Apis.operation(dir, pcml, header("/x", "POST") + "parameters:\n  A: { name: a }\n  R: { direction: internal }\n  C: { direction: internal }\n"
                + "error:\n  when: { field: R, equals: \"1\" }\n  code: C\n  httpStatus: 409\n  statusByCode: { A: 410, B: 404, C: 410, D: 409 }\n");
        assertThat(op.defaultErrorStatus()).contains(409);
        assertThat(op.errorStatuses()).containsExactly(404, 409, 410);
    }

    // ---- limits

    @Test
    void stringHasItsLengthAsMaximumUnlessTheLengthNamesAnotherField(@TempDir Path dir) {
        assertThat(field(dir.resolve("a"), "type=\"char\" length=\"12\"", "").limits().maxLength()).contains(12);
        assertThat(field(dir.resolve("b"), "type=\"varchar\" length=\"30\"", "").limits().maxLength()).contains(30);
        Limits limits = field(dir.resolve("c"), "type=\"char\" length=\"3\"", "").limits();
        assertThat(limits.minimum()).isEmpty();
        assertThat(limits.digits()).isEmpty();
        String pcml = data("N", "type=\"int\" length=\"4\"", "output") + data("S", "type=\"char\" length=\"N\"", "output");
        OperationSurface op = Apis.operation(dir.resolve("d"), pcml, header("/x", "POST") + "parameters:\n  N: { direction: internal }\n  S: { name: s }\n");
        assertThat(op.response().get(0).limits()).isEqualTo(Limits.none());
    }

    @Test
    void integerLimitsComeFromTheWireType(@TempDir Path dir) {
        Limits text = field(dir.resolve("a"), "type=\"char\" length=\"3\"", ", type: integer").limits();
        assertThat(text.minimum()).contains(BigInteger.ZERO);
        assertThat(text.maximum()).contains(BigInteger.valueOf(999));
        Limits two = field(dir.resolve("b"), "type=\"int\" length=\"2\"", "").limits();
        assertThat(two.minimum()).contains(BigInteger.valueOf(-32768));
        assertThat(two.maximum()).contains(BigInteger.valueOf(32767));
        Limits unsigned = field(dir.resolve("c"), "type=\"int\" length=\"4\" precision=\"32\"", "").limits();
        assertThat(unsigned.minimum()).contains(BigInteger.ZERO);
        assertThat(unsigned.maximum()).contains(BigInteger.valueOf(4294967295L));
        assertThat(field(dir.resolve("d"), "type=\"int\" length=\"4\" precision=\"31\"", "").limits().maximum()).contains(BigInteger.valueOf(2147483647L));
        assertThat(field(dir.resolve("e"), "type=\"int\" length=\"8\"", "").limits().minimum()).contains(BigInteger.valueOf(Long.MIN_VALUE));
        assertThat(field(dir.resolve("f"), "type=\"int\" length=\"8\" precision=\"64\"", "").limits().maximum())
                .contains(BigInteger.TWO.pow(64).subtract(BigInteger.ONE));
        Limits packed = field(dir.resolve("g"), "type=\"packed\" length=\"5\" precision=\"0\"", ", type: integer").limits();
        assertThat(packed.minimum()).contains(BigInteger.valueOf(-99999));
        assertThat(packed.maximum()).contains(BigInteger.valueOf(99999));
        Limits zoned = field(dir.resolve("h"), "type=\"zoned\" length=\"4\" precision=\"0\"", ", type: integer").limits();
        assertThat(zoned.minimum()).contains(BigInteger.valueOf(-9999));
        assertThat(zoned.maximum()).contains(BigInteger.valueOf(9999));
        String pcml = data("N", "type=\"int\" length=\"4\"", "output") + data("D", "type=\"char\" length=\"N\"", "output");
        OperationSurface op = Apis.operation(dir.resolve("i"), pcml, header("/x", "POST") + "parameters:\n  N: { direction: internal }\n  D: { name: d, type: integer }\n");
        assertThat(op.response().get(0).limits().minimum()).contains(BigInteger.ZERO);
        assertThat(op.response().get(0).limits().maximum()).isEmpty();
    }

    @Test
    void decimalLimitsAreDigitsAndScaleForPackedAndZonedAndTextHasNoSign(@TempDir Path dir) {
        Limits packed = field(dir.resolve("a"), "type=\"packed\" length=\"9\" precision=\"2\"", "").limits();
        assertThat(packed.digits()).contains(9);
        assertThat(packed.scale()).contains(2);
        assertThat(packed.minimum()).isEmpty();
        Limits zoned = field(dir.resolve("b"), "type=\"zoned\" length=\"6\" precision=\"3\"", "").limits();
        assertThat(zoned.digits()).contains(6);
        assertThat(zoned.scale()).contains(3);
        for (String wire : List.of("type=\"char\" length=\"8\"", "type=\"varchar\" length=\"8\"")) {
            Limits text = field(dir.resolve(wire.substring(6, 10)), wire, ", type: decimal").limits();
            assertThat(text.minimum()).contains(BigInteger.ZERO);
            assertThat(text.digits()).contains(8);
            assertThat(text.scale()).isEmpty();
        }
        assertThat(field(dir.resolve("c"), "type=\"float\" length=\"8\"", "").limits()).isEqualTo(Limits.none());
    }

    @Test
    void binaryHasItsLengthAsBothBounds(@TempDir Path dir) {
        Limits limits = field(dir, "type=\"byte\" length=\"6\"", "").limits();
        assertThat(limits.minBytes()).contains(6);
        assertThat(limits.maxBytes()).contains(6);
        assertThat(limits.maxLength()).isEmpty();
        String pcml = data("N", "type=\"int\" length=\"4\"", "output") + data("B", "type=\"byte\" length=\"N\"", "output");
        OperationSurface op = Apis.operation(dir.resolve("r"), pcml, header("/x", "POST") + "parameters:\n  N: { direction: internal }\n  B: { name: b }\n");
        assertThat(op.response().get(0).limits().minBytes()).isEmpty();
        assertThat(op.response().get(0).limits().maxBytes()).isEmpty();
    }

    @Test
    void booleanAndTemporalTypesHaveNoLimitAndAnArrayCountedByAFieldHasNoMaxItems(@TempDir Path dir) {
        assertThat(field(dir.resolve("a"), "type=\"char\" length=\"1\"", ", type: boolean").limits()).isEqualTo(Limits.none());
        assertThat(field(dir.resolve("b"), "type=\"date\"", "").limits()).isEqualTo(Limits.none());
        assertThat(field(dir.resolve("c"), "type=\"time\"", "").limits()).isEqualTo(Limits.none());
        assertThat(field(dir.resolve("d"), "type=\"timestamp\"", "").limits()).isEqualTo(Limits.none());
        String pcml = data("N", "type=\"int\" length=\"4\"", "output") + data("T", "type=\"char\" length=\"4\" count=\"N\"", "output");
        OperationSurface op = Apis.operation(dir.resolve("e"), pcml, header("/x", "POST") + "parameters:\n  N: { direction: internal }\n  T: { name: t }\n");
        assertThat(op.response().get(0).array()).isTrue();
        assertThat(op.response().get(0).limits().maxItems()).isEmpty();
        assertThat(op.response().get(0).limits().maxLength()).contains(4);
    }

    @Test
    void limitsWithMaxItemsKeepTheOthers() {
        Limits limits = new Limits(Optional.of(4), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        Limits with = limits.withMaxItems(7);
        assertThat(with.maxLength()).contains(4);
        assertThat(with.maxItems()).contains(7);
        assertThat(limits.maxItems()).isEmpty();
    }

    @Test
    void theTypesRejectNull() {
        assertThatThrownBy(() -> ApiSurface.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Origin(null, new WireType.Date())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Origin("A", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiSurface(null, "t", "1", List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiSurface("n", null, "1", List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApiSurface("n", "t", null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Limits(null, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void everyValidFixtureHasASurface() {
        for (String repository : List.of("action-parameter", "body-struct", "dclcase", "defaults-both", "injected", "kitchen-sink", "inputoutput",
                "minimal", "operations-list", "structs")) {
            var model = Fixtures.model(repository);
            for (ApiDefinition api : model.apis()) {
                ApiSurface surface = ApiSurface.of(api);
                assertThat(surface.operations()).as(repository).hasSameSizeAs(api.operations());
            }
        }
        var catalog = ApiSurface.of(Fixtures.model("kitchen-sink").apis().get(0));
        assertThat(catalog.operations().get(0).request().stream().map(FieldSurface::exposedName)).doesNotContain("P0SRC", "src");
    }
}
