package ai.generativearchitects.glow400.diff;

import static ai.generativearchitects.glow400.diff.Repo.changes;
import static ai.generativearchitects.glow400.diff.Repo.data;
import static ai.generativearchitects.glow400.diff.Repo.diff;
import static ai.generativearchitects.glow400.diff.Repo.header;
import static ai.generativearchitects.glow400.diff.Repo.overlay;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One test per row of the classification table of kickoff 05. */
class ClassificationTest {

    private static final String A = data("A", "type=\"char\" length=\"4\"", "input");
    private static final String AB = A + data("B", "type=\"char\" length=\"4\"", "input");
    private static final String PA = "  A: { name: a }\n";
    private static final String PB = "  B: { name: b }\n";
    private static final String OPS = "pcml: T.pcml\nversion: 1.0.0\nprogram: T\noperations:\n";

    /** An overlay with one operation in block style, since a route with a placeholder cannot be written in a flow mapping. */
    private static String op(String id, String route, String method, String name) {
        return OPS + "  - id: " + id + "\n    route: \"" + route + "\"\n    method: " + method + "\n    parameters:\n      A: { name: " + name + ", in: path }\n";
    }

    private static DiffResult same(Path dir, String pcml, String overlayBefore, String overlayAfter) {
        return diff(dir, pcml, overlayBefore, pcml, overlayAfter);
    }

    // ---- nothing changed

    @Test
    void identicalRepositoriesHaveNoChangeAndNoDiagnostic(@TempDir Path dir) {
        DiffResult result = same(dir, AB, overlay("/x", "POST", "", PA + PB), overlay("/x", "POST", "", PA + PB));
        assertThat(result.apis()).hasSize(1);
        assertThat(result.apis().get(0).impact()).isEqualTo(Impact.NONE);
        assertThat(result.apis().get(0).changes()).isEmpty();
        assertThat(result.apis().get(0).api()).isEqualTo("t");
        assertThat(result.apis().get(0).releasedVersion()).contains("1.0.0");
        assertThat(result.apis().get(0).currentVersion()).contains("1.0.0");
        assertThat(result.diagnostics()).isEmpty();
    }

    // ---- operations

    @Test
    void anOperationRemovedIsBreakingAndAnOperationAddedIsCompatible(@TempDir Path dir) {
        String one = OPS + "  - { id: one, route: /one, method: POST, parameters: { A: { name: a } } }\n";
        String two = one + "  - { id: two, route: /two, method: POST, parameters: { A: { name: a } } }\n";
        DiffResult removed = same(dir.resolve("r"), A, two, one);
        assertThat(changes(removed)).containsExactly("BREAKING ");
        assertThat(removed.apis().get(0).changes().get(0).operationId()).isEqualTo("two");
        assertThat(removed.apis().get(0).changes().get(0).description()).isEqualTo("Operation two was removed");
        DiffResult added = same(dir.resolve("a"), A, one, two);
        assertThat(changes(added)).containsExactly("COMPATIBLE ");
        assertThat(added.apis().get(0).changes().get(0).description()).isEqualTo("Operation two was added");
        assertThat(added.apis().get(0).impact()).isEqualTo(Impact.COMPATIBLE);
    }

    @Test
    void aChangedRouteOrMethodIsBreaking(@TempDir Path dir) {
        assertThat(changes(same(dir.resolve("a"), A, overlay("/x", "POST", "", PA), overlay("/y", "POST", "", PA)))).containsExactly("BREAKING route");
        assertThat(changes(same(dir.resolve("b"), A, overlay("/x", "POST", "", PA), overlay("/x", "PUT", "", PA)))).containsExactly("BREAKING method");
        assertThat(same(dir.resolve("c"), A, overlay("/x", "POST", "", PA), overlay("/x", "PUT", "", PA)).apis().get(0).changes().get(0).description())
                .isEqualTo("The method of operation t changed from POST to PUT");
    }

