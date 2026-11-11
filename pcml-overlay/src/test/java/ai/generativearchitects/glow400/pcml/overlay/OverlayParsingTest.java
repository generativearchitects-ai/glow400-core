package ai.generativearchitects.glow400.pcml.overlay;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keys, value types, enumerations and ranges of the overlay format. Every case is an overlay with exactly one fault;
 * the base overlay occupies lines 1 to 5, so appended lines start at line 6.
 */
class OverlayParsingTest {

    private static String overlay(String extra) {
        return Repos.Temp.overlay(extra);
    }

    private static Arguments rejects(String why, String yaml, String code, int line) {
        return Arguments.of(why, yaml, code, line);
    }

    static Stream<Arguments> singleFaults() {
        return Stream.of(
                // ---- the file as a whole
                rejects("empty file", "", "OVL012", 1),
                rejects("only a comment", "# nothing\n", "OVL012", 1),
                rejects("a null document", "~\n", "OVL012", 1),
                rejects("a list at the top", "- a\n- b\n", "OVL012", 1),
                rejects("a scalar at the top", "just text\n", "OVL012", 1),
                rejects("two documents", overlay("---\nprogram: X\n"), "OVL010", 6),
                rejects("a key that is not plain text", "? [a, b]\n: c\n", "OVL010", 1),
                // ---- required keys
                rejects("no pcml", "program: ORDSTATUS\nroute: /a\nmethod: GET\nversion: 1.0.0\n", "OVL012", 1),
                rejects("no route", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nmethod: GET\nversion: 1.0.0\n", "OVL012", 1),
                rejects("no method", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a\nversion: 1.0.0\n", "OVL012", 1),
                rejects("no program", "pcml: ORDSTATUS.pcml\nroute: /a\nmethod: GET\nversion: 1.0.0\n", "OVL012", 1),
                rejects("no version", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a\nmethod: GET\n", "OVL012", 1),
                // ---- scalar values
                rejects("blank pcml", "program: ORDSTATUS\npcml: \" \"\nroute: /a\nmethod: GET\nversion: 1.0.0\n", "OVL012", 2),
                rejects("pcml is a number", "program: ORDSTATUS\npcml: 5\nroute: /a\nmethod: GET\nversion: 1.0.0\n", "OVL012", 2),
                rejects("blank program", "program: \"\"\npcml: ORDSTATUS.pcml\nroute: /a\nmethod: GET\nversion: 1.0.0\n", "OVL012", 1),
                rejects("version without a patch", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a\nmethod: GET\nversion: \"1.0\"\n", "OVL012", 5),
                rejects("version is a number", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a\nmethod: GET\nversion: 1.0\n", "OVL012", 5),
                rejects("version with a leading zero", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a\nmethod: GET\nversion: 01.0.0\n", "OVL012", 5),
                rejects("method in lower case", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a\nmethod: get\nversion: 1.0.0\n", "OVL012", 4),
                rejects("unknown call strategy", overlay("call: fast\n"), "OVL012", 6),
                rejects("summary is a number", overlay("summary: 5\n"), "OVL012", 6),
                rejects("description is a list", overlay("description: [a]\n"), "OVL012", 6),
                rejects("id with a space", overlay("id: bad id\n"), "OVL012", 6),
                rejects("id starting with a dash", overlay("id: -x\n"), "OVL012", 6),
                rejects("blank id", overlay("id: \"\"\n"), "OVL012", 6),
                // ---- routes
                rejects("route without a leading slash", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: orders\nmethod: GET\nversion: 1.0.0\n", "OVL012", 3),
                rejects("route with an unclosed placeholder", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a/{b\nmethod: GET\nversion: 1.0.0\n", "OVL012", 3),
                rejects("route with a stray closing brace", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a/b}\nmethod: GET\nversion: 1.0.0\n", "OVL012", 3),
                rejects("route with an empty placeholder", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a/{}\nmethod: GET\nversion: 1.0.0\n", "OVL012", 3),
                rejects("route with nested placeholders", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a/{{b}}\nmethod: GET\nversion: 1.0.0\n", "OVL012", 3),
                rejects("route with a slash inside a placeholder", "program: ORDSTATUS\npcml: ORDSTATUS.pcml\nroute: /a/{b/c}\nmethod: GET\nversion: 1.0.0\n", "OVL012", 3),
                // ---- tags
                rejects("tags is not a list", overlay("tags: orders\n"), "OVL012", 6),
                rejects("a tag is a list", overlay("tags:\n  - [a]\n"), "OVL012", 7),
                // ---- inject
                rejects("inject is a list", overlay("inject: [a]\n"), "OVL012", 6),
                rejects("inject value is null", overlay("inject:\n  P0STAT:\n"), "OVL012", 7),
                rejects("inject value is a list", overlay("inject:\n  P0STAT: [a]\n"), "OVL012", 7),
                rejects("inject expression with an unknown root", overlay("inject:\n  P0STAT: ${user.name}\n"), "OVL012", 7),
                rejects("inject expression with text around it", overlay("inject:\n  P0STAT: x${tenant.id}\n"), "OVL012", 7),
                rejects("inject expression without a name", overlay("inject:\n  P0STAT: ${environment}\n"), "OVL012", 7),
                rejects("inject expression with a bad name", overlay("inject:\n  P0STAT: ${environment.1a}\n"), "OVL012", 7),
                // ---- parameters and fields
                rejects("parameters is a list", overlay("parameters: [a]\n"), "OVL012", 6),
                rejects("a parameter entry is a scalar", overlay("parameters:\n  P0ORDN: x\n"), "OVL012", 7),
                rejects("unknown direction", overlay("parameters:\n  P0ORDN: { direction: sideways }\n"), "OVL012", 7),
                rejects("injected is not a direction", overlay("parameters:\n  P0ORDN: { direction: injected }\n"), "OVL012", 7),
                rejects("unknown location", overlay("parameters:\n  P0ORDN: { in: cookie }\n"), "OVL012", 7),
                rejects("unknown type", overlay("parameters:\n  P0ORDN: { type: number }\n"), "OVL012", 7),
                rejects("unknown pad", overlay("parameters:\n  P0ORDN: { pad: middle }\n"), "OVL012", 7),
                rejects("padChar with two characters", overlay("parameters:\n  P0ORDN: { padChar: \"ab\" }\n"), "OVL012", 7),
                rejects("empty padChar", overlay("parameters:\n  P0ORDN: { padChar: \"\" }\n"), "OVL012", 7),
                rejects("padChar is a number", overlay("parameters:\n  P0ORDN: { padChar: 0 }\n"), "OVL012", 7),
                rejects("nullAs is a number", overlay("parameters:\n  P0ORDN: { nullAs: 0 }\n"), "OVL012", 7),
                rejects("required is not a boolean", overlay("parameters:\n  P0ORDN: { required: yes }\n"), "OVL012", 7),
                rejects("blank name", overlay("parameters:\n  P0ORDN: { name: \"\" }\n"), "OVL012", 7),
                rejects("format is a number", overlay("parameters:\n  P0DATE: { format: 5 }\n"), "OVL012", 7),
                rejects("example is a list", overlay("parameters:\n  P0ORDN: { example: [1] }\n"), "OVL012", 7),
                rejects("example is null", overlay("parameters:\n  P0ORDN: { example: ~ }\n"), "OVL012", 7),
                rejects("fields is a list", overlay("parameters:\n  P0ORDN: { fields: [a] }\n"), "OVL012", 7),
                rejects("hidden on a parameter", overlay("parameters:\n  P0ORDN: { hidden: true }\n"), "OVL011", 7),
                // ---- trim
                rejects("trim is a scalar", overlay("parameters:\n  P0ORDN: { trim: yes }\n"), "OVL012", 7),
                rejects("trim with both choices", overlay("parameters:\n  P0ORDN: { trim: { countField: A, dropBlank: true } }\n"), "OVL012", 7),
                rejects("trim with neither choice", overlay("parameters:\n  P0ORDN: { trim: {} }\n"), "OVL012", 7),
                rejects("trim with an unknown key", overlay("parameters:\n  P0ORDN: { trim: { keep: 5 } }\n"), "OVL011", 7),
                rejects("trim dropBlank false", overlay("parameters:\n  P0ORDN: { trim: { dropBlank: false } }\n"), "OVL012", 7),
                rejects("trim countField blank", overlay("parameters:\n  P0ORDN: { trim: { countField: \"\" } }\n"), "OVL012", 7),
                // ---- error
                rejects("error is a list", overlay("error: [a]\n"), "OVL012", 6),
                rejects("error with an unknown key", overlay("error:\n  when: { field: P0STAT, equals: K }\n  colour: red\n"), "OVL011", 8),
                rejects("when is a scalar", overlay("error:\n  when: x\n"), "OVL012", 7),
                rejects("when with an unknown key", overlay("error:\n  when: { field: P0STAT, equals: K, extra: 1 }\n"), "OVL011", 7),
                rejects("when without a field", overlay("error:\n  when: { equals: K }\n"), "OVL012", 7),
                rejects("when without an operator", overlay("error:\n  when: { field: P0STAT }\n"), "OVL012", 7),
                rejects("when with two operators", overlay("error:\n  when: { field: P0STAT, equals: K, notEquals: L }\n"), "OVL012", 7),
                rejects("when equals a list", overlay("error:\n  when: { field: P0STAT, equals: [K] }\n"), "OVL012", 7),
                rejects("when notEquals null", overlay("error:\n  when: { field: P0STAT, notEquals: ~ }\n"), "OVL012", 7),
                rejects("when in is not a list", overlay("error:\n  when: { field: P0STAT, in: K }\n"), "OVL012", 7),
                rejects("when in is empty", overlay("error:\n  when: { field: P0STAT, in: [] }\n"), "OVL012", 7),
                rejects("when in has a list inside", overlay("error:\n  when: { field: P0STAT, in: [[a]] }\n"), "OVL012", 7),
                rejects("when field blank", overlay("error:\n  when: { field: \"\", equals: K }\n"), "OVL012", 7),
                rejects("httpStatus too low", overlay("error:\n  when: { field: P0STAT, equals: K }\n  httpStatus: 399\n"), "OVL012", 8),
                rejects("httpStatus too high", overlay("error:\n  when: { field: P0STAT, equals: K }\n  httpStatus: 600\n"), "OVL012", 8),
                rejects("httpStatus is text", overlay("error:\n  when: { field: P0STAT, equals: K }\n  httpStatus: bad\n"), "OVL012", 8),
                rejects("httpStatus out of int range", overlay("error:\n  when: { field: P0STAT, equals: K }\n  httpStatus: 99999999999\n"), "OVL012", 8),
                rejects("statusByCode is a list", overlay("error:\n  when: { field: P0STAT, equals: K }\n  statusByCode: [a]\n"), "OVL012", 8),
                rejects("statusByCode value out of range", overlay("error:\n  when: { field: P0STAT, equals: K }\n  statusByCode: { X: 200 }\n"), "OVL012", 8),
                rejects("error code blank", overlay("error:\n  when: { field: P0STAT, equals: K }\n  code: \"\"\n"), "OVL012", 8),
                // ---- cache
                rejects("cache is a scalar", overlay("cache: 5\n"), "OVL012", 6),
                rejects("cache without ttlSeconds", overlay("cache: {}\n"), "OVL012", 6),
                rejects("cache with an unknown key", overlay("cache:\n  ttlSeconds: 5\n  size: 1\n"), "OVL011", 8),
                rejects("negative ttl", overlay("cache:\n  ttlSeconds: -1\n"), "OVL012", 7),
                rejects("ttl is text", overlay("cache:\n  ttlSeconds: soon\n"), "OVL012", 7),
                // ---- the operations list
                rejects("operations is not a list", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations: x\n", "OVL012", 4),
                rejects("operations is empty", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations: []\n", "OVL012", 4),
                rejects("route next to operations", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\nroute: /a\noperations:\n  - { id: x, route: /x, method: GET }\n", "OVL020", 5),
                rejects("method next to operations", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\nmethod: GET\noperations:\n  - { id: x, route: /x, method: GET }\n", "OVL020", 5),
                rejects("id next to operations", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\nid: top\noperations:\n  - { id: x, route: /x, method: GET }\n", "OVL011", 4),
                rejects("summary next to operations", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\nsummary: top\noperations:\n  - { id: x, route: /x, method: GET }\n", "OVL011", 4),
                rejects("an operation that is a scalar", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations:\n  - x\n", "OVL012", 5),
                rejects("an operation without an id", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations:\n  - { route: /x, method: GET }\n", "OVL012", 5),
                rejects("an operation without a route", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations:\n  - { id: x, method: GET }\n", "OVL012", 5),
                rejects("an operation without a method", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations:\n  - { id: x, route: /x }\n", "OVL012", 5),
                rejects("an operation without a program anywhere", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\noperations:\n  - { id: x, route: /x, method: GET }\n", "OVL012", 4),
                rejects("version inside an operation", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations:\n  - { id: x, route: /x, method: GET, version: 2.0.0 }\n", "OVL011", 5),
                rejects("pcml inside an operation", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations:\n  - { id: x, route: /x, method: GET, pcml: B.pcml }\n", "OVL011", 5),
                rejects("call inside an operation", "pcml: ORDSTATUS.pcml\nversion: 1.0.0\nprogram: ORDSTATUS\noperations:\n  - { id: x, route: /x, method: GET, call: raw }\n", "OVL011", 5),
                // ---- YAML
                rejects("duplicate key", overlay("route: /b\n"), "OVL010", 6),
                rejects("duplicate parameter", overlay("parameters:\n  P0ORDN: {}\n  P0ORDN: {}\n"), "OVL010", 8),
                rejects("tab indentation", overlay("parameters:\n\tP0ORDN: {}\n"), "OVL010", 7),
                rejects("custom tag", overlay("summary: !!binary aGk=\n"), "OVL010", 6));
    }

    /** Keys that a struct member does not accept; the member is not in the PCML either, so OVL002 comes with them. */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"direction: request", "in: body", "trim: { dropBlank: true }"})
    void aKeyThatAMemberDoesNotAcceptIsReportedWithTheMissingMember(String setting, @TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", overlay("parameters:\n  P0ORDN:\n    fields:\n      M: { " + setting + " }\n")).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().line())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OVL002", 9), org.assertj.core.groups.Tuple.tuple("OVL011", 9));
        assertThat(result.diagnostics().get(0).location().column()).isLessThan(result.diagnostics().get(1).location().column());
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleFaults")
    void reportsExactlyOneDiagnostic(String why, String yaml, String code, int line, @TempDir Path dir) {
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", yaml).load();
        assertThat(result.diagnostics()).as(why).hasSize(1);
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).as(why).isEqualTo(code);
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        assertThat(d.location().file()).isEqualTo("apis/A/api.yaml");
        assertThat(d.location().line()).as(why + ": " + d).isEqualTo(line);
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }

    @Test
    void anOperationWithAnErrorIsLeftOutAndTheOthersStay(@TempDir Path dir) {
        String yaml = """
                pcml: ORDSTATUS.pcml
                version: 1.0.0
                program: ORDSTATUS
                parameters:
                  P0ORDN: { name: orderNumber, in: path }
                operations:
                  - id: good
                    route: /orders/{orderNumber}
                    method: GET
                  - id: bad-route
                    route: orders
                    method: GET
                  - id: bad-method
                    route: /x
                    method: FETCH
                  - id: also-good
                    route: /orders/{orderNumber}/again
                    method: GET
                """;
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", yaml).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code, d -> d.location().line())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("OVL012", 11), org.assertj.core.groups.Tuple.tuple("OVL012", 15));
        assertThat(result.model().orElseThrow().operations()).extracting(ApiOperation::id).containsExactly("good", "also-good");
    }

    @Test
    void anErrorInTheSharedSettingsLeavesOutTheWholeFile(@TempDir Path dir) {
        String yaml = """
                pcml: ORDSTATUS.pcml
                version: 1.0
                program: ORDSTATUS
                operations:
                  - id: fine
                    route: /a
                    method: GET
                """;
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", yaml).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL012");
        assertThat(result.model().orElseThrow().operations()).isEmpty();
    }

    @Test
    void severalFaultsInOneFileAreAllReported(@TempDir Path dir) {
        String yaml = Repos.Temp.overlay("tags: x\ncache: {}\nerror: [a]\n");
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", yaml).load();
        assertThat(result.diagnostics()).extracting(d -> d.location().line()).containsExactly(6, 7, 8);
    }

    @Test
    void validUnusualButLegalValuesAreAccepted(@TempDir Path dir) {
        String yaml = """
                program: ORDSTATUS
                pcml: ORDSTATUS.pcml
                id: My.Id_2-x
                call: raw
                route: /a/{orderNumber}/b/{other}.json
                method: PATCH
                version: 1.2.3-rc.1+build.5
                tags: [a, 2, true]
                parameters:
                  P0ORDN: { name: orderNumber, example: true, padChar: " ", nullAs: "N/A" }
                  P0STAT: { name: other, direction: internal }
                """;
        RepositoryResult result = new Repos.Temp(dir).file("apis/A/ORDSTATUS.pcml", Repos.ORDSTATUS_PCML)
                .file("apis/A/api.yaml", yaml).load();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("OVL004");
        // P0STAT is internal, so {other} has no request binding.
    }
}
