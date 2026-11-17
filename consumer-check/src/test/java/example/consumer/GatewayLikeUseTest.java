package example.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.generativearchitects.slay400.bundle.Bundle;
import ai.generativearchitects.slay400.bundle.BundleReadResult;
import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.http.HttpBinding;
import ai.generativearchitects.slay400.http.RequestParts;
import ai.generativearchitects.slay400.http.RequestResult;
import ai.generativearchitects.slay400.http.RouteMatch;
import ai.generativearchitects.slay400.http.RouteTable;
import ai.generativearchitects.slay400.mock.MockOutcome;
import ai.generativearchitects.slay400.mock.MockProgram;
import ai.generativearchitects.slay400.mock.MockReadResult;
import ai.generativearchitects.slay400.mock.MockReader;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.HttpMethod;
import ai.generativearchitects.slay400.pcml.api.MockDefinition;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What the gateway will do with the published artifacts, and nothing else: read a bundle, take an operation and the mock
 * of it, encode a request, call the mocked program and decode what it returns. The three modules named in the pom are the
 * only ones this project depends on; the others, and every version, come from the bill of materials and from the POMs that
 * were published with them.
 */
class GatewayLikeUseTest {

    @Test
    void aBundleIsReadAndTheMockOfAnOperationIsCalledThroughTheCodec() throws Exception {
        BundleReadResult read = BundleReader.read(Path.of(System.getProperty("golden.bundle")));
        assertEquals(List.of(), read.diagnostics());
        Bundle bundle = read.bundle().orElseThrow();
        assertTrue(bundle.paths().contains("descriptor.json"));

        ApiOperation status = bundle.model().operations().stream().filter(o -> o.id().equals("ordstatus")).findFirst().orElseThrow();
        MockReadResult mocks = MockReader.read(new ByteArrayInputStream(bundle.file("mock/orders.json").orElseThrow()), "mock/orders.json");
        assertEquals(List.of(), mocks.diagnostics());
        MockDefinition definition = mocks.file().orElseThrow().operations().stream()
                .filter(d -> d.operationId().equals("ordstatus")).findFirst().orElseThrow();

        OperationCodec codec = OperationCodec.of(status);
        List<WireValue> input = codec.encode(Map.of("orderNumber", new ApiValue.Integer(BigInteger.valueOf(1001))), Map.of())
                .parameters().orElseThrow();
        MockOutcome outcome = MockProgram.of(status, definition).call(input);
        MockOutcome.Returned returned = assertInstanceOf(MockOutcome.Returned.class, outcome);

        DecodeResult decoded = codec.decode(returned.output());
        assertEquals(List.of(), decoded.problems());
        assertEquals(new ApiValue.Text("S"), decoded.response().orElseThrow().get("status"));
    }

    @Test
    void aRequestIsRoutedReadAnsweredByTheMockAndWrittenAsJsonThroughTheHttpBinding() throws Exception {
        Bundle bundle = BundleReader.read(Path.of(System.getProperty("golden.bundle"))).bundle().orElseThrow();
        RouteMatch match = RouteTable.of(bundle.model()).match(HttpMethod.GET, "/orders/1001/status");
        RouteMatch.Found found = assertInstanceOf(RouteMatch.Found.class, match);
        HttpBinding binding = HttpBinding.of(found.operation());
        RequestResult read = binding.readRequest(new RequestParts(found.pathParameters(), Map.of(), Map.of(), Optional.empty(), new byte[0]));
        assertEquals(List.of(), read.problems());
        MockReadResult mocks = MockReader.read(new ByteArrayInputStream(bundle.file("mock/orders.json").orElseThrow()), "mock/orders.json");
        MockDefinition definition = mocks.file().orElseThrow().operations().stream()
                .filter(d -> d.operationId().equals(found.operation().id())).findFirst().orElseThrow();
        OperationCodec codec = OperationCodec.of(found.operation());
        List<WireValue> input = codec.encode(read.values().orElseThrow(), Map.of()).parameters().orElseThrow();
        MockOutcome.Returned returned = assertInstanceOf(MockOutcome.Returned.class, MockProgram.of(found.operation(), definition).call(input));
        byte[] json = binding.writeResponse(codec.decode(returned.output()).response().orElseThrow());
        assertEquals("{\"status\":\"S\",\"lines\":[{\"sku\":\"A1\",\"quantity\":\"2.00\"}]}", new String(json, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, binding.success().status());
    }

    @Test
    void theManifestOfTheBundleNamesTheTool() throws Exception {
        Bundle bundle = BundleReader.read(Path.of(System.getProperty("golden.bundle"))).bundle().orElseThrow();
        assertEquals("slay400", bundle.manifest().generatorName());
    }
}
