package ai.generativearchitects.slay400.pcml.codec;

import static ai.generativearchitects.slay400.pcml.codec.Ops.chr;
import static ai.generativearchitects.slay400.pcml.codec.Ops.field;
import static ai.generativearchitects.slay400.pcml.codec.Ops.op;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Kickoff 14, Part B: for random shapes and fill levels, trimming before decoding (what {@link OperationCodec#decode}
 * does) gives the same response as decoding every declared element and dropping the same trailing blanks afterwards.
 */
class TrimBeforeDecodePropertyTest {

    @Test
    void trimmingBeforeOrAfterDecodingGivesTheSameResult() {
        Properties.check("trimBeforeEqualsDecodeThenTrim", gen -> {
            int size = gen.intIn(0, 40);
            List<WireValue> elements = new ArrayList<>(size);
            List<Boolean> blank = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                boolean isBlank = gen.bool();
                blank.add(isBlank);
                elements.add(isBlank ? new WireValue.Text("   ") : new WireValue.Text(gen.text(Gen.LETTERS, 1, 3)));
            }
            WireValue.Array input = new WireValue.Array(elements);

            var trimmed = field("A", chr(3)).count(size).response().trimBlank();
            var untrimmed = field("A", chr(3)).count(size).response();
            ApiValue.Arr withTrimBeforeDecode = (ApiValue.Arr) OperationCodec.of(op(trimmed)).decode(List.of(input)).response().orElseThrow().get("a");
            ApiValue.Arr decodedInFull = (ApiValue.Arr) OperationCodec.of(op(untrimmed)).decode(List.of(input)).response().orElseThrow().get("a");

            int end = size;
            while (end > 0 && blank.get(end - 1)) {
                end--;
            }
            List<ApiValue> trimAfterDecode = decodedInFull.elements().subList(0, end);

            assertThat(withTrimBeforeDecode.elements()).as("size=%d blank=%s", size, blank).isEqualTo(trimAfterDecode);
        });
    }
}
