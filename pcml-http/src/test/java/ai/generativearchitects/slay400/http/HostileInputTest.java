package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.contract.FieldSurface;
import ai.generativearchitects.slay400.contract.OperationSurface;
import ai.generativearchitects.slay400.emit.descriptor.JsonReader;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.Location;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Random hostile requests: nothing is ever thrown, and no value that the caller sent comes back in a problem. */
class HostileInputTest {

    private static final String SENTINEL = "SENTINEL-4711";
    private static final String[] CONTENT_TYPES = {"application/json", "application/json; charset=utf-8", "text/plain", "application/json; charset=utf-16", "", "garbage;;;", "application/json;"};
    private static final JsonReader.Limits CHECK = new JsonReader.Limits(10_000_000, 1000, 10_000_000, 10_000_000);

    private static String junk(Random random) {
        String alphabet = "\"\\{}[],:0123456789.eE+-truefalsn \n\t\u00e9\u0000\u001f%/<>'";
        StringBuilder out = new StringBuilder(random.nextBoolean() ? SENTINEL : "");
        for (int length = random.nextInt(12); length > 0; length--) {
            out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return out.append(random.nextBoolean() ? SENTINEL : "").toString();
    }

    private static String jsonValue(Random random, int depth) {
        switch (random.nextInt(depth > 40 ? 6 : 9)) {
            case 0: return "\"" + SENTINEL + "\"";
            case 1: return "\"" + SENTINEL + "\\ud800\"";
            case 2: return random.nextBoolean() ? "1" + "0".repeat(random.nextInt(60)) : "-" + SENTINEL.length() + "." + "5".repeat(random.nextInt(50));
            case 3: return random.nextBoolean() ? "true" : "null";
            case 4: return "\"" + junk(random).replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\p{Cntrl}]", "") + "\"";
            case 5: return "1e" + random.nextInt(1_000_000_000);
            case 6: return "[" + jsonValue(random, depth + 1) + "," + jsonValue(random, depth + 1) + "]";
            case 7: return "{\"" + junkName(random) + "\":" + jsonValue(random, depth + 1) + ",\"" + junkName(random) + "\":" + jsonValue(random, depth + 1) + "}";
            default: return "[".repeat(1 + random.nextInt(200)) + "]".repeat(random.nextInt(200));
        }
    }

    private static String junkName(Random random) {
        return "k" + random.nextInt(5);
    }

    private static byte[] body(Random random, List<String> names) {
        switch (random.nextInt(8)) {
            case 0: {
                byte[] bytes = new byte[random.nextInt(40)];
                random.nextBytes(bytes);
                return bytes;
            }
            case 1: return junk(random).getBytes(StandardCharsets.UTF_8);
            case 2: return ("\uFEFF{\"" + names.get(0) + "\":\"" + SENTINEL + "\"}").getBytes(StandardCharsets.UTF_8);
            case 3: return ("{\"" + names.get(0) + "\":\"" + SENTINEL + "\",\"" + names.get(0) + "\":\"" + SENTINEL + "\"}").getBytes(StandardCharsets.UTF_8);
            case 4: return jsonValue(random, 0).getBytes(StandardCharsets.UTF_16LE);
            default: {
                StringBuilder object = new StringBuilder("{");
                for (String name : names) {
                    if (random.nextInt(3) > 0) {
                        object.append(object.length() > 1 ? "," : "").append("\"").append(name).append("\":").append(jsonValue(random, 0));
                    }
                }
                if (random.nextInt(4) == 0) {
                    object.append(object.length() > 1 ? "," : "").append("\"unknown").append(random.nextInt(9)).append("\":").append(jsonValue(random, 0));
                }
                return (object + (random.nextInt(8) == 0 ? "" : "}")).getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    void nothingIsThrownAndNoValueOfTheCallerComesBackInAProblemOverRandomRequestsToEveryOperationOfTheFixtures() throws Exception {
        Random random = new Random(20261117);
        int requests = 0;
        int refused = 0;
        int read = 0;
        for (String name : Repos.VALID) {
            for (ApiOperation operation : Repos.model(name).operations()) {
                HttpBinding binding = HttpBinding.of(operation, new ReadLimits(2_000, 30, 400));
                OperationSurface surface = OperationSurface.of(operation);
                List<String> bodyNames = new ArrayList<>();
                for (FieldSurface field : surface.request()) {
                    if (field.location().get() == Location.BODY) {
                        bodyNames.add(field.exposedName());
                    }
                }
                if (bodyNames.isEmpty()) {
                    bodyNames.add("none");
                }
                OperationCodec codec = OperationCodec.of(operation);
                for (int i = 0; i < 250; i++) {
                    Map<String, String> path = new LinkedHashMap<>();
                    Map<String, List<String>> query = new LinkedHashMap<>();
                    Map<String, List<String>> headers = new LinkedHashMap<>();
                    for (FieldSurface field : surface.request()) {
                        List<String> values = new ArrayList<>();
                        for (int count = random.nextInt(3); count > 0; count--) {
                            values.add(junk(random));
                        }
                        switch (field.location().get()) {
                            case PATH -> path.put(field.exposedName(), junk(random));
                            case QUERY -> query.put(field.exposedName(), values);
                            case HEADER -> headers.put(field.exposedName(), values);
                            default -> { }
                        }
                    }
                    String type = random.nextInt(4) == 0 ? CONTENT_TYPES[random.nextInt(CONTENT_TYPES.length)] : "application/json";
                    RequestResult result = binding.readRequest(new RequestParts(path, query, headers, Optional.of(type), body(random, bodyNames)));
                    requests++;
                    assertThat(result.values().isPresent()).isEqualTo(result.problems().isEmpty());
                    for (ValueProblem problem : result.problems()) {
                        String shown = problem.message() + problem.fieldPath() + (problem.code().equals("REQ006") ? "" : problem.exposedPath());
                        assertThat(shown).as(problem.toString()).doesNotContain(SENTINEL);
                    }
                    String body = new String(ProblemDetails.forRequestProblems(result.problems()).withContext("t", "e", "c").toJson(), StandardCharsets.UTF_8);
                    assertThat(body).doesNotContain(SENTINEL);
                    new JsonReader.Limits(1, 1, 1, 1);
                    assertThat(JsonReader.of(CHECK).read(new StringReader(body))).isInstanceOf(Map.class);
                    if (result.values().isPresent()) {
                        read++;
                        codec.encode(result.values().get(), Map.of());
                    } else {
                        refused++;
                    }
                }
            }
        }
        assertThat(requests).isGreaterThan(2500);
        assertThat(refused).isGreaterThan(requests / 2);
        assertThat(read).isPositive();
    }
}
