package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Overlay files with an {@code operations} list: shared settings, per-operation overrides and where each entry must match. */
class OperationsListTest {

    private static final String PCML = """
            <?xml version="1.0"?>
            <pcml version="6.0">
              <struct name="PAYLOAD"><data name="X" type="char" length="4"/><data name="Y" type="char" length="4"/></struct>
              <program name="ONE" path="/QSYS.LIB/DEMOLIB.LIB/SRV.SRVPGM" entrypoint="ONE">
                <data name="P0ORDN" type="char" length="10" usage="input"/>
                <data name="P0STAT" type="char" length="1" usage="output"/>
                <data name="P0ERR" type="char" length="2" usage="output"/>
                <data name="P0DATA" type="struct" struct="PAYLOAD" usage="output"/>
              </program>
              <program name="TWO" path="/QSYS.LIB/DEMOLIB.LIB/SRV.SRVPGM" entrypoint="TWO">
                <data name="P0ORDN" type="char" length="10" usage="input"/>
                <data name="P0RSN" type="char" length="20" usage="input"/>
                <data name="P0ERR" type="char" length="2" usage="output"/>
              </program>
            </pcml>
            """;

    private static final String HEADER = "pcml: SRV.pcml\nversion: 1.0.0\n";
    private static final String OP_ONE = "  - id: one\n    program: ONE\n    route: /one/{orderNumber}\n    method: GET\n";
    private static final String OP_TWO = "  - id: two\n    program: TWO\n    route: /two/{orderNumber}\n    method: POST\n";

    private static RepositoryResult load(Path dir, String overlay) {
        return new Repos.Temp(dir).file("apis/A/SRV.pcml", PCML).file("apis/A/api.yaml", overlay).load();
    }

