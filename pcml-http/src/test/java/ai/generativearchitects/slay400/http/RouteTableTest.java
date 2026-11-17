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
        assertThat(parameters(get("/x/ab", "GET /x/{a}{b}"))).containsExactly(Map.entry("a", "a"), Map.entry("b", "b"));
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
}