    @Test
    void aChangedIdOnTheSameRouteAndMethodIsOneOperationWhoseIdChanged(@TempDir Path dir) {
        String before = op("one", "/x/{a}", "POST", "a");
        String after = op("uno", "/x/{a}", "POST", "a");
        DiffResult result = same(dir.resolve("a"), A, before, after);
        assertThat(changes(result)).containsExactly("BREAKING id");
        assertThat(result.apis().get(0).changes().get(0).description()).isEqualTo("The id of operation one changed to uno");
        // placeholder names are ignored for the match, so the renamed placeholder is a route change and not a removal and an addition
        String renamed = op("uno", "/x/{b}", "POST", "b");
        DiffResult second = same(dir.resolve("b"), A, before, renamed);
        assertThat(changes(second)).contains("BREAKING id", "BREAKING route", "BREAKING request.a");
        assertThat(changes(second)).doesNotContain("BREAKING ");
        // another method is not the same operation
        String other = op("uno", "/x/{a}", "PUT", "a");
        assertThat(changes(same(dir.resolve("c"), A, before, other))).containsExactly("BREAKING ", "COMPATIBLE ");
    }

    // ---- request fields

    @Test
    void aRequestFieldRemovedIsBreakingAndAddedIsCompatibleWhenOptionalAndBreakingWhenRequired(@TempDir Path dir) {
        assertThat(changes(diff(dir.resolve("a"), AB, overlay("/x", "POST", "", PA + PB), A, overlay("/x", "POST", "", PA))))
                .containsExactly("BREAKING request.b", "INTERNAL parameters", "INTERNAL field settings");
        assertThat(changes(diff(dir.resolve("b"), A, overlay("/x", "POST", "", PA), AB, overlay("/x", "POST", "", PA + PB))))
                .containsExactly("COMPATIBLE request.b", "INTERNAL parameters", "INTERNAL field settings");
        DiffResult required = diff(dir.resolve("c"), A, overlay("/x", "POST", "", PA), AB, overlay("/x", "POST", "", PA + "  B: { name: b, required: true }\n"));
        assertThat(changes(required)).startsWith("BREAKING request.b");
        assertThat(required.apis().get(0).changes().get(0).description()).isEqualTo("Request field b was added, and it is required");
        assertThat(diff(dir.resolve("d"), A, overlay("/x", "POST", "", PA), AB, overlay("/x", "POST", "", PA + PB)).apis().get(0).changes().get(0).description())
                .isEqualTo("Request field b was added, and it is optional");
    }

    @Test
    void aRequestFieldThatBecameRequiredIsBreakingAndOptionalIsCompatible(@TempDir Path dir) {
        String optional = overlay("/x", "POST", "", PA);
        String required = overlay("/x", "POST", "", "  A: { name: a, required: true }\n");
        assertThat(changes(same(dir.resolve("a"), A, optional, required))).containsExactly("BREAKING request.a");
        assertThat(changes(same(dir.resolve("b"), A, required, optional))).containsExactly("COMPATIBLE request.a");
    }

    @Test
    void aRequestFieldThatMovedIsBreaking(@TempDir Path dir) {
        DiffResult result = same(dir, A, overlay("/x", "POST", "", PA), overlay("/x", "POST", "", "  A: { name: a, in: query }\n"));
        assertThat(changes(result)).containsExactly("BREAKING request.a");
        assertThat(result.apis().get(0).changes().get(0).description()).isEqualTo("Request field a moved from body to query");
    }

    // ---- response fields

    @Test
    void aResponseFieldRemovedIsBreakingAndAddedIsCompatible(@TempDir Path dir) {
        String two = data("A", "type=\"char\" length=\"4\"", "output") + data("B", "type=\"char\" length=\"4\"", "output");
        String one = data("A", "type=\"char\" length=\"4\"", "output");
        assertThat(changes(diff(dir.resolve("a"), two, overlay("/x", "POST", "", PA + PB), one, overlay("/x", "POST", "", PA))))
                .startsWith("BREAKING response.b");
        assertThat(changes(diff(dir.resolve("b"), one, overlay("/x", "POST", "", PA), two, overlay("/x", "POST", "", PA + PB))))
                .startsWith("COMPATIBLE response.b");
        assertThat(diff(dir.resolve("c"), one, overlay("/x", "POST", "", PA), two, overlay("/x", "POST", "", PA + PB)).apis().get(0).changes().get(0).description())
                .isEqualTo("Response field b was added");
    }

