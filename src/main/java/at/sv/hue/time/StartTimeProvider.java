package at.sv.hue.time;

import java.time.ZonedDateTime;

public interface StartTimeProvider {
    /**
     * @param input    one of the following start time expressions:
     *                 <ul>
     *                   <li>a {@link java.time.format.DateTimeFormatter#ISO_LOCAL_TIME ISO_LOCAL_TIME} formatted string</li>
     *                   <li>a supported sun keyword (optionally with minute offset using + or -)</li>
     *                   <li>a function expression with syntax {@code functionName(arg1,arg2,...)} where arguments may be
     *                       fixed times, sun keywords, keyword offsets, or nested function expressions</li>
     *                 </ul>
     *                 Supported function names are {@code notBefore}, {@code notAfter}, {@code clamp}, {@code min}, {@code max},
     *                 and {@code mix}. The {@code mix(a,b,w)} function interpolates between {@code a} and {@code b}
     *                 with weight {@code w} where {@code w} can be in {@code [0..1]} or as percentage ({@code 0%..100%}).
     * @param dateTime the date to use a reference for resolving solar times
     * @return the start time corresponding to the input and dateTime
     * @throws InvalidStartTimeExpression if the input is neither a valid {@link java.time.format.DateTimeFormatter#ISO_LOCAL_TIME}
     *                                    nor a supported sun keyword expression, nor a supported function expression.
     */
    ZonedDateTime getStart(String input, ZonedDateTime dateTime);

    String toDebugString(ZonedDateTime dateTime);

    void clearCaches();
}
