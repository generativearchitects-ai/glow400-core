package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.HttpMethod;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Routes: literal against placeholder, encodings, normalisation, methods, and a fuzz that never throws. */
class RouteTableTest {

    private static RouteMatch match(HttpMethod method, String path, String... routes) {
        return RouteTable.of(Repos.routes(routes)).match(method, path);
    }

    private static RouteMatch get(String path, String... routes) {
        return match(HttpMethod.GET, path, routes);
    }

    private static String id(RouteMatch match) {
        return ((RouteMatch.Found) match).operation().id();
    }

    private static Map<String, String> parameters(RouteMatch match) {
        return ((RouteMatch.Found) match).pathParameters();
    }

    @Test
    void aRouteIsFoundWithItsApiItsOperationAndTheValuesOfItsPlaceholdersInRouteOrder() {
        ApiRepositoryModel model = Repos.routes("GET /orders/{orderNumber}/lines/{line}");
        RouteMatch match = RouteTable.of(model).match(HttpMethod.GET, "/orders/1042/lines/7");
        assertThat(match).isInstanceOf(RouteMatch.Found.class);
        RouteMatch.Found found = (RouteMatch.Found) match;
        assertThat(found.api()).isEqualTo(model.apis().get(0));
        assertThat(found.operation().id()).isEqualTo("op0");
        assertThat(found.pathParameters()).containsExactly(Map.entry("orderNumber", "1042"), Map.entry("line", "7"));
        assertThatThrownBy(() -> found.pathParameters().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aLiteralSegmentWinsOverAPlaceholderAtTheSamePositionWhateverTheOrderOfTheRoutes() {
        for (String[] routes : new String[][] {{"GET /orders/{id}", "GET /orders/new"}, {"GET /orders/new", "GET /orders/{id}"}}) {
            assertThat(parameters(get("/orders/new", routes))).isEmpty();
            assertThat(parameters(get("/orders/7", routes))).containsExactly(Map.entry("id", "7"));
        }
        assertThat(id(get("/orders/new", "GET /orders/{id}", "GET /orders/new"))).isEqualTo("op1");
    }

    @Test
    void whenTheBestChoiceLeadsNowhereTheNextOneIsTriedAndTheLeftmostLiteralWinsBetweenTwoCompleteMatches() {
        assertThat(id(get("/a/c/y", "GET /a/c/x", "GET /a/{p}/y"))).isEqualTo("op1");
        assertThat(parameters(get("/a/c/y", "GET /a/c/x", "GET /a/{p}/y"))).containsExactly(Map.entry("p", "c"));
        assertThat(id(get("/a/c/b", "GET /a/{x}/b", "GET /a/c/{y}"))).isEqualTo("op1");
        assertThat(id(get("/a/d/b", "GET /a/{x}/b", "GET /a/c/{y}"))).isEqualTo("op0");
        assertThat(get("/a/c/z", "GET /a/c/x", "GET /a/{p}/y")).isInstanceOf(RouteMatch.NotFound.class);
    }

    @Test
    void aSegmentWithMoreLiteralTextWinsAndTextAroundAPlaceholderIsMatched() {
        assertThat(id(get("/f/xa", "GET /f/{id}", "GET /f/x{id}"))).isEqualTo("op1");
        assertThat(parameters(get("/f/xa", "GET /f/{id}", "GET /f/x{id}"))).containsExactly(Map.entry("id", "a"));
        assertThat(id(get("/f/ya", "GET /f/{id}", "GET /f/x{id}"))).isEqualTo("op0");
        assertThat(parameters(get("/files/report.json", "GET /files/{name}.json"))).containsExactly(Map.entry("name", "report"));
        assertThat(parameters(get("/files/a.json.json", "GET /files/{name}.json"))).containsExactly(Map.entry("name", "a.json"));
        assertThat(get("/files/.json", "GET /files/{name}.json")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/files/report.txt", "GET /files/{name}.json")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(parameters(get("/x/p-q-r", "GET /x/{a}-{b}"))).containsExactly(Map.entry("a", "p"), Map.entry("b", "q-r"));
        assertThat(get("/x/ab", "GET /x/{a}{b}")).as("two placeholders with no text between them match nothing (OVL021)").isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/x/a", "GET /x/{a}{b}")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/x/-b", "GET /x/{a}-{b}")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/x/a-", "GET /x/{a}-{b}")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(id(get("/x/pre-1-post", "GET /x/pre-{n}-post"))).isEqualTo("op0");
        assertThat(parameters(get("/x/pre-1-post", "GET /x/pre-{n}-post"))).containsExactly(Map.entry("n", "1"));
    }

    @Test
    void theRouteTemplatesAreMatchedWhateverTheMethodAndTheOtherMethodsAreReported() {
        String[] routes = {"GET /x", "POST /x", "DELETE /y"};
        assertThat(match(HttpMethod.POST, "/x", routes)).isInstanceOf(RouteMatch.Found.class);
        assertThat(match(HttpMethod.PUT, "/x", routes)).isEqualTo(new RouteMatch.MethodNotAllowed(Set.of(HttpMethod.GET, HttpMethod.POST)));
        assertThat(match(HttpMethod.GET, "/y", routes)).isEqualTo(new RouteMatch.MethodNotAllowed(Set.of(HttpMethod.DELETE)));
        // the first complete match decides, so a literal route without the method hides a placeholder route that has it
        assertThat(match(HttpMethod.GET, "/orders/new", "POST /orders/new", "GET /orders/{id}"))
                .isEqualTo(new RouteMatch.MethodNotAllowed(Set.of(HttpMethod.POST)));
        // routes that differ only in the names of their placeholders share a path, and each method has its own names
        RouteTable table = RouteTable.of(Repos.routes("GET /a/{x}", "POST /a/{y}"));
        assertThat(((RouteMatch.Found) table.match(HttpMethod.POST, "/a/1")).pathParameters()).containsExactly(Map.entry("y", "1"));
        assertThat(((RouteMatch.Found) table.match(HttpMethod.GET, "/a/1")).pathParameters()).containsExactly(Map.entry("x", "1"));
    }

    @Test
    void aPathThatIsNoRouteIsNotFound() {
        String[] routes = {"GET /orders/{n}"};
        assertThat(get("/other", routes)).isEqualTo(new RouteMatch.NotFound());
        assertThat(get("/orders", routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/orders/1/more", routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("", routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("orders/1", routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/", routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/orders/1", "GET orders/{n}")).isInstanceOf(RouteMatch.NotFound.class);
    }

    @Test
    void thePathParametersArePercentDecodedOnceAsUtf8() {
        String[] routes = {"GET /orders/{n}/status"};
        assertThat(parameters(get("/orders/%31%30/status", routes))).containsEntry("n", "10");
        assertThat(parameters(get("/orders/caf%C3%A9/status", routes))).containsEntry("n", "café");
        assertThat(parameters(get("/orders/%e2%82%ac/status", routes))).containsEntry("n", "€");
        assertThat(parameters(get("/orders/%F0%9F%98%80/status", routes))).containsEntry("n", "😀");
        assertThat(parameters(get("/orders/a+b/status", routes))).containsEntry("n", "a+b");
        assertThat(parameters(get("/orders/café/status", routes))).containsEntry("n", "café");
        assertThat(parameters(get("/orders/%2541/status", routes))).containsEntry("n", "%41"); // decoded once, not twice
        assertThat(parameters(get("/orders/a%20b/status", routes))).containsEntry("n", "a b");
        assertThat(parameters(get("/orders/%7B%7D/status", routes))).containsEntry("n", "{}");
        assertThat(id(get("/%6Frders/1/status", "GET /orders/{n}/status"))).isEqualTo("op0"); // a literal is compared decoded
    }

    @Test
    void hexadecimalDigitsAreAsciiAndAtTheEdgesOfTheirRanges() {
        String[] routes = {"GET /v/{x}"};
        for (String[] c : new String[][] {{"%2a", "*"}, {"%2A", "*"}, {"%6f", "o"}, {"%6F", "o"}, {"%39", "9"}, {"%30", "0"}, {"%3a", ":"}, {"%7e", "~"}, {"%20", " "}, {"%2e%2e%2e", "..."}}) {
            assertThat(parameters(get("/v/" + c[0], routes))).as(c[0]).containsEntry("x", c[1]);
        }
        for (String bad : new String[] {"%2G", "%2g", "%2z", "%G2", "%2/", "%2:", "%2@", "%2`", "%2[", "%/2", "%:2", "%g0", "%2 "}) {
            assertThat(get("/v/" + bad, routes)).as(bad).isInstanceOf(RouteMatch.NotFound.class);
        }
    }

    @Test
    void ofTwoPatternsTheOneWithMoreLiteralTextWinsWhereverTheTextIs() {
        assertThat(id(get("/p/1-xx2", "GET /p/{a}-{b}", "GET /p/{a}-xx{b}"))).isEqualTo("op1");
        assertThat(id(get("/p/1-xx2", "GET /p/{a}-xx{b}", "GET /p/{a}-{b}"))).isEqualTo("op0");
        assertThat(id(get("/p/axyz", "GET /p/{a}x", "GET /p/{a}xyz"))).isEqualTo("op1");
        assertThat(id(get("/p/axyz", "GET /p/{a}xyz", "GET /p/{a}x"))).isEqualTo("op0");
        assertThat(id(get("/p/bx", "GET /p/{a}xyz", "GET /p/{a}x"))).isEqualTo("op1");
        assertThat(id(get("/p/1-2-3-4", "GET /p/{a}-{b}-{c}-{d}"))).isEqualTo("op0");
        assertThat(parameters(get("/p/1-2-3-4", "GET /p/{a}-{b}-{c}-{d}"))).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"), Map.entry("c", "3"), Map.entry("d", "4"));
        assertThat(parameters(get("/p/xy1z", "GET /p/x{a}z"))).containsExactly(Map.entry("a", "y1"));
        assertThat(get("/p/xz", "GET /p/x{a}z")).isInstanceOf(RouteMatch.NotFound.class);
    }

    @Test
    void aSegmentThatDoesNotDecodeOrDecodesToASlashOrAControlCharacterMatchesNothing() {
        String[] routes = {"GET /orders/{n}", "GET /orders/{a}/{b}"};
        for (String bad : new String[] {"%2F", "%2f", "a%2Fb", "%00", "%0A", "%0D", "%1F", "%7F", "%80", "%C2%85", "%zz", "%1", "%", "a%", "%1G", "%G1",
                "%C3%28", "%C3", "%FF", "%ED%A0%80", "%E2%82", "%١١", "a\nb", "a\u0000b", "\ud800", "a\udc00b"}) {
            assertThat(get("/orders/" + bad, routes)).as(bad).isInstanceOf(RouteMatch.NotFound.class);
        }
        assertThat(get("/orders/1/%2F", routes)).isInstanceOf(RouteMatch.NotFound.class);
    }

    @Test
    void nothingIsNormalisedSoDotsEmptySegmentsAndSlashesMatchOnlyARouteThatHasThem() {
        String[] routes = {"GET /orders/{n}", "GET /orders/{n}/status", "GET /list/", "GET /", "GET /a/./b"};
        for (String path : new String[] {"/orders/..", "/orders/.", "/orders/%2e%2e", "/orders/%2E", "/orders//status", "//orders/1", "/orders/1/status/", "/orders/1//status",
                "/orders/", "/orders/1/", "/list", "/list//", "/a/b", "/a/../b", "/a//b"}) {
            assertThat(get(path, routes)).as(path).isInstanceOf(RouteMatch.NotFound.class);
        }
        assertThat(id(get("/list/", routes))).isEqualTo("op2");
        assertThat(id(get("/", routes))).isEqualTo("op3");
        assertThat(id(get("/a/./b", routes))).isEqualTo("op4");
        assertThat(id(get("/orders/1", routes))).isEqualTo("op0");
        assertThat(get("/orders/1/", "GET /orders/{n}/")).isInstanceOf(RouteMatch.Found.class);
        assertThat(id(get("/files/x..y", "GET /files/{n}"))).isEqualTo("op0");
        assertThat(get("/a/../b", "GET /a/../b")).isInstanceOf(RouteMatch.Found.class);
    }

    @Test
    void aSegmentIsAtMost4096CharactersAfterDecoding() {
        String[] routes = {"GET /v/{x}"};
        assertThat(get("/v/" + "a".repeat(RouteTable.MAX_SEGMENT_LENGTH), routes)).isInstanceOf(RouteMatch.Found.class);
        assertThat(get("/v/" + "a".repeat(RouteTable.MAX_SEGMENT_LENGTH + 1), routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/v/" + "%61".repeat(RouteTable.MAX_SEGMENT_LENGTH), routes)).isInstanceOf(RouteMatch.Found.class);
        assertThat(get("/v/" + "%61".repeat(RouteTable.MAX_SEGMENT_LENGTH + 1), routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/v/" + "%61".repeat(6 * RouteTable.MAX_SEGMENT_LENGTH), routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/v/" + "a".repeat(6 * RouteTable.MAX_SEGMENT_LENGTH + 1), routes)).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(parameters(get("/v/" + "%F0%9F%98%80".repeat(RouteTable.MAX_SEGMENT_LENGTH / 2), routes)).get("x")).hasSize(RouteTable.MAX_SEGMENT_LENGTH);
        assertThat(parameters(get("/v/" + "%C3%A9".repeat(RouteTable.MAX_SEGMENT_LENGTH), routes)).get("x")).hasSize(RouteTable.MAX_SEGMENT_LENGTH);
    }

    @Test
    void ofTwoRoutesWithTheSameShapeAndMethodTheFirstWinsAndAnythingThatIsNoRouteIsIgnored() {
        assertThat(id(get("/same/1", "GET /same/{id}", "GET /same/{orderNumber}"))).isEqualTo("op0");
        assertThat(get("/anything", "GET anything")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(RouteTable.of(Repos.routes()).match(HttpMethod.GET, "/")).isInstanceOf(RouteMatch.NotFound.class);
    }

    @Test
    void theMatchRecordsRefuseWhatIsNotValid() {
        assertThatThrownBy(() -> new RouteMatch.MethodNotAllowed(Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RouteMatch.MethodNotAllowed(null)).isInstanceOf(NullPointerException.class);
        RouteMatch.Found found = (RouteMatch.Found) get("/x", "GET /x");
        assertThatThrownBy(() -> new RouteMatch.Found(null, found.operation(), Map.of())).isInstanceOf(NullPointerException.class).hasMessage("api");
        assertThatThrownBy(() -> new RouteMatch.Found(found.api(), null, Map.of())).isInstanceOf(NullPointerException.class).hasMessage("operation");
        assertThatThrownBy(() -> new RouteMatch.Found(found.api(), found.operation(), java.util.Collections.singletonMap("a", null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RouteMatch.Found(found.api(), found.operation(), java.util.Collections.singletonMap(null, "a")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RouteTable.of(null)).isInstanceOf(NullPointerException.class).hasMessage("model");
        RouteTable table = RouteTable.of(Repos.routes("GET /x"));
        assertThatThrownBy(() -> table.match(null, "/x")).isInstanceOf(NullPointerException.class).hasMessage("method");
        assertThatThrownBy(() -> table.match(HttpMethod.GET, null)).isInstanceOf(NullPointerException.class).hasMessage("path");
    }

    @Test
    void aFuzzOverRandomPathsNeverThrowsAndOnlyFindsWhatTheRoutesAllow() {
        RouteTable table = RouteTable.of(Repos.routes("GET /orders/{n}/status", "POST /orders", "GET /files/{name}.json", "GET /a/{x}-{y}", "GET /"));
        String alphabet = "/%%%.0123456789abcdefABCDEFxyz-+ \né𐀀{}?#orders/status";
        Random random = new Random(20261117);
        int found = 0;
        for (int i = 0; i < 20_000; i++) {
            StringBuilder path = new StringBuilder(random.nextInt(4) == 0 ? "" : "/");
            for (int length = random.nextInt(24); length > 0; length--) {
                path.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            for (HttpMethod method : HttpMethod.values()) {
                RouteMatch match = table.match(method, path.toString());
                assertThat(match).isNotNull();
                if (match instanceof RouteMatch.Found f) {
                    found++;
                    assertThat(f.pathParameters().values()).allSatisfy(value -> assertThat(value).isNotEmpty().doesNotContain("/").isNotIn(".", ".."));
                }
            }
        }
        assertThat(found).isPositive();
        assertThat(List.of(HttpMethod.values())).hasSize(5);
    }

    // ---- matching a segment in linear time

    @Test
    void aSegmentOfTheLimitWithFourPlaceholdersCostsAFewTimesItsLengthInComparisons() {
        RouteTable.Template template = RouteTable.Template.of("{a}-{b}-{c}-{d}", new java.util.ArrayList<>());
        int length = RouteTable.MAX_SEGMENT_LENGTH;
        String[] segments = {"x".repeat(length), "x-".repeat(length / 2), "-".repeat(length), "a".repeat(length - 3) + "-.-", ".-".repeat(length / 2),
                "x-".repeat(length / 2 - 1) + "x.", "a".repeat(length / 2) + "-" + "b".repeat(length / 2 - 1)};
        for (String segment : segments) {
            assertThat(segment).hasSize(length);
            long[] steps = new long[1];
            template.match(segment, new java.util.ArrayList<>(), steps);
            assertThat(steps[0]).as("comparisons for " + segment.substring(0, 6) + "...").isLessThanOrEqualTo(3L * length);
        }
        // with text before and after, and a text of several characters
        RouteTable.Template wide = RouteTable.Template.of("pre{a}--{b}--{c}--{d}.json", new java.util.ArrayList<>());
        String body = "pre" + "-".repeat(length - 3 - 5);
        long[] steps = new long[1];
        assertThat(wide.match(body + ".json", new java.util.ArrayList<>(), steps)).isTrue();
        assertThat(steps[0]).isLessThanOrEqualTo(4L * length);
        steps = new long[1];
        String noDoubleDash = "pre" + "-x".repeat((length - 3 - 5) / 2) + ".json";
        assertThat(wide.match(noDoubleDash, new java.util.ArrayList<>(), steps)).isFalse();
        assertThat(steps[0]).isLessThanOrEqualTo(4L * length);
    }

    /** The search by backtracking that this table used before the review of PR 17: exhaustive and exponential, kept as the oracle. */
    private static boolean exhaustive(List<String> parts, int part, int position, String segment) {
        if (part == parts.size()) {
            return position == segment.length();
        }
        String current = parts.get(part);
        if (!current.equals("{}")) {
            return segment.startsWith(current, position) && exhaustive(parts, part + 1, position + current.length(), segment);
        }
        for (int end = position + 1; end <= segment.length(); end++) {
            String value = segment.substring(position, end);
            if (value.equals(".") || value.equals("..")) {
                continue;
            }
            if (exhaustive(parts, part + 1, end, segment)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theMatcherFindsAMatchExactlyWhenTheExhaustiveSearchDoes() {
        Random random = new Random(400_17);
        String alphabet = "ab.-";
        int matches = 0;
        int refusals = 0;
        for (int round = 0; round < 60_000; round++) {
            List<String> parts = new java.util.ArrayList<>();
            StringBuilder written = new StringBuilder();
            boolean placeholder = random.nextBoolean();
            for (int count = 1 + random.nextInt(5); count > 0; count--) {
                if (placeholder) {
                    parts.add("{}");
                    written.append("{p").append(parts.size()).append('}');
                } else {
                    String text = "";
                    for (int n = 1 + random.nextInt(2); n > 0; n--) {
                        text += alphabet.charAt(random.nextInt(alphabet.length()));
                    }
                    parts.add(text);
                    written.append(text);
                }
                placeholder = !placeholder;
            }
            StringBuilder segment = new StringBuilder();
            for (int n = random.nextInt(11); n > 0; n--) {
                segment.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            List<String> captures = new java.util.ArrayList<>();
            boolean found = RouteTable.Template.of(written.toString(), new java.util.ArrayList<>()).match(segment.toString(), captures);
            assertThat(found).as(written + " against '" + segment + "'").isEqualTo(exhaustive(parts, 0, 0, segment.toString()));
            if (found) {
                matches++;
                StringBuilder rebuilt = new StringBuilder();
                int next = 0;
                for (String part : parts) {
                    rebuilt.append(part.equals("{}") ? captures.get(next++) : part);
                }
                assertThat(rebuilt.toString()).isEqualTo(segment.toString());
                assertThat(captures).doesNotContain(".", "..", "");
            } else {
                refusals++;
                assertThat(captures).isEmpty();
            }
        }
        assertThat(matches).isGreaterThan(3_000);
        assertThat(refusals).isGreaterThan(3_000);
    }

    // ---- the matcher of a segment, input by input

    private static RouteTable.Template template(String segment) {
        return RouteTable.Template.of(segment, new java.util.ArrayList<>());
    }

    /** The captures of a match, or null; {@code steps[0]} holds the comparisons. */
    private static List<String> captures(String template, String segment, long[] steps) {
        List<String> captures = new java.util.ArrayList<>();
        return template(template).match(segment, captures, steps) ? captures : null;
    }

    @Test
    void theComparisonsOfFixedInputsAreCountedExactly() {
        long[] steps = new long[1];
        assertThat(captures("{a}-{b}-{c}-{d}", "x-x-x-x", steps)).containsExactly("x", "x", "x", "x");
        assertThat(steps[0]).as("one comparison for each dash found at once").isEqualTo(3);
        steps = new long[1];
        assertThat(captures("ab{x}cd", "abXcd", steps)).containsExactly("X");
        assertThat(steps[0]).as("the first text and the last text").isEqualTo(4);
        steps = new long[1];
        assertThat(captures("ab{x}", "abc", steps)).containsExactly("c");
        assertThat(steps[0]).isEqualTo(2);
        steps = new long[1];
        assertThat(captures("{x}cd", "abcd", steps)).containsExactly("ab");
        assertThat(steps[0]).isEqualTo(2);
        steps = new long[1];
        assertThat(captures("{a}--{b}", "x-y--z", steps)).containsExactly("x-y", "z");
        assertThat(steps[0]).as("'--' is compared at 1 (two), at 2 (one) and at 3 (two)").isEqualTo(1 + 1 + 1 + 1 + 1);
        steps = new long[1];
        assertThat(template("abc").match("abc", new java.util.ArrayList<>(), steps)).isTrue();
        assertThat(steps[0]).as("a segment without placeholders is compared as a whole").isEqualTo(3);
        steps = new long[1];
        assertThat(template("abc").match("abcd", new java.util.ArrayList<>(), steps)).isFalse();
        assertThat(steps[0]).isEqualTo(4);
    }

    @Test
    void aFailedMatchLeavesTheCapturesAsItFoundThemAndASuccessAddsToThem() {
        List<String> captures = new java.util.ArrayList<>(List.of("keep"));
        assertThat(template("{a}-{b}-{c}").match("x-y-.", captures)).isFalse();
        assertThat(captures).containsExactly("keep");
        assertThat(template("{a}-{b}-{c}").match("x-y", captures)).isFalse();
        assertThat(captures).containsExactly("keep");
        assertThat(template("{a}-{b}-{c}").match("x-y-z", captures)).isTrue();
        assertThat(captures).containsExactly("keep", "x", "y", "z");
        assertThat(template("ab{x}cd").match("abcd", captures)).isFalse();
        assertThat(captures).containsExactly("keep", "x", "y", "z");
    }

    @Test
    void everyTextLeavesACharacterForTheValueBeforeIt() {
        long[] steps = new long[1];
        assertThat(captures("{a}-{b}", "x-y", steps)).containsExactly("x", "y");
        assertThat(captures("{a}-{b}", "x-", steps)).as("nothing after the dash").isNull();
        assertThat(captures("{a}-{b}", "-y", steps)).as("nothing before the dash").isNull();
        assertThat(captures("{a}-{b}", "--", steps)).as("the dash at 1 would leave nothing after it").isNull();
    }

    @Test
    void theFirstAndTheLastTextAreAnchoredAndMayNotOverlapTheValues() {
        assertThat(captures("ab{x}cd", "abXcd", new long[1])).containsExactly("X");
        assertThat(captures("ab{x}cd", "abcd", new long[1])).as("no character for the value").isNull();
        assertThat(captures("ab{x}cd", "abXc", new long[1])).isNull();
        assertThat(captures("ab{x}cd", "aXcd", new long[1])).isNull();
        assertThat(captures("a{x}a", "a", new long[1])).isNull();
        assertThat(captures("a{x}a", "aa", new long[1])).isNull();
        assertThat(captures("a{x}a", "aXa", new long[1])).containsExactly("X");
        assertThat(captures("ab{x}bc", "abc", new long[1])).as("the texts overlap in 'abc'").isNull();
        assertThat(captures("ab{x}bc", "abXbc", new long[1])).containsExactly("X");
        assertThat(captures("ab{x}bc", "abbbc", new long[1])).containsExactly("b");
    }

    @Test
    void aValueOfDotOrTwoDotsMovesTheTextToItsNextOccurrenceAtEveryPlaceholder() {
        assertThat(captures("{a}-{b}-{c}", ".-x-y-z", new long[1])).as("first: '.'").containsExactly(".-x", "y", "z");
        assertThat(captures("{a}-{b}-{c}", "..-x-y-z", new long[1])).as("first: '..'").containsExactly("..-x", "y", "z");
        assertThat(captures("{a}-{b}-{c}", "x-.-y-z", new long[1])).as("middle: '.'").containsExactly("x", ".-y", "z");
        assertThat(captures("{a}-{b}-{c}", "x-..-y-z", new long[1])).as("middle: '..'").containsExactly("x", "..-y", "z");
        assertThat(captures("{a}-{b}-{c}", "x-y-.", new long[1])).as("last: '.'").isNull();
        assertThat(captures("{a}-{b}-{c}", "x-y-..", new long[1])).as("last: '..'").isNull();
        assertThat(captures("{a}-{b}-{c}", "x-y-.z", new long[1])).as("last: not only dots").containsExactly("x", "y", ".z");
        assertThat(captures("{a}-{b}", ".-x", new long[1])).as("no later place for the dash").isNull();
        assertThat(captures("{a}-{b}", "...-x", new long[1])).containsExactly("...", "x");
        assertThat(get("/d/.-x", "GET /d/{a}-{b}")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(parameters(get("/d/.-x-y", "GET /d/{a}-{b}"))).containsExactly(Map.entry("a", ".-x"), Map.entry("b", "y"));
    }

    @Test
    void theOrderOfTemplatesFollowsTheTextOfEachSegmentWhereverItSits() {
        assertThat(id(get("/q/1-xyz", "GET /q/{a}-{b}", "GET /q/{c}xyz"))).as("3 characters of text before 1").isEqualTo("op1");
        assertThat(id(get("/q/1-xyz", "GET /q/{c}xyz", "GET /q/{a}-{b}"))).isEqualTo("op0");
        assertThat(id(get("/r/1abx", "GET /r/{c}x", "GET /r/{a}ab{b}"))).as("2 characters of text before 1").isEqualTo("op1");
        assertThat(id(get("/r/1abx", "GET /r/{a}ab{b}", "GET /r/{c}x"))).isEqualTo("op0");
    }

    @Test
    void textThatIsNotUtf8AfterDecodingMatchesNothing() {
        assertThat(get("/a/%ff", "GET /a/{x}")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/a/%c3", "GET /a/{x}")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(get("/a/x%c3y", "GET /a/{x}")).isInstanceOf(RouteMatch.NotFound.class);
        assertThat(parameters(get("/a/%c3%a9", "GET /a/{x}"))).containsExactly(Map.entry("x", "\u00e9"));
    }
}
