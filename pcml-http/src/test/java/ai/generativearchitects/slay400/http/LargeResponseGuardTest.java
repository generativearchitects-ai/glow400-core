package ai.generativearchitects.slay400.http;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Kickoff 14, Part D's one real guard, with a wide margin so it cannot fail by chance: the synthetic shape at 100,000
 * cells, 1% filled (1,000 kept), with the trim, decodes and writes within a bound on allocated bytes that is a small
 * multiple of the kept part, not of the 100,000 declared. {@link LargeResponseBenchmark} has the figures without the bound.
 */
class LargeResponseGuardTest {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * Generous on purpose: what matters is that this stays orders of magnitude below what the declared size (100,000
     * elements) would cost if every dropped element were still built, not a tight efficiency target.
     */
    private static final long BYTES_PER_KEPT_ELEMENT = 20_000;

    @Test
    void decodingAndWritingATrimmedArrayCostsAccordingToWhatIsKeptNotWhatIsDeclared() {
        int n = 100_000;
        int filled = n / 100;
        ApiOperation operation = LargeResponseFixture.operation(n, true);
        OperationCodec codec = OperationCodec.of(operation, Integer.MAX_VALUE);
        WireValue input = LargeResponseFixture.cells(n, filled);
        HttpBinding binding = HttpBinding.of(operation);

        // Warm-up: decode once outside the measurement so the JIT's own allocations are not counted.
        byte[] warm = decodeAndWrite(codec, binding, input);
        assertThat(warm.length).isGreaterThan(0);

        long before = allocatedBytes();
        decodeAndWrite(codec, binding, input);
        long allocated = allocatedBytes() - before;

        assertThat(allocated).as("bytes allocated to decode and write %d elements, %d kept", n, filled)
                .isLessThan(filled * BYTES_PER_KEPT_ELEMENT);
    }

    private static byte[] decodeAndWrite(OperationCodec codec, HttpBinding binding, WireValue input) {
        DecodeResult decoded = codec.decode(List.of(input));
        Map<String, ApiValue> response = decoded.response().orElseThrow();
        return binding.writeResponse(response);
    }

    private static long allocatedBytes() {
        return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
