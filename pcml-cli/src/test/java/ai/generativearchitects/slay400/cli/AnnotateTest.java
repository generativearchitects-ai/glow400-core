package ai.generativearchitects.slay400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.cli.Support.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code annotate}, in process, with the answers as scripted input. */
class AnnotateTest {

    static final String DIRECTION = "  direction [request/response/both/internal, Enter to skip]: ";
    static final String TYPE_CHAR = "  type [string/integer/decimal/boolean/date, Enter for string]: ";

    static String name(String current) {
        return "  name [Enter for " + current + "]: ";
    }

    /** A repository made by {@code init}. */
    static Path initialised(Path dir) {
        ScaffoldFixtures.pcmlOnly(dir);
        assertThat(Support.run(dir, "init").exit()).isZero();
        return dir;
    }

    private static final String ORDSTATUS_API = "apis/ORDSTATUS/api.yaml";

    // ---- the questions

    @Test
    void theQuestionsAreAskedInTheOrderOfTheModelAndShowTheWireTypeAndTheUsage(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "", "annotate");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).isEqualTo("GETBAL.DET  struct LINE  inputoutput\n" + DIRECTION + "\n");
    }

    @Test
    void aFullSessionWithEveryKindOfAnswer(@TempDir Path dir) {
        initialised(dir);
        String input = String.join("\n",
                "request", "", // GETBAL.DET: a struct has no type question
                "BOTH", "days",   // GETBAL.DAYS: its type has no alternative, so there is no type question
                "Req", "dec", "orderNumber", // ORDSTATUS.P0ORDN
                "internal") + "\n"; // ORDSTATUS.P0QTY
        Result result = Support.runWithInput(dir, input, "annotate");
        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo("""
                apis/GETBAL/api.yaml:5:5: warning OVL006: Operation getbal has no error mapping: a business error reported by the program is returned as a success
                apis/ORDSTATUS/api.yaml:5:5: warning OVL006: Operation ordstatus has no error mapping: a business error reported by the program is returned as a success
                0 errors, 2 warnings, 0 notes
                """);
        assertThat(result.err()).isEqualTo(
                "GETBAL.DET  struct LINE  inputoutput\n" + DIRECTION + name("det")
                        + "GETBAL.DAYS  int(4)  inputoutput\n" + DIRECTION + name("days")
                        + "GETBAL.DET  inputoutput -> request\nGETBAL.DAYS  inputoutput -> both\n"
                        + "ORDSTATUS.P0ORDN  char(10)  inputoutput\n" + DIRECTION + TYPE_CHAR + name("p0ordn")
                        + "ORDSTATUS.P0QTY  packed(7,2)  inputoutput\n" + DIRECTION
                        + "ORDSTATUS.P0ORDN  inputoutput -> request, decimal\nORDSTATUS.P0QTY  inputoutput -> internal\n");
    }

    @Test
    void theAnswersAreWrittenIntoTheEntries(@TempDir Path dir) {
        initialised(dir);
        Support.runWithInput(dir, "request\n\nBOTH\ndays\nReq\ndec\norderNumber\ninternal\n", "annotate");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains(
                "      P0ORDN: { name: orderNumber, direction: request, type: decimal }\n"
                        + "      P0STAT: { name: p0stat }\n"
                        + "      P0QTY: { name: p0qty, direction: internal }\n");
        assertThat(Support.read(dir.resolve("apis/GETBAL/api.yaml"))).contains("      DET: { name: det, direction: request }\n")
                .contains("      DAYS: { name: days, direction: both }\n");
    }

    @Test
    void skippingTheDirectionSkipsTheParameterAndNothingIsWritten(@TempDir Path dir) {
        initialised(dir);
        java.util.Map<String, String> before = texts(dir);
        Result result = Support.runWithInput(dir, "\n\n\n\n", "annotate");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).doesNotContain("->");
        assertThat(result.out()).contains("error OVL001");
        assertThat(texts(dir)).isEqualTo(before);
    }

    private static java.util.Map<String, String> texts(Path dir) {
        java.util.Map<String, String> files = new java.util.TreeMap<>();
        for (String name : Support.names(dir)) {
            if (java.nio.file.Files.isRegularFile(dir.resolve(name))) {
                files.put(name, Support.read(dir.resolve(name)));
            }
        }
        return files;
    }

    @Test
    void aSkippedParameterDoesNotStopTheNextOnesFromBeingAsked(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "\nr\nrequest\n\n\n", "annotate", "--api", "ORDSTATUS");
        // P0ORDN skipped; P0QTY: 'r' is ambiguous, then request
        assertThat(result.err()).contains("ORDSTATUS.P0QTY  inputoutput -> request\n").doesNotContain("P0ORDN  inputoutput ->");
        assertThat(result.err()).contains("  Please answer request, response, both or internal, or the beginning of one of them.\n");
    }

    @Test
    void anInvalidAnswerRepeatsTheQuestion(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "sideways\nreq\nnumber\nstring\nfoo\u0001bar\nok name\n\nrequest\n", "annotate", "--api", "ORDSTATUS");
        String hint = "  Please answer request, response, both or internal, or the beginning of one of them.\n";
        assertThat(result.err()).startsWith("ORDSTATUS.P0ORDN  char(10)  inputoutput\n" + DIRECTION + hint + DIRECTION + TYPE_CHAR
                + "  Please answer string, integer, decimal, boolean, date, or the beginning of one of them.\n" + TYPE_CHAR
                + name("p0ordn") + "  A name must not be blank or have control characters.\n" + name("p0ordn"));
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("P0ORDN: { name: \"ok name\", direction: request }");
    }

    @Test
    void answersMayBeInAnyCaseAndShortenedToAnUnambiguousPrefix(@TempDir Path dir) {
        initialised(dir);
        // GETBAL: DET resp, DAYS int/internal.. ; ORDSTATUS: P0ORDN 'RESPO' , P0QTY 'i'
        Result result = Support.runWithInput(dir, "RESP\n\nI\nrespo\n\n\nB\n\n", "annotate");
        assertThat(result.err()).contains("GETBAL.DET  inputoutput -> response\n").contains("GETBAL.DAYS  inputoutput -> internal\n")
                .contains("ORDSTATUS.P0ORDN  inputoutput -> response\n").contains("ORDSTATUS.P0QTY  inputoutput -> both\n");
        assertThat(result.exit()).isZero();
    }

    @Test
    void theDefaultTypeIsNotWrittenWhenItIsTyped(@TempDir Path dir) {
        initialised(dir);
        Support.runWithInput(dir, "request\nstr\n\n", "annotate", "--api", "ORDSTATUS");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("      P0ORDN: { name: p0ordn, direction: request }\n");
    }

    @Test
    void theNameIsNotWrittenWhenItIsTheDefault(@TempDir Path dir) {
        initialised(dir);
        Support.runWithInput(dir, "request\n\n  p0ordn  \n", "annotate", "--api", "ORDSTATUS");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("      P0ORDN: { name: p0ordn, direction: request }\n");
    }

    @Test
    void theNameQuestionShowsTheNameThatIsInTheFile(@TempDir Path dir) {
        initialised(dir);
        Path api = dir.resolve(ORDSTATUS_API);
        Support.write(api, Support.read(api).replace("P0ORDN: { name: p0ordn }", "P0ORDN: { name: \"order\\e[2J\\u202eno\" }"));
        Result result = Support.runWithInput(dir, "request\n\n", "annotate", "--api", "ORDSTATUS");
        assertThat(result.err()).contains("  name [Enter for order\\u001b[2J\\u202eno]: ");
        assertThat(result.err()).doesNotContain("\u001b").doesNotContain("‮");
    }

    @Test
    void aStructGetsNoTypeQuestionAndInternalGetsNoTypeOrNameQuestion(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "both\n\ninternal\n", "annotate", "--api", "GETBAL");
        assertThat(result.err()).startsWith("GETBAL.DET  struct LINE  inputoutput\n" + DIRECTION + name("det") + "GETBAL.DAYS  int(4)  inputoutput\n" + DIRECTION);
        assertThat(result.err()).doesNotContain("type [").contains("GETBAL.DAYS  inputoutput -> internal");
    }

    // ---- the end of the input

    @Test
    void theEndOfTheInputKeepsWhatWasAnswered(@TempDir Path dir) {
        initialised(dir);
        // GETBAL.DET answered completely; the input ends at the direction of GETBAL.DAYS
        Result result = Support.runWithInput(dir, "request\n\n", "annotate");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).isEqualTo("GETBAL.DET  struct LINE  inputoutput\n" + DIRECTION + name("det")
                + "GETBAL.DAYS  int(4)  inputoutput\n" + DIRECTION + "\n" + "GETBAL.DET  inputoutput -> request\n");
        assertThat(Support.read(dir.resolve("apis/GETBAL/api.yaml"))).contains("DET: { name: det, direction: request }").contains("DAYS: { name: days }  # inputoutput:");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("P0ORDN: { name: p0ordn }  # inputoutput:");
    }

    @Test
    void theEndOfTheInputAfterTheDirectionKeepsTheDirection(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "request", "annotate", "--api", "ORDSTATUS");
        assertThat(result.err()).isEqualTo("ORDSTATUS.P0ORDN  char(10)  inputoutput\n" + DIRECTION + TYPE_CHAR + "\n" + "ORDSTATUS.P0ORDN  inputoutput -> request\n");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("P0ORDN: { name: p0ordn, direction: request }");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("P0QTY: { name: p0qty }  # inputoutput:");
    }

    @Test
    void theEndOfTheInputAtTheNameKeepsTheDirectionAndTheType(@TempDir Path dir) {
        initialised(dir);
        Support.runWithInput(dir, "response\ninteger", "annotate", "--api", "ORDSTATUS");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("P0ORDN: { name: p0ordn, direction: response, type: integer }");
    }

    @Test
    void noInputAtAllAsksTheFirstQuestionAndWritesNothing(@TempDir Path dir) {
        initialised(dir);
        java.util.Map<String, String> before = texts(dir);
        assertThat(Support.runWithInput(dir, "", "annotate").exit()).isEqualTo(1);
        assertThat(texts(dir)).isEqualTo(before);
    }

    @Test
    void theLinesOfAFileAreSaidAfterItIsWritten(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "request\n\n\nboth\n\n", "annotate", "--api", "ORDSTATUS");
        assertThat(result.err()).endsWith("ORDSTATUS.P0ORDN  inputoutput -> request\nORDSTATUS.P0QTY  inputoutput -> both\n");
        assertThat(result.err()).doesNotContain("would set");
    }

    // ---- --api, --dry-run

    @Test
    void apiLimitsTheQuestionsToOneFolderWhateverTheCase(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "request\n\n\n", "annotate", "--api", "ordstatus");
        assertThat(result.err()).startsWith("ORDSTATUS.P0ORDN").doesNotContain("GETBAL");
        assertThat(Support.read(dir.resolve("apis/GETBAL/api.yaml"))).contains("# inputoutput:");
    }

    @Test
    void aFolderThatIsNotThereIsAUsageError(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "request\n", "annotate", "--api", "NOPE");
        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).isEqualTo("pcml-cli: There is no folder 'NOPE' under apis/. Try 'pcml-cli --help'.\n".replace("pcml-cli", ai.generativearchitects.slay400.emit.descriptor.Tool.NAME));
        assertThat(result.out()).isEmpty();
        assertThat(Support.run(dir.resolve("apis"), "annotate", "--repo", "..", "--api", "a/b").exit()).isEqualTo(2);
        Path empty = dir.resolve("empty");
        empty.toFile().mkdirs();
        assertThat(Support.run(empty, "annotate", "--api", "x").exit()).isEqualTo(2);
    }

    @Test
    void dryRunAsksAndSaysWhatItWouldDoButWritesNothing(@TempDir Path dir) {
        initialised(dir);
        java.util.Map<String, String> before = texts(dir);
        Result result = Support.runWithInput(dir, "request\n\nboth\n\nrequest\n\n\nresponse\n\n", "annotate", "--dry-run");
        assertThat(result.err()).contains("would set GETBAL.DET  inputoutput -> request\n").contains("would set ORDSTATUS.P0QTY  inputoutput -> response\n");
        assertThat(result.err().lines().filter(l -> l.contains("->")).map(l -> l.substring(l.indexOf("would set ") < 0 ? 0 : l.indexOf("would set "))))
                .isNotEmpty().allMatch(l -> l.startsWith("would set "));
        assertThat(texts(dir)).isEqualTo(before);
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("error OVL001");
    }

    @Test
    void aFileWithNoAcceptedEditIsNotTouched(@TempDir Path dir) throws IOException {
        initialised(dir);
        Path file = dir.resolve("apis/GETBAL/api.yaml");
        java.nio.file.attribute.FileTime time = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, time);
        Support.runWithInput(dir, "\n\n", "annotate", "--api", "GETBAL");
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(time);
    }

    // ---- the repository must be fit to annotate

    @Test
    void anotherErrorStopsBeforeTheFirstQuestionWithTheReport(@TempDir Path dir) {
        initialised(dir);
        Path api = dir.resolve("apis/GETBAL/api.yaml");
        Support.write(api, Support.read(api) + "bogus: 1\n");
        java.util.Map<String, String> before = texts(dir);
        Result result = Support.runWithInput(dir, "request\nrequest\nrequest\n", "annotate");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).contains("apis/GETBAL/api.yaml:").contains("error OVL011: Unknown key 'bogus'").contains("error OVL001");
        assertThat(texts(dir)).isEqualTo(before);
    }

    @Test
    void aBrokenGatewayYamlStopsToo(@TempDir Path dir) {
        initialised(dir);
        Support.write(dir.resolve("gateway.yaml"), "defaults: [");
        Result result = Support.runWithInput(dir, "request\n", "annotate");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).isEmpty();
        assertThat(result.out()).contains("gateway.yaml:").contains("OVL010");
    }

    @Test
    void aRepositoryThatDoesNotExistStopsWithTheReport(@TempDir Path dir) {
        Result result = Support.runWithInput(dir, "", "annotate", "--repo", "nope");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).contains("OVL013");
    }

    @Test
    void anOperationWithOtherErrorsOfItsOwnStopsAlso(@TempDir Path dir) {
        initialised(dir);
        Path api = dir.resolve(ORDSTATUS_API);
        Support.write(api, Support.read(api).replace("method: POST", "method: POST\n    route2: x"));
        assertThat(Support.runWithInput(dir, "request\n", "annotate").out()).contains("OVL011");
    }

    @Test
    void nothingToAnnotateIsSaidAndTheRepositoryIsValidated(@TempDir Path dir) {
        initialised(dir);
        Support.runWithInput(dir, "request\n\nrequest\n\nrequest\n\n\nrequest\n\n\n".replace("request\n\nrequest\n\nrequest", "request\n\nboth\n\nrequest"), "annotate");
        Result again = Support.runWithInput(dir, "request\n", "annotate");
        assertThat(again.err()).isEqualTo("nothing to annotate\n");
        assertThat(again.exit()).isZero();
        assertThat(again.out()).contains("OVL006");
    }

    @Test
    void whenTheRepositoryOnlyWarnsAboutInputoutputTheParametersAreStillAsked(@TempDir Path dir) {
        initialised(dir);
        Support.write(dir.resolve("gateway.yaml"), "defaults:\n  inputoutput: both\n");
        Result result = Support.runWithInput(dir, "request\n\n\n", "annotate", "--api", "ORDSTATUS");
        assertThat(result.err()).contains("ORDSTATUS.P0ORDN  inputoutput -> request");
    }

    // ---- the editor's answers

    @Test
    void anEntryThatCannotBeEditedIsLeftAloneAndReportedWithScf001(@TempDir Path dir) {
        initialised(dir);
        Path api = dir.resolve(ORDSTATUS_API);
        Support.write(api, Support.read(api).replace("P0ORDN: { name: p0ordn }", "P0ORDN: &a { name: p0ordn }"));
        Result result = Support.runWithInput(dir, "request\n\n\nrequest\n\n\n", "annotate", "--api", "ORDSTATUS");
        assertThat(result.out()).contains("apis/ORDSTATUS/api.yaml:10:7: warning SCF001: Operation 'ordstatus', parameter 'P0ORDN' was not edited: anchor or alias; edit it by hand");
        assertThat(result.err()).contains("ORDSTATUS.P0QTY  inputoutput -> request\n").doesNotContain("P0ORDN  inputoutput ->");
        assertThat(Support.read(api)).contains("P0ORDN: &a { name: p0ordn }  # inputoutput:").contains("P0QTY: { name: p0qty, direction: request }");
        assertThat(result.exit()).isEqualTo(1);
    }

    @Test
    void aRefusedEditIsNotWrittenAndIsReportedWithItsOperationAndParameter(@TempDir Path dir) {
        initialised(dir);
        java.util.Map<String, String> before = texts(dir);
        // P0ORDN as a response named p0stat clashes with P0STAT, which is a response
        Result result = Support.runWithInput(dir, "response\n\np0stat\n", "annotate", "--api", "ORDSTATUS");
        assertThat(result.out()).contains("apis/ORDSTATUS/api.yaml:1:1: error SCF002: Operation 'ordstatus', parameter 'P0ORDN' was not edited: "
                + "the edited file was refused: the new text gives a diagnostic that the old one did not (OVL014)\n");
        assertThat(result.err()).doesNotContain("->");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(texts(dir)).isEqualTo(before);
    }

    @Test
    void oneBadAnswerAmongGoodOnesCostsOnlyThatAnswer(@TempDir Path dir) {
        initialised(dir);
        // P0ORDN: response named p0stat (refused, it clashes with P0STAT); P0QTY: both (good)
        Result result = Support.runWithInput(dir, "response\n\np0stat\nboth\n\n", "annotate", "--api", "ORDSTATUS");
        assertThat(result.out()).contains("error SCF002: Operation 'ordstatus', parameter 'P0ORDN' was not edited").doesNotContain("P0QTY' was not");
        assertThat(result.err()).contains("ORDSTATUS.P0QTY  inputoutput -> both\n").doesNotContain("P0ORDN  inputoutput ->");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("P0QTY: { name: p0qty, direction: both }\n")
                .contains("P0ORDN: { name: p0ordn }  # inputoutput:");
        assertThat(result.exit()).isEqualTo(1);
    }

    @Test
    void theEditsAreTriedInTheOrderTheyWereAnswered(@TempDir Path dir) {
        initialised(dir);
        // both parameters of GETBAL are requests named x: the first answer is kept, the second is the one that clashes
        Result result = Support.runWithInput(dir, "request\nx\nrequest\nx\n", "annotate", "--api", "GETBAL");
        assertThat(result.out()).contains("Operation 'getbal', parameter 'DAYS' was not edited").doesNotContain("parameter 'DET' was not");
        assertThat(Support.read(dir.resolve("apis/GETBAL/api.yaml"))).contains("DET: { name: x, direction: request }").contains("DAYS: { name: days }  # inputoutput:");
    }

    @Test
    void everyRefusedEditIsReportedOnce(@TempDir Path dir) {
        initialised(dir);
        // P0ORDN and P0QTY both ask for the response name of P0STAT
        Result result = Support.runWithInput(dir, "response\n\np0stat\nresponse\np0stat\n", "annotate", "--api", "ORDSTATUS");
        assertThat(result.out().lines().filter(l -> l.contains("error SCF002")).toList()).hasSize(2);
        assertThat(result.out()).contains("parameter 'P0ORDN' was not edited").contains("parameter 'P0QTY' was not edited");
    }

    // ---- what is kept

    @Test
    void aHandWrittenOverlayKeepsItsCommentsOrderQuotingAndLineEndings(@TempDir Path dir) {
        Support.write(dir.resolve("gateway.yaml"), "defaults:\n  inputoutput: fail\n");
        Support.write(dir.resolve("apis/ORDSTATUS/ORDSTATUS.pcml"), ScaffoldFixtures.ORDSTATUS);
        String hand = """
                # Orders. Written by hand.
                pcml: "ORDSTATUS.pcml"
                version: 1.2.0   # keep this
                title: 'Order status'

                program: ORDSTATUS
                route: /orders/{orderNumber}/status
                method: GET
                parameters:
                    # the key
                    P0ORDN:
                        in: path
                        name: orderNumber   # the number
                        description: "The order"

                    P0STAT: {  name:  status }
                summary: Status of an order
                """.replace("\n", "\r\n");
        Support.write(dir.resolve(ORDSTATUS_API), hand);
        Result result = Support.runWithInput(dir, "request\n\n\nresponse\n\n\n", "annotate");
        assertThat(result.exit()).isZero();
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).isEqualTo(("""
                # Orders. Written by hand.
                pcml: "ORDSTATUS.pcml"
                version: 1.2.0   # keep this
                title: 'Order status'

                program: ORDSTATUS
                route: /orders/{orderNumber}/status
                method: GET
                parameters:
                    # the key
                    P0ORDN:
                        in: path
                        name: orderNumber   # the number
                        description: "The order"
                        direction: request

                    P0STAT: {  name:  status }
                    P0QTY: { direction: response }
                summary: Status of an order
                """).replace("\n", "\r\n"));
    }

    // ---- standard output

    @Test
    void standardOutputIsOnlyTheReportInEveryFormat(@TempDir Path dir) {
        initialised(dir);
        Result json = Support.runWithInput(dir, "request\n\nboth\n\n\nrequest\n\n\nrequest\n\n\n", "annotate", "--format", "json");
        assertThat(json.out()).startsWith("{").endsWith("}\n").doesNotContain("direction [").doesNotContain("->");
        assertThat(json.err()).contains("direction [").contains("->");
        initialised(dir.resolve("again"));
        Result sarif = Support.runWithInput(dir.resolve("again"), "", "annotate", "--format=sarif");
        assertThat(sarif.out()).startsWith("{").doesNotContain("direction [");
        Result stopped = Support.runWithInput(dir, "", "annotate", "--format", "json", "--repo", "nope");
        assertThat(stopped.out()).startsWith("{").endsWith("}\n");
    }

    @Test
    void theReportCanGoToAFileWhileTheQuestionsStayOnStandardError(@TempDir Path dir) {
        initialised(dir);
        Result result = Support.runWithInput(dir, "request\n\n", "annotate", "--api", "GETBAL", "--report", "report.txt");
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("direction [").endsWith("report written to report.txt\n");
        assertThat(Support.read(dir.resolve("report.txt"))).contains("OVL001");
    }

    // ---- a file that changes while the questions are asked

    @Test
    void aFileChangedWhileTheQuestionsAreAskedIsNotWrittenAndCli005SaysSo(@TempDir Path dir) {
        initialised(dir);
        Path api = dir.resolve(ORDSTATUS_API);
        // P0ORDN is answered (lines 0 to 2); before the direction of P0QTY is read, the file is changed from outside
        java.io.InputStream in = Support.linesWithHook("request\n\n\nboth\n\n", 3,
                () -> Support.write(api, Support.read(api) + "# written in an editor\n"));
        String changed = Support.read(api) + "# written in an editor\n";
        Result result = Support.runWithStream(dir, in, "annotate", "--api", "ORDSTATUS");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(Support.read(api)).isEqualTo(changed);
        assertThat(result.out()).contains("apis/ORDSTATUS/api.yaml:1:1: warning CLI005: The file apis/ORDSTATUS/api.yaml was changed while the questions were asked, so nothing was written to it\n");
        assertThat(result.err()).doesNotContain("->");
    }

    @Test
    void theNextFileIsStillWrittenWhenOneChanged(@TempDir Path dir) {
        initialised(dir);
        Path getbal = dir.resolve("apis/GETBAL/api.yaml");
        // GETBAL.DET (lines 0, 1), GETBAL.DAYS (2, 3); ORDSTATUS follows. GETBAL changes before its second parameter.
        java.io.InputStream in = Support.linesWithHook("request\n\nboth\n\nrequest\n\n\nboth\n\n", 2,
                () -> Support.write(getbal, Support.read(getbal) + "# changed\n"));
        Result result = Support.runWithStream(dir, in, "annotate");
        assertThat(result.exit()).isEqualTo(1); // GETBAL still has parameters without a direction
        assertThat(result.out()).contains("warning CLI005").contains("apis/GETBAL/api.yaml:").doesNotContain("apis/ORDSTATUS/api.yaml:10");
        assertThat(Support.read(getbal)).contains("# changed").contains("# inputoutput:").doesNotContain(", direction");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("direction: request").contains("direction: both");
    }

    @Test
    void anUnchangedFileIsWrittenAsBefore(@TempDir Path dir) {
        initialised(dir);
        java.io.InputStream in = Support.linesWithHook("request\n\n\nboth\n\n", 3, () -> { });
        assertThat(Support.runWithStream(dir, in, "annotate", "--api", "ORDSTATUS").out()).doesNotContain("CLI005");
        assertThat(Support.read(dir.resolve(ORDSTATUS_API))).contains("direction: request").contains("direction: both");
    }

    // ---- writing

    @Test
    void aFileThatCannotBeWrittenIsCli003AndTheRunStops(@TempDir Path dir) throws IOException {
        initialised(dir);
        Path folder = dir.resolve("apis/GETBAL");
        try {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("no POSIX permissions here");
        }
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(folder), "the folder is writable anyway (running as root?)");
            Result result = Support.runWithInput(dir, "request\n\nboth\n\n\nrequest\n\n\n", "annotate");
            assertThat(result.exit()).isEqualTo(1);
            assertThat(result.out()).startsWith("apis/GETBAL/api.yaml:1:1: error CLI003: The file apis/GETBAL/api.yaml could not be written: ");
            assertThat(result.err()).doesNotContain("ORDSTATUS").doesNotContain("->");
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void anOverlayThatLeadsOutOfTheRepositoryIsNotWritten(@TempDir Path dir) throws IOException {
        Path repo = initialised(dir.resolve("repo"));
        Path outside = dir.resolve("outside.yaml");
        Path api = repo.resolve("apis/GETBAL/api.yaml");
        Files.move(api, outside);
        try {
            Files.createSymbolicLink(api, outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        String before = Support.read(outside);
        Result result = Support.runWithInput(dir, "request\n\n", "annotate", "--api", "GETBAL", "--repo", "repo");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).startsWith("repo/apis/GETBAL/api.yaml:1:1: error CLI003: The file repo/apis/GETBAL/api.yaml could not be written: it leads out of the repository\n");
        assertThat(result.err()).doesNotContain("->");
        assertThat(Support.read(outside)).isEqualTo(before);
        assertThat(Files.isSymbolicLink(api)).isTrue();
    }

    @Test
    void permissionsAreKeptWhenAFileIsReplaced(@TempDir Path dir) throws IOException {
        initialised(dir);
        Path api = dir.resolve(ORDSTATUS_API);
        try {
            Files.setPosixFilePermissions(api, PosixFilePermissions.fromString("rw-r-----"));
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("no POSIX permissions here");
        }
        Support.runWithInput(dir, "request\n\n\n", "annotate", "--api", "ORDSTATUS");
        assertThat(Files.getPosixFilePermissions(api)).isEqualTo(PosixFilePermissions.fromString("rw-r-----"));
        assertThat(Support.names(dir).stream().filter(n -> n.contains(".tmp"))).isEmpty();
    }
}
