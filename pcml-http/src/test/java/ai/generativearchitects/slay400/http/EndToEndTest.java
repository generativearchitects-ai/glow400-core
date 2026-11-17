package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.mock.MockOutcome;
import ai.generativearchitects.slay400.mock.MockProgram;
import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.HttpMethod;
import ai.generativearchitects.slay400.pcml.api.MockDefinition;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.EncodeResult;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Without HTTP, from request parts to response bytes, for the {@code mocked} fixture: route, read, encode, the mock of the
 * program, decode, and the JSON response or the problem details, each against a golden file. Write the files again on purpose
 * with {@code -Dslay400.updateGolden=true} and review the diff.
 */
class EndToEndTest {

    private static final ApiRepositoryModel MODEL = Repos.model("mocked");
    private static final RouteTable TABLE = RouteTable.of(MODEL);

    /** What the gateway would answer. */
    private record Answer(int status, String contentType, String body) {
    }

    private static Answer problem(ProblemDetails problem) {
        return new Answer(problem.status(), ProblemDetails.CONTENT_TYPE, new String(problem.withContext("acme", "test", "req-0001").toJson(), StandardCharsets.UTF_8));
    }

    private static Answer exchange(HttpMethod method, String path, String contentType, String body) {
        RouteMatch match = TABLE.match(method, path);
        if (match instanceof RouteMatch.NotFound) {
            return problem(ProblemDetails.of(ProblemKind.NOT_FOUND, "No route matches this path."));
        }
        if (match instanceof RouteMatch.MethodNotAllowed) {
            return problem(ProblemDetails.of(ProblemKind.METHOD_NOT_ALLOWED, "The route does not take this method."));
        }
        RouteMatch.Found found = (RouteMatch.Found) match;
        ApiOperation operation = found.operation();
        HttpBinding binding = HttpBinding.of(operation);
        RequestResult read = binding.readRequest(new RequestParts(found.pathParameters(), Map.of(), Map.of(), Optional.ofNullable(contentType),
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)));
        if (read.values().isEmpty()) {
            return problem(ProblemDetails.forRequestProblems(read.problems()));
        }
        OperationCodec codec = OperationCodec.of(operation);
        EncodeResult encoded = codec.encode(read.values().get(), Map.of());
        if (encoded.parameters().isEmpty()) {
            return problem(ProblemDetails.forRequestProblems(encoded.problems()));
        }
        Optional<MockDefinition> mock = found.api().mocks().stream().filter(m -> m.operationId().equals(operation.id())).findFirst();
        MockProgram program = mock.isPresent() ? MockProgram.of(operation, mock.get()) : MockProgram.of(operation);
        MockOutcome outcome = program.call(encoded.parameters().get());
        if (outcome instanceof MockOutcome.Failed failed) {
            return problem(ProblemDetails.of(failed.kind().name().equals("TIMEOUT") ? ProblemKind.TIMEOUT : ProblemKind.UNAVAILABLE, "The program could not be called."));
        }
        DecodeResult decoded = codec.decode(((MockOutcome.Returned) outcome).output());
        if (decoded.error().isPresent()) {
            return problem(ProblemDetails.forBusinessError(decoded.error().get()));
        }
        HttpBinding.Success success = binding.success();
        return new Answer(success.status(), success.contentType().orElse(""), new String(binding.writeResponse(decoded.response().orElseThrow()), StandardCharsets.UTF_8));
    }

    private static void golden(String name, Answer answer) {
        Path file = Path.of("src/test/resources/golden/" + name + ".json");
        String text = answer.status() + " " + answer.contentType() + "\n" + answer.body() + "\n";
        try {
            if (Boolean.getBoolean("slay400.updateGolden")) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text);
            }
            assertThat(Files.readString(file)).as("golden file " + name + " (write it with -Dslay400.updateGolden=true)").isEqualTo(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aSuccessIsTheJsonOfTheResponseSchemaWithItsStatusAndContentType() {
        golden("ordstatus-shipped", exchange(HttpMethod.GET, "/orders/1001/status", null, null));
        golden("update-quantity-echo", exchange(HttpMethod.PUT, "/items/A1/quantity", "application/json; charset=utf-8", "{\"quantity\":5,\"note\":\"hello\"}"));
        assertThat(exchange(HttpMethod.GET, "/orders/1001/status", null, null).status()).isEqualTo(200);
        assertThat(exchange(HttpMethod.GET, "/orders/1001/status", null, null).contentType()).isEqualTo("application/json");
    }

    @Test
    void aBusinessErrorOfTheProgramIsTheStatusOfTheMappingWithTheProgramsCode() {
        Answer answer = exchange(HttpMethod.GET, "/orders/9999/status", null, null);
        golden("ordstatus-unknown-order", answer);
        assertThat(answer.status()).isEqualTo(404);
        assertThat(answer.contentType()).isEqualTo("application/problem+json");
    }

    @Test
    void aRequestThatCannotBeReadOrEncodedIsA400WithOneEntryForEachProblem() {
        golden("ordstatus-bad-number", exchange(HttpMethod.GET, "/orders/abc/status", null, null));
        golden("update-quantity-bad-body", exchange(HttpMethod.PUT, "/items/A1/quantity", "application/json", "{\"quantity\":5,\"extra\":1}"));
        golden("update-quantity-not-json", exchange(HttpMethod.PUT, "/items/A1/quantity", "application/json", "{\"quantity\":"));
        golden("update-quantity-too-long", exchange(HttpMethod.PUT, "/items/A1/quantity", "application/json", "{\"quantity\":" + "9".repeat(80) + "}"));
        assertThat(exchange(HttpMethod.PUT, "/items/A1/quantity", "application/json", "{\"quantity\":\"x\"").status()).isEqualTo(400);
    }

    @Test
    void anUnknownRouteAMethodThatTheRouteDoesNotHaveAndAProgramThatCannotBeCalledAreProblemsOfTheTable() {
        golden("route-not-found", exchange(HttpMethod.GET, "/nothing", null, null));
        golden("method-not-allowed", exchange(HttpMethod.POST, "/orders/1001/status", null, null));
        golden("program-unavailable", exchange(HttpMethod.GET, "/orders/6/status", null, null));
        assertThat(exchange(HttpMethod.GET, "/nothing", null, null).status()).isEqualTo(404);
        assertThat(exchange(HttpMethod.POST, "/orders/1001/status", null, null).status()).isEqualTo(405);
        assertThat(exchange(HttpMethod.GET, "/orders/6/status", null, null).status()).isEqualTo(503);
    }

    @Test
    void everyOperationOfTheFixtureIsReachableThroughTheRouteTable() {
        for (ApiDefinition api : MODEL.apis()) {
            for (ApiOperation operation : api.operations()) {
                String path = operation.route().replaceAll("\\{[^}]*}", "1");
                assertThat(TABLE.match(operation.method(), path)).isInstanceOf(RouteMatch.Found.class);
            }
        }
        assertThat(List.of(HttpMethod.values())).contains(HttpMethod.GET);
    }
}