    @Test
    void aResponseThatWentFromContentToNoContentIsBreakingAndTheOtherWayIsCompatible(@TempDir Path dir) {
        String out = data("A", "type=\"char\" length=\"4\"", "output");
        String none = data("A", "type=\"char\" length=\"4\"", "output");
        String noneOverlay = overlay("/x", "POST", "", "  A: { direction: internal }\n");
        String contentOverlay = overlay("/x", "POST", "", PA);
        DiffResult lost = same(dir.resolve("a"), out, contentOverlay, noneOverlay);
        assertThat(changes(lost)).contains("BREAKING response");
        assertThat(changes(lost)).doesNotContain("BREAKING response.a");
        assertThat(lost.apis().get(0).changes().get(0).description()).isEqualTo("Operation t no longer returns content");
        DiffResult gained = same(dir.resolve("b"), none, noneOverlay, contentOverlay);
        assertThat(changes(gained)).contains("COMPATIBLE response");
        assertThat(changes(gained)).doesNotContain("COMPATIBLE response.a");
        assertThat(gained.apis().get(0).changes().get(0).description()).isEqualTo("Operation t now returns content");
    }

    // ---- types and shapes, either side

    @Test
    void aFieldWhoseTypeChangedIsBreakingOnEitherSide(@TempDir Path dir) {
        for (String usage : new String[] {"input", "output"}) {
            String pcml = data("A", "type=\"char\" length=\"4\"", usage);
            String side = usage.equals("input") ? "request" : "response";
            DiffResult result = same(dir.resolve(usage), pcml, overlay("/x", "POST", "", PA), overlay("/x", "POST", "", "  A: { name: a, type: integer }\n"));
            assertThat(changes(result)).contains("BREAKING " + side + ".a");
            assertThat(result.apis().get(0).changes().get(0).description()).isEqualTo(
                    (side.equals("request") ? "Request" : "Response") + " field a changed type from string to integer");
        }
    }

    @Test
    void aFieldThatChangedBetweenScalarObjectAndArrayIsBreakingOnEitherSide(@TempDir Path dir) {
        String scalar = data("A", "type=\"char\" length=\"4\"", "input");
        String array = data("A", "type=\"char\" length=\"4\" count=\"3\"", "input");
        String object = data("A", "type=\"struct\" struct=\"ROW\"", "input");
        String objects = data("A", "type=\"struct\" struct=\"ROW\" count=\"3\"", "input");
        String[] pcmls = {scalar, array, object, objects};
        String[] names = {"a scalar", "an array of scalars", "an object", "an array of objects"};
        for (int i = 0; i < pcmls.length; i++) {
            for (int j = 0; j < pcmls.length; j++) {
                if (i == j) {
                    continue;
                }
                DiffResult result = diff(dir.resolve(i + "-" + j), pcmls[i], overlay("/x", "POST", "", PA), pcmls[j], overlay("/x", "POST", "", PA));
                assertThat(changes(result)).as(names[i] + " to " + names[j]).contains("BREAKING request.a");
                assertThat(result.apis().get(0).changes().get(0).description()).isEqualTo("Request field a changed from " + names[i].replace("a scalar", "a scalar")
                        .replaceAll("^an object$", "a object") + " to " + names[j].replaceAll("^an object$", "a object"));
            }
        }
        // the same on the response side
        DiffResult response = diff(dir.resolve("resp"), data("A", "type=\"char\" length=\"4\"", "output"), overlay("/x", "POST", "", PA),
                data("A", "type=\"struct\" struct=\"ROW\"", "output"), overlay("/x", "POST", "", PA));
        assertThat(changes(response)).contains("BREAKING response.a");
    }

    @Test
    void membersOfAnObjectAreComparedWithTheirParentsAsPath(@TempDir Path dir) {
        String pcml = data("R", "type=\"struct\" struct=\"ROW\"", "input");
        String before = overlay("/x", "POST", "", "  R:\n    name: r\n    fields:\n      X: { name: x }\n      Y: { name: y }\n");
        String after = overlay("/x", "POST", "", "  R:\n    name: r\n    fields:\n      X: { name: x, required: true }\n      Y: { name: z }\n");
        DiffResult result = same(dir, pcml, before, after);
        assertThat(changes(result)).containsExactlyInAnyOrder("BREAKING request.r.x", "BREAKING request.r.y", "COMPATIBLE request.r.z");
        assertThat(result.apis().get(0).changes().stream().filter(c -> c.path().equals("request.r.z")).findFirst().orElseThrow().description())
                .isEqualTo("Request field r.z was added, and it is optional");
    }

