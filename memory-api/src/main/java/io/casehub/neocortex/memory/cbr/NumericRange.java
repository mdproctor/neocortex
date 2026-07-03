package io.casehub.neocortex.memory.cbr;

public record NumericRange(double min, double max) {

    public NumericRange {
        if (min > max) {
            throw new IllegalArgumentException(
                "min must be <= max, got min=" + min + " max=" + max);
        }
    }

    public static NumericRange exact(double value) {
        return new NumericRange(value, value);
    }

    public static NumericRange within(double center, double toleranceFraction) {
        if (toleranceFraction < 0) {
            throw new IllegalArgumentException("toleranceFraction must be >= 0");
        }
        double delta = Math.abs(center * toleranceFraction);
        return new NumericRange(center - delta, center + delta);
    }

    public static NumericRange of(double min, double max) {
        return new NumericRange(min, max);
    }

    public boolean contains(double value) {
        return value >= min && value <= max;
    }
}
