package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.parser.PcmlParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading that tolerates {@code OVL001} (for tools that complete an overlay), and the in-memory load of one file. */
class AnnotationLoadTest {

    private static final String PCML = """
            <?xml version="1.0"?>
            <pcml version="6.0">
              <program name="UPD" path="/QSYS.LIB/DEMOLIB.LIB/UPD.PGM">
                <data name="ID" type="char" length="10" usage="input"/>
                <data name="QTY" type="int" length="4" usage="inputoutput"/>
                <data name="NOTE" type="char" length="4" usage="inherit"/>
                <data name="OUT" type="char" length="4" usage="output"/>
              </program>
              <program name="OTHER" path="/QSYS.LIB/DEMOLIB.LIB/OTHER.PGM">
                <data name="Z" type="char" length="4" usage="inputoutput"/>
              </program>
            </pcml>
            """;

    private static final String OVERLAY = """
            pcml: UPD.pcml
            version: 1.0.0
            operations:
              - id: upd
                program: UPD
                route: /upd
                method: POST
                parameters:
                  ID: { name: id }
              - id: other
                program: OTHER
                route: /other
                method: POST
                parameters:
                  Z: { direction: both }
            """;

    private static PcmlDocument pcml() {
        return PcmlParser.parse("UPD.pcml", new ByteArrayInputStream(PCML.getBytes(StandardCharsets.UTF_8))).document().orElseThrow();
    }

    private static GatewayDefaults failing() {
        GatewayDefaults standard = GatewayDefaults.standard();
        return new GatewayDefaults(standard.charCodec(), InputOutputPolicy.FAIL, standard.emitters(),
                standard.largeArrayThreshold(), standard.emitterSources());
    }