    // ---- limits

    private static DiffResult limits(Path dir, String usage, String before, String after) {
        return diff(dir, data("A", before, usage), overlay("/x", "POST", "", PA), data("A", after, usage), overlay("/x", "POST", "", PA));
    }

    @Test
    void aTighterRequestLimitIsBreakingAndALooserOneIsCompatible(@TempDir Path dir) {
        String[][] pairs = {
                {"type=\"char\" length=\"10\"", "type=\"char\" length=\"5\"", "maxLength"},
                {"type=\"varchar\" length=\"10\"", "type=\"varchar\" length=\"5\"", "maxLength"},
                {"type=\"int\" length=\"4\"", "type=\"int\" length=\"2\"", "maximum, minimum"},
                {"type=\"int\" length=\"4\"", "type=\"int\" length=\"4\" precision=\"32\"", "minimum"},
                {"type=\"packed\" length=\"9\" precision=\"2\"", "type=\"packed\" length=\"7\" precision=\"2\"", "digits"},
                {"type=\"byte\" length=\"8\"", "type=\"byte\" length=\"4\"", "maxBytes"},
                {"type=\"char\" length=\"4\" count=\"5\"", "type=\"char\" length=\"4\" count=\"3\"", "maxItems"},
        };
        int n = 0;
        for (String[] pair : pairs) {
            DiffResult tighter = limits(dir.resolve("t" + n), "input", pair[0], pair[1]);
            assertThat(changes(tighter)).as(pair[2] + " tighter").contains("BREAKING request.a");
            assertThat(tighter.apis().get(0).changes().stream().filter(c -> c.impact() == Impact.BREAKING).findFirst().orElseThrow().description())
                    .as(pair[2]).contains("got tighter").contains(pair[2].split(",")[0]);
            // signed to unsigned raises the minimum and the maximum, and a byte field has an exact length: the way back is a mix of looser and tighter
            DiffResult looser = limits(dir.resolve("l" + n), "input", pair[1], pair[0]);
            if (pair[2].equals("minimum") || pair[2].equals("maxBytes")) {
                assertThat(changes(looser)).as("mixed").contains("BREAKING request.a");
            } else {
                assertThat(changes(looser)).as(pair[2] + " looser").contains("COMPATIBLE request.a").doesNotContain("BREAKING request.a");
            }
            n++;
        }
    }

    @Test
    void anAddedOrRemovedRequestLimitCountsAsTighterOrLooser(@TempDir Path dir) {
        // a length that names another field has no limit: from a limit to none is looser, and the other way tighter
        String n = data("N", "type=\"int\" length=\"4\"", "input");
        String fixed = n + data("A", "type=\"char\" length=\"4\" count=\"3\"", "input");
        DiffResult more = limits(dir.resolve("a"), "input", "type=\"char\" length=\"4\" count=\"3\"", "type=\"char\" length=\"4\" count=\"3\" ");
        assertThat(changes(more)).isEmpty();
        DiffResult none = limits(dir.resolve("b"), "input", "type=\"char\" length=\"4\"", "type=\"char\" length=\"4\" count=\"9\"");
        assertThat(changes(none)).contains("BREAKING request.a");
        assertThat(fixed).contains("count=\"3\"");
    }

    @Test
    void aResponseLimitThatChangedIsCompatibleWhicheverWay(@TempDir Path dir) {
        DiffResult tighter = limits(dir.resolve("a"), "output", "type=\"char\" length=\"10\"", "type=\"char\" length=\"5\"");
        assertThat(changes(tighter)).startsWith("COMPATIBLE response.a");
        DiffResult looser = limits(dir.resolve("b"), "output", "type=\"char\" length=\"5\"", "type=\"char\" length=\"10\"");
        assertThat(changes(looser)).startsWith("COMPATIBLE response.a");
        assertThat(looser.apis().get(0).changes().get(0).description()).isEqualTo("The limits of response field a changed: maxLength");
    }

