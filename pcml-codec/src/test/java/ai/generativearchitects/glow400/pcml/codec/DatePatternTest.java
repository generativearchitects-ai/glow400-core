package ai.generativearchitects.glow400.pcml.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The fixed-width date and time pattern grammar. */
class DatePatternTest {

    private static DatePattern pattern(String text) {
        DatePattern.Parsed parsed = DatePattern.parse(text);
        assertThat(parsed.error()).as(text).isEmpty();
        return parsed.pattern().orElseThrow();
    }

    private static String error(String text) {
        DatePattern.Parsed parsed = DatePattern.parse(text);
        assertThat(parsed.pattern()).as(text).isEmpty();
        return parsed.error().orElseThrow();
    }

    // ---- grammar

    @Test
    void everyPartHasAFixedWidth() {
        assertThat(pattern("yyyyMMdd").outputLength()).isEqualTo(8);
        assertThat(pattern("yyyy-MM-dd").outputLength()).isEqualTo(10);
        assertThat(pattern("HHmmss").outputLength()).isEqualTo(6);
        assertThat(pattern("HH:mm:ss").outputLength()).isEqualTo(8);
        assertThat(pattern("HH:mm:ss.S").outputLength()).isEqualTo(10);
        assertThat(pattern("HH:mm:ss.SSSSSSSSS").outputLength()).isEqualTo(18);
        assertThat(pattern("yyyy-MM-dd'T'HH:mm:ss").outputLength()).isEqualTo(19);
        assertThat(pattern("yyyy MM dd").outputLength()).isEqualTo(10);
        assertThat(pattern("dd.MM.yyyy").outputLength()).isEqualTo(10);
    }

    @Test
    void anyCharacterThatIsNotALetterIsALiteral() {
        assertThat(pattern("yyyy/MM/dd").outputLength()).isEqualTo(10);
        assertThat(pattern("yyyy_MM_dd").outputLength()).isEqualTo(10);
        assertThat(pattern("yyyy0MM0dd").outputLength()).isEqualTo(10);
        assertThat(pattern("(yyyyMMdd)").outputLength()).isEqualTo(10);
    }

    @Test
    void aQuotedTextIsALiteralAndTwoQuotesAreOneApostrophe() {
        assertThat(pattern("'at 'HH").outputLength()).isEqualTo(5);
        assertThat(pattern("HH''mm").outputLength()).isEqualTo(5);
        assertThat(pattern("HH'o''clock'mm").outputLength()).isEqualTo(2 + 7 + 2);
        assertThat(pattern("yyyy''").outputLength()).isEqualTo(5);
        assertThat(pattern("yyyy'abc'").outputLength()).isEqualTo(7);
        assertThat(pattern("'yyyy'HH").outputLength()).isEqualTo(6);
        assertThat(pattern("'a''b'HH").outputLength()).isEqualTo(5);
        assertThat(pattern("HH''''mm").outputLength()).isEqualTo(6);
        assertThat(pattern("HH'x'").outputLength()).isEqualTo(3);
    }

    @Test
    void unsupportedPartsAreRefused() {
        assertThat(error("yy")).contains("'y' repeated 2 times");
        assertThat(error("y")).contains("'y' repeated 1 time is not supported");
        assertThat(error("yyyyy")).contains("'y' repeated 5 times");
        assertThat(error("M")).contains("'M'");
        assertThat(error("MMM")).contains("'M' repeated 3 times");
        assertThat(error("d")).contains("'d'");
        assertThat(error("ddd")).contains("'d'");
        assertThat(error("H")).contains("'H'");
        assertThat(error("HHH")).contains("'H'");
        assertThat(error("m")).contains("'m'");
        assertThat(error("s")).contains("'s'");
        assertThat(error("SSSSSSSSSS")).contains("'S' repeated 10 times");
        assertThat(error("EEE")).contains("'E'");
        assertThat(error("a")).contains("'a'");
        assertThat(error("T")).contains("'T'");
        assertThat(error("h")).contains("'h'");
        assertThat(error("yyyyMMddHHmmssX")).contains("'X'");
        assertThat(error("é")).contains("é");
    }

    @Test
    void aPartMayAppearOnlyOnce() {
        assertThat(error("yyyyMMddyyyy")).contains("'y' appears twice");
        assertThat(error("HHmmssHH")).contains("'H' appears twice");
        assertThat(error("HH:mm:ss.SSS.SS")).contains("'S' appears twice");
        assertThat(error("MMddMM")).contains("'M' appears twice");
        assertThat(error("ddMMdd")).contains("'d' appears twice");
        assertThat(error("mmHHmm")).contains("'m' appears twice");
        assertThat(error("ssHHss")).contains("'s' appears twice");
    }

