package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.CacheSettings;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.parser.PcmlParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The complete API model of every valid fixture repository, written out by hand from the merge rules of kickoff 02. */
class RepositoryModelTest {

    private static final SourceLocation ANY = new SourceLocation("-", 0, 0);
    private static final CodecSettings TEXT = new CodecSettings(Pad.RIGHT, ' ', "", Optional.empty());
    private static final CodecSettings NONE = CodecSettings.none();

    // ---- builder for expected bindings

    private static final class B {
        private final String path;
        private final Direction direction;
        private String name;
        private Location location;
        private SemanticType type;
        private CodecSettings codec = TEXT;
        private boolean required;
        private String description;
        private String example;
        private String injection;
        private boolean hidden;
        private Trim trim;
        private List<FieldBinding> children = List.of();

        B(String path, Direction direction, String name) {
            this.path = path;
            this.direction = direction;
            this.name = name;
        }

        B at(Location l) { location = l; return this; }
        B type(SemanticType t) { type = t; return this; }
        B codec(CodecSettings c) { codec = c; return this; }
        B required() { required = true; return this; }
        B description(String d) { description = d; return this; }
        B example(String e) { example = e; return this; }
        B injected(String i) { injection = i; return this; }
        B hidden() { hidden = true; return this; }
        B trim(Trim t) { trim = t; return this; }
        B children(FieldBinding... c) { children = List.of(c); return this; }

        FieldBinding build() {
            return new FieldBinding(path, direction, Optional.ofNullable(injection), name, Optional.ofNullable(location),
                    Optional.ofNullable(type), codec, required, Optional.ofNullable(description),
                    Optional.ofNullable(example), hidden, Optional.ofNullable(trim), children, ANY);
        }
    }

    private static B b(String path, Direction direction, String name, SemanticType type) {
        return new B(path, direction, name).type(type);
    }

    private static B text(String path, Direction direction, String name) {
        return b(path, direction, name, SemanticType.STRING);
    }

    // ---- helpers

