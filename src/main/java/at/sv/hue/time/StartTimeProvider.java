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
     *                 {@code mix}, and {@code smooth}. The {@code mix(a,b,w)} function interpolates between {@code a} and {@code b}
     *                 with weight {@code w} (in {@code [0..1]} or as percentage {@code 0%..100%}), where {@code w=0} returns {@code a}
     *                 and {@code w=1} returns {@code b}.
     *                 The {@code smooth(expr,halfLife)} function exponentially smooths a time expression across past days,
     *                 where {@code halfLife} is specified in days (e.g. {@code 14d}).
     * @param dateTime the date to use a reference for resolving solar times
     * @return the start time corresponding to the input and dateTime
     * @throws InvalidStartTimeExpression if the input is neither a valid {@link java.time.format.DateTimeFormatter#ISO_LOCAL_TIME}
     *                                    nor a supported sun keyword expression, nor a supported function expression.
     */
    ZonedDateTime getStart(String input, ZonedDateTime dateTime);

    String toDebugString(ZonedDateTime dateTime);

    void clearCaches();
}
