package ai.generativearchitects.slay400.pcml.codec;

import static ai.generativearchitects.slay400.pcml.codec.Ops.chr;
import static ai.generativearchitects.slay400.pcml.codec.Ops.field;
import static ai.generativearchitects.slay400.pcml.codec.Ops.int4;
import static ai.generativearchitects.slay400.pcml.codec.Ops.op;
import static ai.generativearchitects.slay400.pcml.codec.Ops.struct;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Kickoff 14, Part B: for random shapes and fill levels, trimming before decoding (what {@link OperationCodec#decode}
 * does) gives the same response as decoding every declared element and dropping the same elements afterwards, by
 * whichever trim rule applies.
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

    /**
     * Review of PR #20, "to change" #3: a {@code dropBlank} at each of two nesting levels, independently — an array
     * of structs (rows), each holding its own array (cells). Blankness is generated per cell, at random, not only at
     * the trailing position, so a row can have blanks in the middle of its cells too; only the trailing run at each
     * level is ever dropped, by either level's own rule.
     */
    @Test
    void nestedTrimAtEachLevelGivesTheSameResultBeforeOrAfterDecoding() {
        Properties.check("nestedTrimBeforeEqualsAfter", gen -> {
            int cellsPerRow = gen.intIn(1, 5);
            int rowCount = gen.intIn(0, 5);
            boolean outerTrim = gen.bool();
            boolean innerTrim = gen.bool();

            List<WireValue> rows = new ArrayList<>(rowCount);
            boolean[] rowAllBlank = new boolean[rowCount];
            boolean[][] cellBlank = new boolean[rowCount][cellsPerRow];
            for (int r = 0; r < rowCount; r++) {
                List<WireValue> cells = new ArrayList<>(cellsPerRow);
                boolean allBlank = true;
                for (int c = 0; c < cellsPerRow; c++) {
                    boolean isBlank = gen.bool();
                    cellBlank[r][c] = isBlank;
                    allBlank &= isBlank;
                    cells.add(isBlank ? new WireValue.Text("  ") : new WireValue.Text("XY"));
                }
                rowAllBlank[r] = allBlank;
                rows.add(new WireValue.Struct(List.of(new WireValue.Array(cells))));
            }
            WireValue.Array input = new WireValue.Array(rows);

            Ops.F cellField = field("CELLS", chr(2)).count(cellsPerRow);
            if (innerTrim) {
                cellField.trimBlank();
            }
            Ops.F rowField = struct("ROW", cellField).count(rowCount).response();
            if (outerTrim) {
                rowField.trimBlank();
            }

            ApiValue.Arr actual = (ApiValue.Arr) OperationCodec.of(op(rowField)).decode(List.of(input)).response().orElseThrow().get("row");

            // Reference, from the same ground truth the input was built from: a row's blankness (for the outer
            // trim) is whether every one of its cells is blank, regardless of whether the inner array is itself
            // trimmed -- isBlankElement looks at the full declared cells, not at what a trim would keep.
            int keptRows = rowCount;
            if (outerTrim) {
                while (keptRows > 0 && rowAllBlank[keptRows - 1]) {
                    keptRows--;
                }
            }
            List<ApiValue> expected = new ArrayList<>();
            for (int r = 0; r < keptRows; r++) {
                int keptCells = cellsPerRow;
                if (innerTrim) {
                    while (keptCells > 0 && cellBlank[r][keptCells - 1]) {
                        keptCells--;
                    }
                }
                List<ApiValue> cellValues = new ArrayList<>();
                for (int c = 0; c < keptCells; c++) {
                    cellValues.add(cellBlank[r][c] ? new ApiValue.Text("") : new ApiValue.Text("XY"));
                }
                expected.add(new ApiValue.Obj(Map.of("cells", new ApiValue.Arr(cellValues))));
            }

            assertThat(actual.elements())
                    .as("cellsPerRow=%d rowCount=%d outerTrim=%s innerTrim=%s", cellsPerRow, rowCount, outerTrim, innerTrim)
                    .isEqualTo(expected);
        });
    }

    /** Review of PR #20, "to change" #3: the same equivalence, for a {@code countField} trim. */
    @Test
    void countFieldTrimBeforeOrAfterDecodingGivesTheSameResult() {
        Properties.check("countFieldTrimBeforeEqualsAfter", gen -> {
            int size = gen.intIn(0, 20);
            int n = gen.intIn(0, size);
            List<WireValue> elements = new ArrayList<>(size);
            List<String> texts = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String t = gen.text(Gen.LETTERS, 1, 2);
                texts.add(t);
                elements.add(new WireValue.Text(t));
            }
            WireValue.Array input = new WireValue.Array(elements);

            Ops.F countField = field("N", int4()).internal();
            Ops.F arrayField = field("A", chr(2)).count(size).response().trimCount("N");
            DecodeResult r = OperationCodec.of(op(countField, arrayField))
                    .decode(List.of(new WireValue.Integer(BigInteger.valueOf(n)), input));

            assertThat(r.problems()).isEmpty();
            List<ApiValue> expected = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                expected.add(new ApiValue.Text(texts.get(i)));
            }
            assertThat(((ApiValue.Arr) r.response().orElseThrow().get("a")).elements()).as("size=%d n=%d", size, n).isEqualTo(expected);
        });
    }

    /**
     * Kickoff 15, Part B: the same equivalence, for a {@code countField} trim set on a struct member, naming a
     * sibling member of the same struct rather than a top-level field.
     */
    @Test
    void memberCountFieldTrimGivesTheSameResultAsTheParameterLevelOne() {
        Properties.check("memberCountFieldTrimBeforeEqualsAfter", gen -> {
            int size = gen.intIn(0, 20);
            int n = gen.intIn(0, size);
            List<WireValue> elements = new ArrayList<>(size);
            List<String> texts = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String t = gen.text(Gen.LETTERS, 1, 2);
                texts.add(t);
                elements.add(new WireValue.Text(t));
            }
            Ops.F structField = struct("S", field("N", int4()), field("T", chr(2)).count(size).trimCount("N")).response();
            WireValue input = new WireValue.Struct(List.of(new WireValue.Integer(BigInteger.valueOf(n)), new WireValue.Array(elements)));
            DecodeResult r = OperationCodec.of(op(structField)).decode(List.of(input));

            assertThat(r.problems()).isEmpty();
            List<ApiValue> expected = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                expected.add(new ApiValue.Text(texts.get(i)));
            }
            ApiValue.Obj decoded = (ApiValue.Obj) r.response().orElseThrow().get("s");
            assertThat(((ApiValue.Arr) decoded.members().get("t")).elements()).as("size=%d n=%d", size, n).isEqualTo(expected);
        });
    }
}
