package at.sv.hue;

import java.util.function.BiPredicate;

public record Pair<F, S>(F first, S second) {
    public static <F, S> Pair<F, S> of(F first, S second) {
        return new Pair<>(first, second);
    }

    public boolean test(BiPredicate<F, S> predicate) {
        return predicate.test(first, second);
    }

    @Override
    public String toString() {
        return "[" + first + "," + second + ']';
    }
}