    @Test
    void aDecimalScaleThatChangedIsBreakingOnEitherSide(@TempDir Path dir) {
        for (String usage : new String[] {"input", "output"}) {
            String side = usage.equals("input") ? "request" : "response";
            DiffResult result = limits(dir.resolve(usage), usage, "type=\"packed\" length=\"9\" precision=\"2\"", "type=\"packed\" length=\"9\" precision=\"3\"");
            assertThat(changes(result)).contains("BREAKING " + side + ".a");
            assertThat(result.apis().get(0).changes().stream().filter(c -> c.impact() == Impact.BREAKING).findFirst().orElseThrow().description())
                    .isEqualTo("The decimal scale of a changed from 2 to 3");
        }
    }

    // ---- error statuses

    private static final String ERRORS = A + data("R", "type=\"char\" length=\"1\"", "output") + data("C", "type=\"char\" length=\"4\"", "output");

    private static String errors(String status, String byCode) {
        return overlay("/x", "POST", "", PA + "  R: { direction: internal }\n  C: { direction: internal }\n")
                + "error:\n  when: { field: R, equals: \"1\" }\n  code: C\n  httpStatus: " + status + "\n  statusByCode: " + byCode + "\n";
    }

    @Test
    void anErrorStatusRemovedOrADefaultChangedIsBreakingAndAnAddedStatusIsCompatible(@TempDir Path dir) {
        DiffResult removed = same(dir.resolve("a"), ERRORS, errors("422", "{ NF: 404, GONE: 410 }"), errors("422", "{ NF: 404 }"));
        assertThat(changes(removed)).containsExactly("BREAKING error.410");
        assertThat(removed.apis().get(0).changes().get(0).description()).isEqualTo("Operation t no longer returns the error status 410");
        DiffResult added = same(dir.resolve("b"), ERRORS, errors("422", "{ NF: 404 }"), errors("422", "{ NF: 404, GONE: 410 }"));
        assertThat(changes(added)).containsExactly("COMPATIBLE error.410");
        assertThat(added.apis().get(0).changes().get(0).description()).isEqualTo("Operation t now also returns the error status 410");
        DiffResult changed = same(dir.resolve("c"), ERRORS, errors("422", "{ NF: 404 }"), errors("409", "{ NF: 404 }"));
        assertThat(changes(changed)).containsExactly("BREAKING error");
        assertThat(changed.apis().get(0).changes().get(0).description()).isEqualTo("The default error status of operation t changed from 422 to 409");
    }

    @Test
    void anErrorMappingRemovedOrAddedMovesAllItsStatuses(@TempDir Path dir) {
        String without = overlay("/x", "POST", "", PA + "  R: { direction: internal }\n  C: { direction: internal }\n");
        DiffResult removed = same(dir.resolve("a"), ERRORS, errors("422", "{ NF: 404 }"), without);
        assertThat(changes(removed)).contains("BREAKING error.404", "BREAKING error.422");
        DiffResult added = same(dir.resolve("b"), ERRORS, without, errors("422", "{ NF: 404 }"));
        assertThat(changes(added)).contains("COMPATIBLE error.404", "COMPATIBLE error.422");
        assertThat(changes(added)).doesNotContain("BREAKING error");
    }

    // ---- documentation

    @Test
    void textForPeopleIsDocumentation(@TempDir Path dir) {
        String base = header("/x", "POST");
        String[][] edits = {
                {"title: One\n", "title: Two\n", "title"},
                {"summary: One\n", "summary: Two\n", "documentation"},
                {"description: One\n", "description: Two\n", "documentation"},
                {"tags: [one]\n", "tags: [two]\n", "documentation"},
        };
        int n = 0;
        for (String[] edit : edits) {
            DiffResult result = diff(dir.resolve("e" + n++), A, base + edit[0] + "parameters:\n" + PA, A, base + edit[1] + "parameters:\n" + PA);
            assertThat(changes(result)).as(edit[2]).containsExactly("DOCUMENTATION " + edit[2]);
            assertThat(result.apis().get(0).impact()).isEqualTo(Impact.DOCUMENTATION);
        }
        DiffResult field = same(dir.resolve("f"), A, overlay("/x", "POST", "", "  A: { name: a, description: One }\n"),
                overlay("/x", "POST", "", "  A: { name: a, description: Two }\n"));
        assertThat(changes(field)).containsExactly("DOCUMENTATION request.a");
        DiffResult example = same(dir.resolve("g"), A, overlay("/x", "POST", "", "  A: { name: a, example: one }\n"),
                overlay("/x", "POST", "", "  A: { name: a, example: two }\n"));
        assertThat(changes(example)).containsExactly("DOCUMENTATION request.a");
        assertThat(example.apis().get(0).changes().get(0).description()).isEqualTo("The description or example of a changed");
    }