    @Test
    void anEmptyPatternOrAnUnclosedQuoteIsRefused() {
        assertThat(error("")).contains("empty");
        assertThat(error("yyyy'")).contains("quote is not closed");
        assertThat(error("yyyy'abc")).contains("quote is not closed");
        assertThat(error("'")).contains("quote is not closed");
        assertThat(error("yyyy'ab''")).contains("quote is not closed");
        assertThat(DatePattern.parse("''").pattern()).isPresent();
    }

    // ---- what the pattern suits

    @Test
    void aDatePatternHasYearMonthDayAndNoTime() {
        assertThat(pattern("yyyyMMdd").problemForDate()).isEmpty();
        assertThat(pattern("yyyyMM").problemForDate()).isPresent();
        assertThat(pattern("yyyydd").problemForDate()).isPresent();
        assertThat(pattern("MMdd").problemForDate()).isPresent();
        for (String withTime : new String[] {"yyyyMMddHH", "yyyyMMddmm", "yyyyMMddss", "yyyyMMddS"}) {
            assertThat(pattern(withTime).problemForDate()).as(withTime).isPresent();
        }
    }

    @Test
    void aTimePatternHasHourMinuteSecondAndNoDate() {
        assertThat(pattern("HHmmss").problemForTime()).isEmpty();
        assertThat(pattern("HHmmssSSS").problemForTime()).isEmpty();
        assertThat(pattern("HHmm").problemForTime()).isPresent();
        assertThat(pattern("HHss").problemForTime()).isPresent();
        assertThat(pattern("mmss").problemForTime()).isPresent();
        assertThat(pattern("SSS").problemForTime()).isPresent();
        for (String withDate : new String[] {"yyyyHHmmss", "MMHHmmss", "ddHHmmss"}) {
            assertThat(pattern(withDate).problemForTime()).as(withDate).isPresent();
        }
    }

    @Test
    void aDateTimePatternHasEverythingFromYearToSecond() {
        assertThat(pattern("yyyyMMddHHmmss").problemForDateTime()).isEmpty();
        assertThat(pattern("yyyyMMddHHmmssSSS").problemForDateTime()).isEmpty();
        assertThat(pattern("yyyyMMdd").problemForDateTime()).isPresent();
        assertThat(pattern("HHmmss").problemForDateTime()).isPresent();
        assertThat(pattern("yyyyMMddHHmm").problemForDateTime()).isPresent();
        assertThat(pattern("yyyyMMHHmmss").problemForDateTime()).isPresent();
    }

    // ---- formatting

    @Test
    void datesAreWrittenWithLeadingZeros() {
        assertThat(pattern("yyyyMMdd").format(LocalDate.of(2026, 1, 2))).contains("20260102");
        assertThat(pattern("dd.MM.yyyy").format(LocalDate.of(5, 11, 30))).contains("30.11.0005");
        assertThat(pattern("yyyy-MM-dd").format(LocalDate.of(0, 1, 1))).contains("0000-01-01");
        assertThat(pattern("yyyy-MM-dd").format(LocalDate.of(9999, 12, 31))).contains("9999-12-31");
        assertThat(pattern("yyyy-MM-dd").format(LocalDate.of(10000, 1, 1))).isEmpty();
        assertThat(pattern("yyyy-MM-dd").format(LocalDate.of(-1, 1, 1))).isEmpty();
    }

    @Test
    void timesAreWrittenWithTheirFractionOnlyIfNothingIsLost() {
        assertThat(pattern("HH:mm:ss").format(LocalTime.of(9, 5, 7))).contains("09:05:07");
        assertThat(pattern("HH:mm:ss").format(LocalTime.of(23, 59, 59))).contains("23:59:59");
        assertThat(pattern("HH:mm:ss").format(LocalTime.of(0, 0, 0))).contains("00:00:00");
        assertThat(pattern("HH:mm:ss").format(LocalTime.of(9, 5, 7, 1))).isEmpty();
        assertThat(pattern("HH:mm:ss.SSS").format(LocalTime.of(9, 5, 7, 120_000_000))).contains("09:05:07.120");
        assertThat(pattern("HH:mm:ss.SSS").format(LocalTime.of(9, 5, 7, 123_000_000))).contains("09:05:07.123");
        assertThat(pattern("HH:mm:ss.SSS").format(LocalTime.of(9, 5, 7, 123_000_001))).isEmpty();
        assertThat(pattern("HH:mm:ss.S").format(LocalTime.of(9, 5, 7, 100_000_000))).contains("09:05:07.1");
        assertThat(pattern("HH:mm:ss.S").format(LocalTime.of(9, 5, 7, 120_000_000))).isEmpty();
        assertThat(pattern("HH:mm:ss.SSSSSSSSS").format(LocalTime.of(9, 5, 7, 1))).contains("09:05:07.000000001");
        assertThat(pattern("HH:mm:ss.SSSSSS").format(LocalTime.of(9, 5, 7, 1_000))).contains("09:05:07.000001");
    }

