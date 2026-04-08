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
    void parse_functionExpression_isInvalid() {
        assertInvalid("mix(sunrise,sunset,0.5)");
        assertInvalid("notBefore(07:00,sunrise)");
        assertInvalid("clamp(sunrise,06:00,08:00)");
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
    }

    private static SceneNameParser.ParseResult parse(String sceneName) {
        return SceneNameParser.parse(sceneName);
    }
}