    // ---- internal

    @Test
    void whatConsumersCannotSeeIsInternal(@TempDir Path dir) {
        // padding and the wire format of a field
        assertThat(changes(same(dir.resolve("a"), A, overlay("/x", "POST", "", PA), overlay("/x", "POST", "", "  A: { name: a, pad: left, padChar: \"0\" }\n"))))
                .containsExactly("INTERNAL field settings");
        assertThat(changes(diff(dir.resolve("b"), A, overlay("/x", "POST", "", PA), A.replace("length=\"4\"", "length=\"4\" ccsid=\"37\""), overlay("/x", "POST", "", PA))))
                .containsExactly("INTERNAL parameters", "INTERNAL field settings");
        // the call strategy, the cache and the error-mapping fields that keep the same statuses
        assertThat(changes(same(dir.resolve("c"), A, overlay("/x", "POST", "", PA), overlay("/x", "POST", "call: raw\n", PA)))).containsExactly("INTERNAL call strategy");
        assertThat(changes(same(dir.resolve("d"), A, overlay("/x", "POST", "", PA), overlay("/x", "POST", "cache:\n  ttlSeconds: 5\n", PA)))).containsExactly("INTERNAL cache");
        DiffResult mapping = same(dir.resolve("e"), ERRORS, errors("422", "{ NF: 404 }"), errors("422", "{ NF: 404 }").replace("code: C", "message: C"));
        assertThat(changes(mapping)).containsExactly("INTERNAL error mapping");
        assertThat(mapping.apis().get(0).impact()).isEqualTo(Impact.INTERNAL);
        assertThat(mapping.apis().get(0).changes().get(0).description()).isEqualTo("The error mapping of operation t changed, which consumers cannot see");
    }

    @Test
    void anInjectedValueAndAHiddenMemberAreInternal(@TempDir Path dir) {
        String injected = A + data("J", "type=\"char\" length=\"4\"", "input");
        String withInject = overlay("/x", "POST", "inject:\n  J: one\n", PA);
        String otherInject = overlay("/x", "POST", "inject:\n  J: two\n", PA);
        DiffResult injectedResult = same(dir.resolve("a"), injected, withInject, otherInject);
        assertThat(changes(injectedResult)).containsExactly("INTERNAL field settings");
        assertThat(injectedResult.apis().get(0).impact()).isEqualTo(Impact.INTERNAL);
        String pcml = data("R", "type=\"struct\" struct=\"ROW\"", "output");
        String visible = overlay("/x", "POST", "", "  R:\n    name: r\n    fields:\n      X: { name: x }\n      Y: { name: y }\n      H: { name: h }\n");
        String hidden = overlay("/x", "POST", "", "  R:\n    name: r\n    fields:\n      X: { name: x }\n      Y: { name: y }\n      H: { hidden: true }\n");
        assertThat(changes(same(dir.resolve("b"), pcml, visible, hidden))).contains("BREAKING response.r.h");
        // an internal field that stays internal: its name is invisible, its padding is internal
        String internalBefore = overlay("/x", "POST", "", PA + "  B: { direction: internal }\n");
        String renamed = overlay("/x", "POST", "", PA + "  B: { direction: internal, name: other }\n");
        assertThat(changes(same(dir.resolve("c"), AB, internalBefore, renamed))).isEmpty();
        String padded = overlay("/x", "POST", "", PA + "  B: { direction: internal, pad: left }\n");
        assertThat(changes(same(dir.resolve("d"), AB, internalBefore, padded))).containsExactly("INTERNAL field settings");
    }

    @Test
    void theImpactOfAnApiIsTheHighestImpactOfItsChanges(@TempDir Path dir) {
        String one = overlay("/x", "POST", "summary: One\n", PA + PB);
        String two = overlay("/x", "POST", "summary: Two\n", PA);
        DiffResult result = diff(dir, AB, one, A, two);
        assertThat(result.apis().get(0).impact()).isEqualTo(Impact.BREAKING);
        assertThat(changes(result)).contains("DOCUMENTATION documentation", "BREAKING request.b");
    }
}