    private static ProgramDef program(String repository, String folder, String file, String name) {
        Path pcml = Repos.fixture(repository).resolve("apis").resolve(folder).resolve(file);
        PcmlDocument document = PcmlParser.parse(pcml).document().orElseThrow();
        return document.programs().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private static ApiRepositoryModel loadClean(String repository, int warnings) {
        RepositoryResult result = Repos.load(repository);
        assertThat(result.diagnostics()).as("diagnostics of %s", repository).hasSize(warnings)
                .allSatisfy(d -> assertThat(d.severity()).isEqualTo(Severity.WARNING));
        return result.model().orElseThrow();
    }

    private static void assertOperation(ApiOperation actual, ApiOperation expected) {
        assertThat(actual).usingRecursiveComparison()
                .withStrictTypeChecking()
                .ignoringFieldsOfTypes(SourceLocation.class)
                .isEqualTo(expected);
    }

    // ---- fixtures

    @Test
    void minimal() {
        ApiRepositoryModel model = loadClean("minimal", 0);
        assertThat(model.defaults()).isEqualTo(GatewayDefaults.standard());
        assertThat(model.operations()).hasSize(1);
        assertOperation(model.operations().get(0), new ApiOperation(
                "ordstatus", "1.0.0",
                Optional.of("Returns the status of a purchase order"), Optional.of("Looks up one order by number."),
                List.of("orders"), program("minimal", "ORDSTATUS", "ORDSTATUS.pcml", "ORDSTATUS"),
                "apis/ORDSTATUS/ORDSTATUS.pcml", "apis/ORDSTATUS/api.yaml",
                "/orders/{orderNumber}/status", HttpMethod.GET, CallStrategy.AUTO,
                List.of(
                        text("P0ORDN", Direction.REQUEST, "orderNumber").at(Location.PATH).required()
                                .description("Purchase order number").example("1042").build(),
                        text("P0STAT", Direction.RESPONSE, "status").build(),
                        b("P0DATE", Direction.RESPONSE, "deliveryDate", SemanticType.DATE)
                                .codec(new CodecSettings(Pad.RIGHT, ' ', "", Optional.of("yyyyMMdd"))).build()),
                Optional.empty(), Optional.empty(), ANY));
    }

    @Test
    void inputOutputTakesDirectionsFromTheOverlay() {
        ApiRepositoryModel model = loadClean("inputoutput", 0);
        assertOperation(model.operations().get(0), new ApiOperation(
                "updqty", "1.0.0", Optional.empty(), Optional.empty(), List.of(),
                program("inputoutput", "UPDQTY", "UPDQTY.pcml", "UPDQTY"),
                "apis/UPDQTY/UPDQTY.pcml", "apis/UPDQTY/api.yaml",
                "/items/{itemId}/quantity", HttpMethod.POST, CallStrategy.AUTO,
                List.of(
                        text("ITEMID", Direction.REQUEST, "itemId").at(Location.PATH).required().build(),
                        b("QTY", Direction.BOTH, "quantity", SemanticType.INTEGER).at(Location.BODY).codec(NONE).build(),
                        text("STATUS", Direction.RESPONSE, "status").build()),
                Optional.empty(), Optional.empty(), ANY));
    }

    @Test
    void inputOutputWithoutDirectionIsBothWhenThePolicyIsBoth() {
        RepositoryResult result = Repos.load("defaults-both");
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OVL001", Severity.WARNING),
                        org.assertj.core.groups.Tuple.tuple("OVL001", Severity.WARNING));
        ApiRepositoryModel model = result.model().orElseThrow();
        assertThat(model.defaults()).isEqualTo(new GatewayDefaults(TEXT, InputOutputPolicy.BOTH,
                List.of("descriptor", "openapi", "mock")));
        assertOperation(model.operations().get(0), new ApiOperation(
                "getbal", "1.0.0", Optional.empty(), Optional.empty(), List.of(),
                program("defaults-both", "GETBAL", "GETBAL.pcml", "GETBAL"),
                "apis/GETBAL/GETBAL.pcml", "apis/GETBAL/api.yaml", "/balances", HttpMethod.GET, CallStrategy.AUTO,
                List.of(
                        text("ACCT", Direction.BOTH, "acct").at(Location.QUERY).build(),
                        b("BAL", Direction.BOTH, "bal", SemanticType.DECIMAL).at(Location.QUERY).codec(NONE).build()),
                Optional.empty(), Optional.empty(), ANY));
    }

    @Test
    void inputOutputWithoutDirectionLeavesTheOperationOutWhenThePolicyIsFail() {
        RepositoryResult result = Repos.load("defaults-fail");
        assertThat(result.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OVL001", Severity.ERROR),
                        org.assertj.core.groups.Tuple.tuple("OVL001", Severity.ERROR));
        ApiRepositoryModel model = result.model().orElseThrow();
        assertThat(model.defaults().inputOutput()).isEqualTo(InputOutputPolicy.FAIL);
        assertThat(model.operations()).isEmpty();
    }

    @Test
    void structsArraysTrimAndHiddenMembers() {
        ApiRepositoryModel model = loadClean("structs", 0);
        FieldBinding price = b("P0ROWS.PRICE", Direction.RESPONSE, "price", null).codec(NONE).children(
                b("P0ROWS.PRICE.AMT", Direction.RESPONSE, "amount", SemanticType.DECIMAL).codec(NONE).build(),
                text("P0ROWS.PRICE.CUR", Direction.RESPONSE, "currency").build()).build();
        FieldBinding rows = b("P0ROWS", Direction.RESPONSE, "lines", null).codec(NONE)
                .trim(new Trim.CountField("P0NROW")).children(
                        text("P0ROWS.ITEMCD", Direction.RESPONSE, "itemCode").build(),
                        b("P0ROWS.QTY", Direction.RESPONSE, "quantity", SemanticType.INTEGER)
                                .codec(new CodecSettings(Pad.LEFT, '0', "", Optional.empty())).build(),
                        text("P0ROWS.FILLER", Direction.RESPONSE, "filler").hidden().build(),
                        price).build();
        assertOperation(model.operations().get(0), new ApiOperation(
                "listlines", "2.1.0", Optional.empty(), Optional.empty(), List.of(),
                program("structs", "LISTLINES", "LISTLINES.pcml", "LISTLINES"),
                "apis/LISTLINES/LISTLINES.pcml", "apis/LISTLINES/api.yaml",
                "/orders/{orderNumber}/lines", HttpMethod.GET, CallStrategy.AUTO,
                List.of(
                        text("P0ORDN", Direction.REQUEST, "orderNumber").at(Location.PATH).required().build(),
                        b("P0NROW", Direction.INTERNAL, "p0nrow", SemanticType.INTEGER).codec(NONE).build(),
                        rows),
                Optional.empty(), Optional.empty(), ANY));
    }

