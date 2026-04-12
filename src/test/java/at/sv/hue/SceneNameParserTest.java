package at.sv.hue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SceneNameParserTest {

    // ---- null / empty / disabled ----

    @Test
    void parse_nullOrEmpty_isInvalid() {
        assertInvalid(null);
        assertInvalid("");
        assertInvalid(" ");
    }

    @Test
    void parse_hashPrefix_isInvalid_disabled() {
        assertInvalid("#07:00");
        assertInvalid(" #07:00");
        assertInvalid("# sunrise");
    }

    // ---- HH:mm time expressions ----

    @Test
    void parse_returnsTimeExpression() {
        assertTimeExpression("07:00", "07:00");
        assertTimeExpression("07:00:30", "07:00:30");
        assertTimeExpression("00:00", "00:00");
        assertTimeExpression("23:59", "23:59");
    }

    @Test
    void parse_invalidTime_isInvalid() {
        assertInvalid("25:00");
        assertInvalid("07:60");
    }

    // ---- Alternative absolute time formats ----

    @Test
    void parse_singleDigitHour_normalizedTo24h() {
        assertTimeExpression("7:30", "07:30");
        assertTimeExpression("7:00", "07:00");
        assertTimeExpression("0:00", "00:00");
    }

    @Test
    void parse_pmSuffix_normalizedTo24h() {
        assertTimeExpression("7pm", "19:00");
        assertTimeExpression("7PM", "19:00");
        assertTimeExpression("7 pm", "19:00");
        assertTimeExpression("7 PM", "19:00");
        assertTimeExpression("7:30pm", "19:30");
        assertTimeExpression("7:30 pm", "19:30");
        assertTimeExpression("7:30 PM", "19:30");
        assertTimeExpression("12pm", "12:00");
    }

    @Test
    void parse_amSuffix_normalizedTo24h() {
        assertTimeExpression("12am", "00:00");
        assertTimeExpression("12:15am", "00:15");
        assertTimeExpression("7am", "07:00");
        assertTimeExpression("7:30am", "07:30");
        assertTimeExpression("7:30 am", "07:30");
    }

    @Test
    void parse_uhrSuffix_normalizedTo24h() {
        assertTimeExpression("7 Uhr", "07:00");
        assertTimeExpression("7Uhr", "07:00");
        assertTimeExpression("19 Uhr", "19:00");
        assertTimeExpression("0 Uhr", "00:00");
        assertTimeExpression("7 uhr", "07:00");
        assertTimeExpression("07:30 Uhr", "07:30");
        assertTimeExpression("9:31 Uhr", "09:31");
    }

    @Test
    void parse_dotSeparator_normalizedTo24h() {
        assertTimeExpression("7.30", "07:30");
        assertTimeExpression("19.05", "19:05");
        assertTimeExpression("00.00", "00:00");
        assertTimeExpression("23.59", "23:59");
    }

    @Test
    void parse_dotSeparatorUhr_normalizedTo24h() {
        assertTimeExpression("7.30 Uhr", "07:30");
        assertTimeExpression("15.00 Uhr", "15:00");
        assertTimeExpression("9.20 Uhr", "09:20");
        assertTimeExpression("09.31 uhr", "09:31");
    }

    @Test
    void parse_hSuffix_normalizedTo24h() {
        assertTimeExpression("7h", "07:00");
        assertTimeExpression("7 h", "07:00");
        assertTimeExpression("7H", "07:00");
        assertTimeExpression("19h", "19:00");
        assertTimeExpression("0h", "00:00");
    }

    @Test
    void parse_hSuffixWithMinutes_normalizedTo24h() {
        assertTimeExpression("7h30", "07:30");
        assertTimeExpression("7 h 30", "07:30");
        assertTimeExpression("7 h30", "07:30");
        assertTimeExpression("19h00", "19:00");
    }

    @Test
    void parse_alternativeTimeWithFlags() {
        assertFlags("7:30[i]", "07:30", null, null, true);
        assertFlags("7pm[tr:5min]", "19:00", "5min", null, null);
        assertFlags("7 Uhr[i]", "07:00", null, null, true);
        assertFlags("7:30 pm[tr-b:30min]", "19:30", null, "30min", null);
        assertFlags("7.30[i]", "07:30", null, null, true);
        assertFlags("7h30[tr:5min]", "07:30", "5min", null, null);
    }

    @Test
    void parse_invalidAlternativeTimes_areInvalid() {
        assertInvalid("13pm");
        assertInvalid("12:60pm");
        assertInvalid("0pm");
        assertInvalid("24 Uhr");
        assertInvalid("7");
        assertInvalid("24h");
        assertInvalid("7.60");
        assertInvalid("24.00");
    }

    // ---- Sun keyword expressions ----

    @Test
    void parse_sunrise_returnsTimeExpression() {
        assertTimeExpression("sunrise", "sunrise");
        assertTimeExpression("sunset", "sunset");
        assertTimeExpression("noon", "noon");
        assertTimeExpression("civil_dawn", "civil_dawn");
        assertTimeExpression("civil_dusk", "civil_dusk");
    }

    @Test
    void parse_allOtherSunKeywords_returnTimeExpression() {
        assertThat(parse("astronomical_start")).isNotNull();
        assertThat(parse("astronomical_dawn")).isNotNull();
        assertThat(parse("nautical_start")).isNotNull();
        assertThat(parse("nautical_dawn")).isNotNull();
        assertThat(parse("civil_start")).isNotNull();
        assertThat(parse("golden_hour")).isNotNull();
        assertThat(parse("blue_hour")).isNotNull();
        assertThat(parse("civil_end")).isNotNull();
        assertThat(parse("night_hour")).isNotNull();
        assertThat(parse("nautical_end")).isNotNull();
        assertThat(parse("nautical_dusk")).isNotNull();
        assertThat(parse("astronomical_end")).isNotNull();
        assertThat(parse("astronomical_dusk")).isNotNull();
    }

    @Test
    void parse_sunKeyword_caseInsensitive() {
        assertTimeExpression("SUNRISE", "SUNRISE");
        assertTimeExpression("Sunset", "Sunset");
    }

    @Test
    void parse_unknownKeyword_isInvalid() {
        assertInvalid("Living Room");
        assertInvalid("solar_noon");
        assertInvalid("Bright");
    }

    // ---- Offset expressions ----

    @Test
    void parse_sunriseWithOffset_returnsTimeExpression() {
        assertTimeExpression("sunrise+30", "sunrise+30");
        assertTimeExpression("sunset-15", "sunset-15");
    }

    @Test
    void parse_invalidSunKeyword_withOffset_isInvalid() {
        assertInvalid("solar_noon+10");
        assertInvalid("something+30");
    }

    @Test
    void parse_offset_nonIntegerValue_isInvalid() {
        assertInvalid("sunrise+30min");
        assertInvalid("sunrise++");
        assertInvalid("sunrise+ ?");
    }

    // ---- Function expressions (not in scope) ----

    @Test
    void parse_functionExpression_accepted_caseInsensitive() {
        assertTimeExpression("max(sunrise,7:00)", "max(sunrise,7:00)");
        assertTimeExpression("Max(sunrise,7:00)", "Max(sunrise,7:00)");
        assertTimeExpression("min(sunset,19:00)", "min(sunset,19:00)");
        assertTimeExpression("mix(sunrise,sunset,0.5)", "mix(sunrise,sunset,0.5)");
        assertTimeExpression("notBefore(sunrise,07:00)", "notBefore(sunrise,07:00)");
        assertTimeExpression("notAfter(sunset,19:00)", "notAfter(sunset,19:00)");
        assertTimeExpression("clamp(sunrise,06:00,08:00)", "clamp(sunrise,06:00,08:00)");
        assertTimeExpression("clamp(NOT_FURTHER_VALIDATED)", "clamp(NOT_FURTHER_VALIDATED)");
    }

    // ---- Flags ----

    @Test
    void parse_emptyBrackets_returnsValidResultWithNullFlags() {
        assertTimeExpression("07:00[]", "07:00");
    }

    @Test
    void parse_interpolateFlag_setsInterpolate() {
        assertFlags("07:00[i]", "07:00", null, null, true);
        assertFlags("sunset[i]", "sunset", null, null, true);
    }

    @Test
    void parse_interpolateFlag_ignoresWhitespace() {
        assertFlags("07:00 [i] ", "07:00", null, null, true);
    }

    @Test
    void parse_trFlag_setsTransitionTime() {
        assertFlags("07:00[tr:5min]", "07:00", "5min", null, null);
    }

    @Test
    void parse_trBeforeFlag_setsTransitionTimeBefore() {
        assertFlags("07:00[tr-b:19:00]", "07:00", null, "19:00", null);
        assertFlags("sunrise[tr-b:30min]", "sunrise", null, "30min", null);
        assertFlags("sunset[tr-b:golden_hour]", "sunset", null, "golden_hour", null);
    }

    @Test
    void parse_additionalFlags_forced_off_parsed() {
        SceneNameParser.ParseResult result = parse("07:00 [off, f]");

        assertThat(result).isNotNull();
        assertThat(result.forced()).isTrue();
        assertThat(result.on()).isFalse();
    }

    @Test
    void parse_additionalFlags_on_parsed() {
        SceneNameParser.ParseResult result = parse("07:00 [on]");

        assertThat(result).isNotNull();
        assertThat(result.on()).isTrue();
    }

    @Test
    void parse_additionalFlags_daysOfWeek_parsed() {
        SceneNameParser.ParseResult result = parse("07:00 [days:Mo;Di;Mi-Fr]");

        assertThat(result).isNotNull();
        assertThat(result.daysOfWeek()).isEqualTo("Mo,Di,Mi-Fr");
    }

    @Test
    void parse_combinedFlags() {
        assertFlags("sunrise+30[tr:1h, tr-b:19:00]", "sunrise+30", "1h", "19:00", null);
        assertFlags("07:00[i,tr:5min,tr-b:19:00]", "07:00", "5min", "19:00", true);
    }

    @Test
    void parse_unknownFlag_isIgnored() {
        assertTimeExpression("07:00[x]", "07:00");
        assertTimeExpression("07:00[force]", "07:00");
        assertFlags("07:00[,i]", "07:00", null, null, true);
    }

    @Test
    void parse_unclosedBracket_isInvalid() {
        assertInvalid("07:00[i");
    }

    // ---- German locale aliases ----

    @Test
    void parse_german() {
        assertTimeExpression("Sonnenaufgang", "sunrise");
        assertTimeExpression("sonnenaufgang", "sunrise");
        assertTimeExpression("SONNENAUFGANG", "sunrise");
        assertTimeExpression("Sonnenuntergang", "sunset");
        assertTimeExpression("Mittag", "noon");
        assertTimeExpression("Goldene Stunde", "golden_hour");
        assertTimeExpression("GoldeneStunde", "golden_hour");
        assertTimeExpression("Blaue Stunde", "blue_hour");
        assertTimeExpression("Bürgerliche Morgendämmerung", "civil_dawn");
        assertTimeExpression("Nautische Morgendämmerung", "nautical_dawn");
        assertTimeExpression("Astronomische Morgendämmerung", "astronomical_dawn");
        assertTimeExpression("Bürgerliche Abenddämmerung", "civil_dusk");
        assertTimeExpression("Nautische Abenddämmerung", "nautical_dusk");
        assertTimeExpression("Astronomische Abenddämmerung", "astronomical_dusk");
    }

    @Test
    void parse_german_abbreviated() {
        assertTimeExpression("Bürg. Morgendämm.", "civil_dawn");
        assertTimeExpression("Bürg Morgendämm", "civil_dawn");
        assertTimeExpression("Naut. Morgendämm.", "nautical_dawn");
        assertTimeExpression("Astr. Morgendämm.", "astronomical_dawn");
        assertTimeExpression("Bürg. Abenddämm.", "civil_dusk");
        assertTimeExpression("Burg Abenddämm.", "civil_dusk");
        assertTimeExpression("Naut. Abenddämm.", "nautical_dusk");
        assertTimeExpression("Astr. Abenddämm.", "astronomical_dusk");
    }

    @Test
    void parse_german_withOffset_mapsAndPreservesOffset() {
        assertTimeExpression("Sonnenaufgang+30", "sunrise+30");
        assertTimeExpression("Sonnenuntergang-15", "sunset-15");
        assertTimeExpression("Astr. Morgendämm.+60", "astronomical_dawn+60");
    }

    @Test
    void parse_german_withFlags_mapsCorrectly() {
        assertFlags("Sonnenuntergang[i]", "sunset", null, null, true);
        assertFlags("Sonnenaufgang+30[tr:1h]", "sunrise+30", "1h", null, null);
        assertFlags("Bürg. Morgendämm.[tr-b:30min]", "civil_dawn", null, "30min", null);
        assertFlags("Sonnenuntergang [tr-b:golden_hour]", "sunset", null, "golden_hour", null);
    }

    // ---- English friendly aliases ----

    @Test
    void parse_english_variants() {
        assertTimeExpression("Civil Dawn", "civil_dawn");
        assertTimeExpression("civil dawn", "civil_dawn");
        assertTimeExpression("CIVIL DAWN", "civil_dawn");
        assertTimeExpression("Nautical Dawn", "nautical_dawn");
        assertTimeExpression("Astronomical Dawn", "astronomical_dawn");
        assertTimeExpression("Golden Hour", "golden_hour");
        assertTimeExpression("Blue Hour", "blue_hour");
        assertTimeExpression("Night Hour", "night_hour");
        assertTimeExpression("Civil Dusk", "civil_dusk");
        assertTimeExpression("Nautical Dusk", "nautical_dusk");
        assertTimeExpression("Astronomical Dusk", "astronomical_dusk");
    }

    @Test
    void parse_english_withOffset_mapsAndPreservesOffset() {
        assertTimeExpression("Civil Dawn+30", "civil_dawn+30");
        assertTimeExpression("Golden Hour-10", "golden_hour-10");
    }

    @Test
    void parse_english_withFlags_mapsCorrectly() {
        assertFlags("Civil Dawn[i]", "civil_dawn", null, null, true);
        assertFlags("Golden Hour[tr:5min]", "golden_hour", "5min", null, null);
    }

    private static void assertInvalid(String sceneName) {
        assertThat(parse(sceneName)).isNull();
    }

    private static void assertTimeExpression(String sceneName, String time) {
        assertFlags(sceneName, time, null, null, null);
    }

    private static void assertFlags(String sceneName, String timeExpression, String tr, String trBefore, Boolean interpolate) {
        SceneNameParser.ParseResult result = parse(sceneName);

        assertThat(result).isNotNull();
        assertThat(result.timeExpression()).isEqualTo(timeExpression);
        assertThat(result.transitionTime()).isEqualTo(tr);
        assertThat(result.transitionTimeBefore()).isEqualTo(trBefore);
        assertThat(result.interpolate()).isEqualTo(interpolate);
        assertThat(result.forced()).isNull();
        assertThat(result.on()).isNull();
    }

    private static SceneNameParser.ParseResult parse(String sceneName) {
        return SceneNameParser.parse(sceneName);
    }
}