    @Test
    void ovl001IsStillAnErrorButTheOperationIsBuiltWithTheParameterInternal() {
        OverlayText.Loaded loaded = OverlayText.load("api.yaml", OVERLAY, pcml(), failing());
        assertThat(loaded.diagnostics()).extracting(Diagnostic::code, Diagnostic::severity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OVL001", Severity.ERROR),
                        org.assertj.core.groups.Tuple.tuple("OVL001", Severity.ERROR));
        assertThat(loaded.operations()).extracting(ApiOperation::id).containsExactly("upd", "other");
        assertThat(loaded.operations().get(0).bindings()).filteredOn(b -> b.fieldPath().equals("QTY"))
                .singleElement().satisfies(b -> assertThat(b.direction()).isEqualTo(Direction.INTERNAL));
        assertThat(loaded.undirected()).extracting(UndirectedParameter::operationId, UndirectedParameter::programName, u -> u.field().name(), u -> u.field().usage())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("upd", "UPD", "QTY", Usage.INPUT_OUTPUT),
                        org.assertj.core.groups.Tuple.tuple("upd", "UPD", "NOTE", Usage.INHERIT));
        assertThat(loaded.file()).isPresent();
    }

    @Test
    void thePolicyBothGivesAWarningBuildsAsBothAndStillListsTheParameters() {
        OverlayText.Loaded loaded = OverlayText.load("api.yaml", OVERLAY, pcml(), GatewayDefaults.standard());
        assertThat(loaded.diagnostics()).extracting(Diagnostic::severity).containsOnly(Severity.WARNING);
        assertThat(loaded.undirected()).hasSize(2);
        assertThat(loaded.operations().get(0).bindings()).filteredOn(b -> b.fieldPath().equals("QTY"))
                .singleElement().satisfies(b -> assertThat(b.direction()).isEqualTo(Direction.BOTH));
    }

    @Test
    void anInOnAParameterWithoutADirectionIsNotAnotherErrorButOnADirectedInternalOneItIs() {
        String undirected = OVERLAY.replace("ID: { name: id }", "ID: { name: id }\n      QTY: { in: query }");
        assertThat(OverlayText.load("api.yaml", undirected, pcml(), failing()).diagnostics()).extracting(Diagnostic::code)
                .containsExactly("OVL001", "OVL001");
        String directed = OVERLAY.replace("ID: { name: id }", "ID: { name: id }\n      QTY: { in: query, direction: internal }");
        assertThat(OverlayText.load("api.yaml", directed, pcml(), failing()).diagnostics()).extracting(Diagnostic::code)
                .contains("OVL012");
    }

    @Test
    void aRoutePlaceholderWaitsForTheParameterWithoutADirectionButWhatIsThereIsChecked() {
        String text = "pcml: UPD.pcml\nversion: 1.0.0\nprogram: UPD\nroute: /upd/{qty}\nmethod: POST\nparameters:\n  QTY: { in: path, name: qty }\n";
        assertThat(OverlayText.load("api.yaml", text, pcml(), failing()).diagnostics()).extracting(Diagnostic::code).containsExactly("OVL001", "OVL001");
        String bound = text.replace("QTY: { in: path, name: qty }", "ID: { in: path, name: other }");
        assertThat(OverlayText.load("api.yaml", bound, pcml(), failing()).diagnostics()).extracting(Diagnostic::code)
                .containsExactlyInAnyOrder("OVL004", "OVL001", "OVL001"); // ID is bound to a placeholder that the route does not have
        String directed = text.replace("QTY: { in: path, name: qty }", "QTY: { in: path, name: qty, direction: internal }");
        assertThat(OverlayText.load("api.yaml", directed, pcml(), failing()).diagnostics()).extracting(Diagnostic::code).contains("OVL012");
    }

    @Test
    void namesThatClashAreFoundWhileOtherParametersStillHaveNoDirection() {
        String text = "pcml: UPD.pcml\nversion: 1.0.0\nprogram: UPD\nroute: /upd\nmethod: POST\nparameters:\n"
                + "  ID: { name: same }\n  OUT: { name: same }\n  NOTE: { direction: request, name: same }\n";
        assertThat(OverlayText.load("api.yaml", text, pcml(), failing()).diagnostics()).extracting(Diagnostic::code).contains("OVL014");
    }

    @Test
    void aParameterWithADirectionIsNotUndirected() {
        String text = OVERLAY.replace("ID: { name: id }", "ID: { name: id }\n      QTY: { direction: request }\n      NOTE: { direction: internal }");
        OverlayText.Loaded loaded = OverlayText.load("api.yaml", text, pcml(), failing());
        assertThat(loaded.diagnostics()).isEmpty();
        assertThat(loaded.undirected()).isEmpty();
    }

    @Test
    void aSharedDirectionCounts() {
        String text = "pcml: UPD.pcml\nversion: 1.0.0\nprogram: UPD\nparameters:\n  QTY: { direction: both }\n  NOTE: { direction: both }\n"
                + "operations:\n  - id: a\n    route: /a\n    method: POST\n";
        assertThat(OverlayText.load("api.yaml", text, pcml(), failing()).undirected()).isEmpty();
    }

    @Test
    void aFileThatIsNotYamlGivesNoFileAndTheDiagnostic() {
        OverlayText.Loaded loaded = OverlayText.load("api.yaml", "a: [", pcml(), failing());
        assertThat(loaded.file()).isEmpty();
        assertThat(loaded.operations()).isEmpty();
        assertThat(loaded.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL010");
        assertThat(loaded.diagnostics().get(0).location().file()).isEqualTo("api.yaml");
    }

    @Test
    void anotherErrorInAnOperationStillLeavesItOut() {
        String text = OVERLAY.replace("method: POST\n    parameters:\n      ID", "method: POST\n    parameters:\n      NOPE: {}\n      ID");
        OverlayText.Loaded loaded = OverlayText.load("api.yaml", text, pcml(), failing());
        assertThat(loaded.diagnostics()).extracting(Diagnostic::code).contains("OVL002", "OVL001");
        assertThat(loaded.operations()).extracting(ApiOperation::id).containsExactly("other");
    }

    @Test
    void operationIdIsTheIdOrTheProgramInLowerCase() {
        OverlayFile file = OverlayText.load("api.yaml", OVERLAY, pcml(), failing()).file().orElseThrow();
        assertThat(file.operations()).extracting(o -> OverlayText.operationId(file, o).orElseThrow()).containsExactly("upd", "other");
        OverlayFile shared = OverlayText.load("api.yaml", "pcml: UPD.pcml\nversion: 1.0.0\nprogram: UPD\noperations:\n  - id: a\n    route: /a\n    method: POST\n",
                pcml(), failing()).file().orElseThrow();
        assertThat(OverlayText.operationId(shared, shared.operations().get(0))).contains("a");
        assertThat(OverlayText.operationId(shared, new OperationSettings(java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Map.of(), java.util.Map.of(), java.util.Optional.empty(),
                java.util.Optional.empty(), shared.operations().get(0).source()))).contains("upd");
    }

    @Test
    void theDefaultTypeFollowsTheWireType() {
        List<ai.generativearchitects.glow400.pcml.model.FieldDef> fields = pcml().programs().get(0).parameters();
        assertThat(OverlayText.defaultType(fields.get(0).wire())).contains(ai.generativearchitects.glow400.pcml.api.SemanticType.STRING);
        assertThat(OverlayText.defaultType(fields.get(1).wire())).contains(ai.generativearchitects.glow400.pcml.api.SemanticType.INTEGER);
        assertThat(OverlayText.defaultType(new ai.generativearchitects.glow400.pcml.model.WireType.Struct("S"))).isEmpty();
    }

    @Test
    void exposedNamesFollowTheLoadersRule() {
        assertThat(OverlayText.exposedName("P0ORDN")).isEqualTo("p0ordn");
        assertThat(OverlayText.exposedName("ORDER_NUMBER")).isEqualTo("orderNumber");
        assertThat(OverlayText.isValidExposedName("orderNumber")).isTrue();
        assertThat(OverlayText.isValidExposedName("order number")).isTrue();
        assertThat(OverlayText.isValidExposedName("")).isFalse();
        assertThat(OverlayText.isValidExposedName("   ")).isFalse();
        assertThat(OverlayText.isValidExposedName(" x")).isFalse();
        assertThat(OverlayText.isValidExposedName("x ")).isFalse();
        assertThat(OverlayText.isValidExposedName("a\nb")).isFalse();
        assertThat(OverlayText.isValidExposedName("a\tb")).isFalse();
        assertThat(OverlayText.isValidExposedName("a\u007fb")).isFalse();
    }

    // ---- over a repository on disk

    @Test
    void loadingARepositoryForAnnotationKeepsOvl001AndListsTheTargets(@TempDir Path dir) {
        Repos.Temp repo = new Repos.Temp(dir).file("gateway.yaml", "defaults:\n  inputoutput: fail\n")
                .file("apis/UPD/UPD.pcml", PCML).file("apis/UPD/api.yaml", OVERLAY)
                .api("ORD", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n"));
        RepositoryResult plain = repo.load();
        assertThat(plain.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL001", "OVL001");
        assertThat(plain.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("ordstatus", "other");

        OverlayLoader.Annotation annotation = OverlayLoader.loadRepositoryForAnnotation(dir);
        assertThat(annotation.result().diagnostics()).isEqualTo(plain.diagnostics());
        assertThat(annotation.result().model().orElseThrow().operations()).extracting(ApiOperation::id)
                .containsExactly("ordstatus", "upd", "other");
        assertThat(annotation.targets()).hasSize(1);
        AnnotationTarget target = annotation.targets().get(0);
        assertThat(target.apiName()).isEqualTo("upd");
        assertThat(target.overlayPath()).isEqualTo("apis/UPD/api.yaml");
        assertThat(target.pcmlPath()).isEqualTo("apis/UPD/UPD.pcml");
        assertThat(target.defaults().inputOutput()).isEqualTo(InputOutputPolicy.FAIL);
        assertThat(target.pcml().programs()).hasSize(2);
        assertThat(target.undirected()).extracting(u -> u.field().name()).containsExactly("QTY", "NOTE");
    }

    @Test
    void aRepositoryWithoutUndirectedParametersHasNoTargets(@TempDir Path dir) {
        new Repos.Temp(dir).api("ORD", Repos.Temp.overlay("parameters:\n  P0ORDN: { name: orderNumber }\n"));
        assertThat(OverlayLoader.loadRepositoryForAnnotation(dir).targets()).isEmpty();
        assertThat(OverlayLoader.loadRepositoryForAnnotation(dir.resolve("missing")).result().model()).isEmpty();
    }

    @Test
    void anUnknownRootIsOvl013ForAnnotationToo(@TempDir Path dir) {
        assertThat(OverlayLoader.loadRepositoryForAnnotation(dir.resolve("nope")).result().diagnostics())
                .extracting(Diagnostic::code).containsExactly("OVL013");
    }

}