    @Test
    void requestStructsAndArraysTravelInTheBody() {
        ApiRepositoryModel model = loadClean("body-struct", 0);
        assertOperation(model.operations().get(0), new ApiOperation(
                "neworder", "1.0.0", Optional.empty(), Optional.empty(), List.of(),
                program("body-struct", "NEWORDER", "NEWORDER.pcml", "NEWORDER"),
                "apis/NEWORDER/NEWORDER.pcml", "apis/NEWORDER/api.yaml", "/orders", HttpMethod.POST, CallStrategy.AUTO,
                List.of(
                        b("P0HDR", Direction.REQUEST, "header", null).at(Location.BODY).codec(NONE).children(
                                text("P0HDR.CUSTID", Direction.REQUEST, "customerId").at(Location.BODY).required().build(),
                                text("P0HDR.NOTE", Direction.REQUEST, "note").at(Location.BODY).build()).build(),
                        text("P0TAGS", Direction.REQUEST, "tags").at(Location.BODY).build(),
                        text("P0ORDN", Direction.RESPONSE, "orderNumber").build()),
                Optional.empty(), Optional.empty(), ANY));
    }

    @Test
    void severalOperationsShareAndOverrideSettings() {
        ApiRepositoryModel model = loadClean("operations-list", 0);
        assertThat(model.operations()).extracting(ApiOperation::id).containsExactly("get-order-status", "cancel-order");
        CodecSettings zeroPadded = new CodecSettings(Pad.LEFT, '0', "", Optional.empty());
        FieldBinding env = text("P0ENV", Direction.INJECTED, "p0env").injected("${environment.code}").build();
        FieldBinding orderNumber = b("P0ORDN", Direction.REQUEST, "orderNumber", SemanticType.INTEGER)
                .at(Location.PATH).required().codec(zeroPadded).build();

        assertOperation(model.operations().get(0), new ApiOperation(
                "get-order-status", "1.2.0", Optional.of("Returns the status of a purchase order"), Optional.empty(),
                List.of("orders"), program("operations-list", "ORDERSRV", "ORDERSRV.pcml", "GETSTATUS"),
                "apis/ORDERSRV/ORDERSRV.pcml", "apis/ORDERSRV/api.yaml",
                "/orders/{orderNumber}/status", HttpMethod.GET, CallStrategy.AUTO,
                List.of(env, orderNumber, text("P0STAT", Direction.RESPONSE, "status").build()),
                Optional.empty(), Optional.empty(), ANY));
        assertOperation(model.operations().get(1), new ApiOperation(
                "cancel-order", "1.2.0", Optional.empty(), Optional.empty(), List.of("orders", "changes"),
                program("operations-list", "ORDERSRV", "ORDERSRV.pcml", "CANCELORD"),
                "apis/ORDERSRV/ORDERSRV.pcml", "apis/ORDERSRV/api.yaml",
                "/orders/{orderNumber}/cancel", HttpMethod.POST, CallStrategy.AUTO,
                List.of(env, orderNumber, text("P0RSN", Direction.REQUEST, "reason").at(Location.BODY).build()),
                Optional.empty(), Optional.empty(), ANY));
    }