    @Test
    void aDateTimeCombinesBoth() {
        assertThat(pattern("yyyy-MM-dd'T'HH:mm:ss").format(LocalDateTime.of(2026, 11, 5, 9, 5, 7))).contains("2026-11-05T09:05:07");
        assertThat(pattern("yyyyMMddHHmmssSSS").format(LocalDateTime.of(2026, 11, 5, 9, 5, 7, 5_000_000))).contains("20261105090507005");
        assertThat(pattern("yyyyMMddHHmmss").format(LocalDateTime.of(2026, 11, 5, 9, 5, 7, 5))).isEmpty();
        assertThat(pattern("yyyyMMddHHmmss").format(LocalDateTime.of(10000, 11, 5, 9, 5, 7))).isEmpty();
    }

    // ---- parsing

    @Test
    void parsingIsStrictAboutLengthDigitsAndLiterals() {
        DatePattern date = pattern("yyyy-MM-dd");
        assertThat(date.parseDate("2026-11-05")).contains(LocalDate.of(2026, 11, 5));
        for (String bad : new String[] {"2026-11-5", "2026/11/05", "2026-11-05 ", " 2026-11-05", "2026-1a-05", "2026-11-0", "", "20261105", "2026-11--5"}) {
            assertThat(date.parseDate(bad)).as(bad).isEmpty();
        }
        assertThat(date.parseDate("2026-02-30")).isEmpty();
        assertThat(date.parseDate("2026-13-01")).isEmpty();
        assertThat(date.parseDate("2026-00-01")).isEmpty();
        assertThat(date.parseDate("2026-01-00")).isEmpty();
        assertThat(date.parseDate("2024-02-29")).contains(LocalDate.of(2024, 2, 29));
        assertThat(date.parseDate("2025-02-29")).isEmpty();
        assertThat(date.parseDate("0000-01-01")).contains(LocalDate.of(0, 1, 1));
        assertThat(pattern("yyyyMMdd").parseDate("2026110٥")).isEmpty();
    }

    @Test
    void timesParseWithTheirFraction() {
        DatePattern time = pattern("HH:mm:ss.SSS");
        assertThat(time.parseTime("09:05:07.120")).contains(LocalTime.of(9, 5, 7, 120_000_000));
        assertThat(time.parseTime("23:59:59.999")).contains(LocalTime.of(23, 59, 59, 999_000_000));
        assertThat(pattern("HHmmss").parseTime("090507")).contains(LocalTime.of(9, 5, 7));
        assertThat(pattern("HHmmssSSSSSSSSS").parseTime("090507000000001")).contains(LocalTime.of(9, 5, 7, 1));
        assertThat(pattern("HHmmssS").parseTime("0905075")).contains(LocalTime.of(9, 5, 7, 500_000_000));
        for (String bad : new String[] {"24:00:00.000", "09:60:00.000", "09:00:60.000", "9:05:07.120", "09:05:07.12", "09:05:07,120"}) {
            assertThat(time.parseTime(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void dateTimesParseAndAnInvalidOneIsEmpty() {
        DatePattern dateTime = pattern("yyyy-MM-dd'T'HH:mm:ss");
        assertThat(dateTime.parseDateTime("2026-11-05T09:05:07")).contains(LocalDateTime.of(2026, 11, 5, 9, 5, 7));
        assertThat(dateTime.parseDateTime("2026-11-05 09:05:07")).isEmpty();
        assertThat(dateTime.parseDateTime("2026-11-31T09:05:07")).isEmpty();
        assertThat(dateTime.parseDateTime("2026-11-05T25:05:07")).isEmpty();
        assertThat(pattern("yyyyMMddHHmmssSS").parseDateTime("2026110509050712")).contains(LocalDateTime.of(2026, 11, 5, 9, 5, 7, 120_000_000));
    }

    @Test
    void aPatternMissingPartsReadsTheRestAsTheStartOfTheirRange() {
        assertThat(pattern("HHmmss").parseDate("090507")).contains(LocalDate.of(0, 1, 1));
        assertThat(pattern("yyyyMMdd").parseTime("20261105")).contains(LocalTime.MIDNIGHT);
        Optional<LocalDate> date = pattern("yyyyMMdd").parseDate("20261105");
        assertThat(date).contains(LocalDate.of(2026, 11, 5));
    }

    @Test
    void formattingAndParsingAgree() {
        for (String text : new String[] {"yyyyMMdd", "yyyy-MM-dd", "dd/MM/yyyy", "yyyy.MM.dd"}) {
            DatePattern p = pattern(text);
            for (LocalDate d : new LocalDate[] {LocalDate.of(2026, 1, 31), LocalDate.of(1999, 12, 1), LocalDate.of(2024, 2, 29), LocalDate.of(5, 5, 5)}) {
                assertThat(p.parseDate(p.format(d).orElseThrow())).as(text + " " + d).contains(d);
            }
        }
    }
}