    private static ApiOperation operation(RepositoryResult result, String id) {
        assertThat(result.diagnostics()).isEmpty();
        return result.model().orElseThrow().operations().stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    private static FieldBinding binding(ApiOperation operation, String path) {
        return operation.bindings().stream().filter(b -> b.fieldPath().equals(path)).findFirst().orElseThrow();
    }

    private static List<Tuple> codesAndLines(RepositoryResult result) {
        return result.diagnostics().stream().map(d -> Tuple.tuple(d.code(), d.location().line())).toList();
    }

    @Test
    void anOperationsParameterSettingsAreMergedOverTheSharedOnesSettingBySetting(@TempDir Path dir) {
        String overlay = HEADER + "program: ONE\nparameters:\n  P0ORDN: { name: shared, type: integer, pad: left, padChar: \"0\", description: D }\n"
                + "  P0STAT: { name: status }\n  P0ERR: { direction: internal }\n  P0DATA: { direction: response }\n"
                + "operations:\n" + OP_ONE.replace("    program: ONE\n", "") + "    parameters:\n      P0ORDN: { name: orderNumber, in: path, pad: right }\n";
        ApiOperation op = operation(load(dir, overlay), "one");
        FieldBinding orderNumber = binding(op, "P0ORDN");
        assertThat(orderNumber.exposedName()).isEqualTo("orderNumber");
        assertThat(orderNumber.semanticType()).contains(SemanticType.INTEGER);
        assertThat(orderNumber.codec().pad()).isEqualTo(Pad.RIGHT);
        assertThat(orderNumber.codec().padChar()).isEqualTo('0');
        assertThat(orderNumber.description()).contains("D");
        assertThat(orderNumber.location()).contains(Location.PATH);
        assertThat(binding(op, "P0STAT").exposedName()).isEqualTo("status");
    }

    @Test
    void memberSettingsAreMergedPerMember(@TempDir Path dir) {
        String overlay = HEADER + "program: ONE\nparameters:\n  P0STAT: { name: status }\n  P0ERR: { direction: internal }\n"
                + "  P0DATA:\n    direction: response\n    fields:\n      X: { name: shared, description: sx }\n      Y: { name: sharedY }\n"
                + "operations:\n" + OP_ONE.replace("    program: ONE\n", "") + "    parameters:\n      P0ORDN: { in: path, name: orderNumber }\n"
                + "      P0DATA:\n        fields:\n          X: { name: own }\n          Y: { hidden: true }\n";
        ApiOperation op = operation(load(dir, overlay), "one");
        FieldBinding data = binding(op, "P0DATA");
        assertThat(data.exposedName()).isEqualTo("p0data");
        assertThat(data.children().get(0).exposedName()).isEqualTo("own");
        assertThat(data.children().get(0).description()).contains("sx");
        assertThat(data.children().get(1).exposedName()).isEqualTo("sharedY");
        assertThat(data.children().get(1).hidden()).isTrue();
    }

    @Test
    void errorMappingsAreMergedKeyByKeyAndCacheAndTagsAreReplaced(@TempDir Path dir) {
        String overlay = HEADER + "program: ONE\ntags: [shared]\ncache: { ttlSeconds: 10 }\n"
                + "error:\n  when: { field: P0ERR, equals: KO }\n  code: P0ERR\n  httpStatus: 409\n  statusByCode: { A: 404, B: 410 }\n"
                + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n"
                + "operations:\n" + OP_ONE.replace("    program: ONE\n", "")
                + "    tags: [own]\n    cache: { ttlSeconds: 99 }\n    error:\n      message: P0STAT\n      statusByCode: { B: 500, C: 503 }\n"
                + OP_TWO.replace("    program: TWO\n", "    program: TWO\n") + "    parameters:\n      P0ORDN: { in: path, name: orderNumber }\n      P0RSN: { name: reason }\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).isEmpty();
        ApiOperation one = result.model().orElseThrow().operations().get(0);
        ApiOperation two = result.model().orElseThrow().operations().get(1);
        assertThat(one.tags()).containsExactly("own");
        assertThat(two.tags()).containsExactly("shared");
        assertThat(one.cache().orElseThrow().ttlSeconds()).isEqualTo(99);
        assertThat(two.cache().orElseThrow().ttlSeconds()).isEqualTo(10);
        assertThat(one.errorMapping()).contains(new ErrorMapping(new ErrorCondition.Equals("P0ERR", "KO"),
                Optional.of("P0ERR"), Optional.of("P0STAT"), 409, Map.of("A", 404, "B", 500, "C", 503)));
        assertThat(two.errorMapping()).contains(new ErrorMapping(new ErrorCondition.Equals("P0ERR", "KO"),
                Optional.of("P0ERR"), Optional.empty(), 409, Map.of("A", 404, "B", 410)));
    }

    @Test
    void injectedValuesOfAnOperationOverrideTheSharedOnes(@TempDir Path dir) {
        String overlay = HEADER + "program: TWO\ninject:\n  P0RSN: shared\nparameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0ERR: { direction: internal }\n"
                + "operations:\n" + OP_TWO.replace("    program: TWO\n", "")
                + "  - id: other\n    route: /other/{orderNumber}\n    method: POST\n    inject:\n      P0RSN: own\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).isEmpty();
        List<ApiOperation> operations = result.model().orElseThrow().operations();
        assertThat(binding(operations.get(0), "P0RSN").injection()).contains("shared");
        assertThat(binding(operations.get(1), "P0RSN").injection()).contains("own");
    }

    @Test
    void theOperationsProgramWinsOverTheTopLevelOne(@TempDir Path dir) {
        String overlay = HEADER + "program: ONE\nparameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0ERR: { direction: internal }\n"
                + "operations:\n" + OP_TWO + "    parameters:\n      P0RSN: { name: reason }\n";
        ApiOperation op = operation(load(dir, overlay), "two");
        assertThat(op.program().name()).isEqualTo("TWO");
    }

    @Test
    void everyOperationNeedsAProgramFromSomewhere(@TempDir Path dir) {
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\noperations:\n"
                + OP_ONE.replace("    program: ONE\n", "") + "  - id: has\n    program: TWO\n    route: /has/{orderNumber}\n    method: POST\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactly(Tuple.tuple("OVL012", 6));
        assertThat(result.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("has");
    }

    // ---- where entries must match (OVL002)

    @Test
    void aSharedEntryThatOneProgramLacksIsIgnoredForThatOperation(@TempDir Path dir) {
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0RSN: { name: reason }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\noperations:\n" + OP_ONE + OP_TWO;
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.model().orElseThrow().operations()).hasSize(2);
        assertThat(binding(result.model().orElseThrow().operations().get(0), "P0ORDN").exposedName()).isEqualTo("orderNumber");
    }

    @Test
    void aSharedEntryThatNoProgramHasIsOvl002(@TempDir Path dir) {
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0GONE: { name: gone }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\n  P0RSN: { name: reason }\noperations:\n" + OP_ONE + OP_TWO;
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactly(Tuple.tuple("OVL002", 5));
        assertThat(result.diagnostics().get(0).message()).contains("'P0GONE'").contains("ONE").contains("TWO");
        // The operations themselves are fine and stay.
        assertThat(result.model().orElseThrow().operations()).hasSize(2);
    }

    @Test
    void aSharedInjectThatNoProgramHasIsOvl002(@TempDir Path dir) {
        String overlay = HEADER + "inject:\n  P0GONE: x\nparameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\noperations:\n" + OP_ONE;
        assertThat(codesAndLines(load(dir, overlay))).containsExactly(Tuple.tuple("OVL002", 4));
    }

    @Test
    void aSharedMemberThatNoStructHasIsOvl002(@TempDir Path dir) {
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0STAT: { name: status }\n  P0ERR: { direction: internal }\n  P0DATA:\n    direction: response\n    fields:\n      X: { name: x }\n      NOPE: { name: nope }\noperations:\n" + OP_ONE;
        assertThat(codesAndLines(load(dir, overlay))).containsExactly(Tuple.tuple("OVL002", 11));
    }

    @Test
    void aSharedEntryThatNoProgramHasIsReportedEvenWhenAnOperationOfTheFileHasAnError(@TempDir Path dir) {
        // The second operation cannot be built (P0RSN gets a contradicting direction), yet every program is known.
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0GONE: { name: gone }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\noperations:\n"
                + OP_ONE + OP_TWO + "    parameters:\n      P0RSN: { direction: response }\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactlyInAnyOrder(Tuple.tuple("OVL016", 19), Tuple.tuple("OVL002", 5));
        assertThat(result.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("one");
    }

    @Test
    void aSharedEntryThatNoProgramHasIsReportedEvenWhenTheHeaderHasAnError(@TempDir Path dir) {
        String overlay = "pcml: SRV.pcml\nversion: \"1\"\nprogram: ONE\nparameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0GONE: { name: gone }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\noperations:\n"
                + OP_ONE.replace("    program: ONE\n", "");
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactly(Tuple.tuple("OVL012", 2), Tuple.tuple("OVL002", 6));
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }

    @Test
    void anOperationsOwnEntryThatItsProgramLacksLeavesOutThatOperationOnly(@TempDir Path dir) {
        // P0RSN exists in TWO but not in ONE.
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0ERR: { direction: internal }\noperations:\n"
                + OP_ONE + "    parameters:\n      P0RSN: { name: reason }\n      P0STAT: { name: status }\n      P0DATA: { direction: response }\n"
                + OP_TWO + "    parameters:\n      P0RSN: { name: reason }\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactly(Tuple.tuple("OVL002", 12));
        assertThat(result.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("two");
    }

    @Test
    void membersOfAScalarAreNotInThePcml(@TempDir Path dir) {
        String overlay = HEADER + "program: ONE\nparameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0STAT: { name: status, fields: { A: { name: a } } }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\noperations:\n" + OP_ONE.replace("    program: ONE\n", "");
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL002");
        assertThat(result.diagnostics().get(0).message()).contains("P0STAT.A");
    }

    @Test
    void aSharedEntryThatNoProgramHasIsReportedEvenWhenAnOperationHasAnInvalidValue(@TempDir Path dir) {
        // The second operation has an invalid route, but its program is known.
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0GONE: { name: gone }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\noperations:\n"
                + OP_ONE + "  - id: broken\n    program: TWO\n    route: broken\n    method: GET\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactly(Tuple.tuple("OVL002", 5), Tuple.tuple("OVL012", 16));
    }

    @Test
    void anUnknownKeyInAnOperationStillLetsItsNamesBeChecked(@TempDir Path dir) {
        String overlay = HEADER + "program: ONE\noperations:\n  - id: one\n    route: /one\n    method: GET\n    colour: red\n"
                + "    parameters:\n      P0GONE: { name: gone }\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactly(Tuple.tuple("OVL011", 8), Tuple.tuple("OVL002", 10));
    }

    @Test
    void aSharedEntryIsNotCheckedWhenAnEntryOfTheListCannotBeRead(@TempDir Path dir) {
        // The second entry is not a mapping, so its program is unknown: P0GONE might be meant for it.
        String overlay = HEADER + "program: ONE\nparameters:\n  P0GONE: { name: gone }\noperations:\n  - id: one\n    route: /one\n    method: GET\n  - just text\n";
        assertThat(codesAndLines(load(dir, overlay))).containsExactly(Tuple.tuple("OVL012", 10));
    }

    @Test
    void sharedEntriesAreNotCheckedWhenAProgramIsMissing(@TempDir Path dir) {
        String overlay = HEADER + "parameters:\n  P0GONE: { name: gone }\noperations:\n"
                + "  - id: one\n    program: NOSUCH\n    route: /one\n    method: GET\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL013");
        assertThat(result.diagnostics().get(0).location().line()).isEqualTo(7);
    }

    @Test
    void aSharedInOnAParameterThatIsAResponseInOneOperationDropsOnlyThatOperation(@TempDir Path dir) {
        // P0ORDN is a request field in both programs; P0STAT exists in ONE only, as a response field.
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0STAT: { in: header, name: status }\n  P0ERR: { direction: internal }\n  P0DATA: { direction: response }\noperations:\n"
                + OP_ONE + OP_TWO + "    parameters:\n      P0RSN: { name: reason }\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(codesAndLines(result)).containsExactly(Tuple.tuple("OVL012", 5));
        assertThat(result.diagnostics().get(0).location().column()).isEqualTo(17);
        assertThat(result.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("two");
    }

    @Test
    void anInThatEveryOperationUsesAsARequestFieldIsFine(@TempDir Path dir) {
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: query, name: orderNumber }\n  P0STAT: { name: status }\n  P0ERR: { direction: internal }\n  P0DATA: { direction: response }\n  P0RSN: { name: reason }\noperations:\n"
                + OP_ONE.replace("/one/{orderNumber}", "/one") + OP_TWO.replace("/two/{orderNumber}", "/two");
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).isEmpty();
        assertThat(binding(result.model().orElseThrow().operations().get(0), "P0ORDN").location()).contains(Location.QUERY);
    }

    // ---- uniqueness

    @Test
    void operationIdsAreUniqueWithinTheRepository(@TempDir Path dir) {
        String overlay = HEADER + "parameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0STAT: { name: status }\n  P0DATA: { direction: response }\n  P0ERR: { direction: internal }\n  P0RSN: { name: reason }\noperations:\n"
                + OP_ONE + OP_TWO.replace("id: two", "id: one");
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL014");
        assertThat(result.diagnostics().get(0).location().line()).isEqualTo(14);
        assertThat(result.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("one");
    }

    @Test
    void routesThatDifferOnlyInPlaceholderNamesAreTheSameRoute(@TempDir Path dir) {
        String overlay = HEADER + "program: TWO\nparameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0ERR: { direction: internal }\noperations:\n"
                + "  - id: a\n    route: /x/{orderNumber}\n    method: POST\n  - id: b\n    route: /x/{orderNumber}\n    method: POST\n";
        RepositoryResult result = load(dir, overlay);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL014");
        assertThat(result.diagnostics().get(0).message()).contains("'b'").contains("'a'");
        assertThat(result.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("a");
    }

    @Test
    void theSameRouteWithAnotherMethodIsAnotherOperation(@TempDir Path dir) {
        String overlay = HEADER + "program: TWO\nparameters:\n  P0ORDN: { in: path, name: orderNumber }\n  P0ERR: { direction: internal }\noperations:\n"
                + "  - id: a\n    route: /x/{orderNumber}\n    method: POST\n  - id: b\n    route: /x/{orderNumber}\n    method: PUT\n";
        assertThat(load(dir, overlay).model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("a", "b");
    }
}
