package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/**
 * Kickoff 14, Part D: time and allocated bytes for decode and write of the synthetic shape at 10,000, 100,000 and
 * 1,000,000 cells, without trim, and with the trim when 1% is filled. Evidence, not a promise of speed: not a
 * {@code @Test}, run by hand with {@code java -cp ... LargeResponseBenchmark} (or through an IDE), not by
 * {@code ./mvnw verify}. The guard test with an actual pass/fail bound is {@link LargeResponseGuardTest}.
 */
final class LargeResponseBenchmark {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private LargeResponseBenchmark() {
    }

    public static void main(String[] args) {
        for (int n : new int[] {10_000, 100_000, 1_000_000}) {
            run("without trim, fully filled", n, false, n);
            run("with trim, 1% filled", n, true, Math.max(1, n / 100));
        }
    }

    private static void run(String label, int n, boolean trim, int filled) {
        ApiOperation operation = LargeResponseFixture.operation(n, trim);
        OperationCodec codec = OperationCodec.of(operation, Integer.MAX_VALUE);
        WireValue input = LargeResponseFixture.cells(n, filled);
        HttpBinding binding = HttpBinding.of(operation);

        // One warm-up call before the measured one, so the JIT and the JVM's own startup allocations are not counted.
        decodeAndWrite(codec, binding, input);

        long startAllocated = allocatedBytes();
        long startNanos = System.nanoTime();
        byte[] json = decodeAndWrite(codec, binding, input);
        long elapsedNanos = System.nanoTime() - startNanos;
        long allocated = allocatedBytes() - startAllocated;

        System.out.printf("%-28s n=%-9d filled=%-9d time=%8.3f ms  allocated=%10d bytes  json=%10d bytes%n",
                label, n, filled, elapsedNanos / 1_000_000.0, allocated, json.length);
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