    @Test
    void sameProgramWithADifferentInjectedActionPerOperation() {
        ApiRepositoryModel model = loadClean("action-parameter", 0);
        assertThat(model.operations()).extracting(ApiOperation::id).containsExactly("cancel-order", "hold-order");
        for (int i = 0; i < 2; i++) {
            String action = i == 0 ? "CANCEL" : "HOLD";
            String id = i == 0 ? "cancel-order" : "hold-order";
            assertOperation(model.operations().get(i), new ApiOperation(
                    id, "1.0.0", Optional.empty(), Optional.empty(), List.of(),
                    program("action-parameter", "ORDACT", "ORDACT.pcml", "ORDACT"),
                    "apis/ORDACT/ORDACT.pcml", "apis/ORDACT/api.yaml",
                    "/orders/{orderNumber}/" + (i == 0 ? "cancel" : "hold"), HttpMethod.POST, CallStrategy.AUTO,
                    List.of(
                            text("P0ACT", Direction.INJECTED, "p0act").injected(action).build(),
                            text("P0ORDN", Direction.REQUEST, "orderNumber").at(Location.PATH).required().build(),
                            text("P0RES", Direction.RESPONSE, "result").build()),
                    Optional.empty(), Optional.empty(), ANY));
        }
    }

    @Test
    void injectedParametersErrorMappingAndCache() {
        ApiRepositoryModel model = loadClean("injected", 0);
        assertOperation(model.operations().get(0), new ApiOperation(
                "ordstatus", "1.0.0", Optional.empty(), Optional.empty(), List.of(),
                program("injected", "ORDSTATUS", "ORDSTATUS.pcml", "ORDSTATUS"),
                "apis/ORDSTATUS/ORDSTATUS.pcml", "apis/ORDSTATUS/api.yaml",
                "/orders/{orderNumber}/status", HttpMethod.GET, CallStrategy.AUTO,
                List.of(
                        text("P0ENV", Direction.INJECTED, "p0env").injected("${environment.code}").build(),
                        text("P0TEN", Direction.INJECTED, "p0ten").injected("${tenant.id}").build(),
                        text("P0ORDN", Direction.REQUEST, "orderNumber").at(Location.PATH).required().build(),
                        text("P0STAT", Direction.RESPONSE, "status").build(),
                        text("P0ERR", Direction.INTERNAL, "p0err").build(),
                        text("P0ERRC", Direction.INTERNAL, "p0errc").build(),
                        text("P0XERR", Direction.INTERNAL, "p0xerr").build()),
                Optional.of(new ErrorMapping(new ErrorCondition.Equals("P0ERR", "KO"), Optional.of("P0ERRC"),
                        Optional.of("P0XERR"), 422, Map.of("NOTFOUND", 404))),
                Optional.of(new CacheSettings(0)), ANY));
    }

    @Test
    void mixedCaseRpgNamesGetReadableExposedNames() {
        ApiRepositoryModel model = loadClean("dclcase", 0);
        assertThat(model.operations().get(0).bindings()).extracting(FieldBinding::fieldPath, FieldBinding::exposedName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("OrderNumber", "orderNumber"),
                        org.assertj.core.groups.Tuple.tuple("ORDER_DATE", "orderDate"),
                        org.assertj.core.groups.Tuple.tuple("Cust_Name", "cust_Name"),
                        org.assertj.core.groups.Tuple.tuple("_LEAD__TRAIL_", "leadTrail"));
        assertThat(model.operations().get(0).bindings().get(0).location()).contains(Location.PATH);
    }

    @Test
    void theProgramIsTheCallModelProgramUnchanged() {
        ApiOperation operation = Repos.load("minimal").model().orElseThrow().operations().get(0);
        ProgramDef expected = program("minimal", "ORDSTATUS", "ORDSTATUS.pcml", "ORDSTATUS");
        assertThat(operation.program()).usingRecursiveComparison().ignoringFieldsOfTypes(SourceLocation.class)
                .isEqualTo(expected);
        // Positions inside the PCML are named after the repository-relative file.
        assertThat(operation.program().source().file()).isEqualTo("apis/ORDSTATUS/ORDSTATUS.pcml");
    }

    @Test
    void bindingPositionsAreOverlayPositionsOrPcmlPositionsWhenDerived() {
        List<FieldBinding> bindings = Repos.load("minimal").model().orElseThrow().operations().get(0).bindings();
        assertThat(bindings.get(0).source()).isEqualTo(new SourceLocation("apis/ORDSTATUS/api.yaml", 11, 3));
        List<FieldBinding> dcl = Repos.load("dclcase").model().orElseThrow().operations().get(0).bindings();
        assertThat(dcl.get(0).source()).isEqualTo(new SourceLocation("apis/DCLCASE/DCLCASE.pcml", 4, 5));
    }
}
