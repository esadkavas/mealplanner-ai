package mealplanner.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** BigDecimal helpers. Calculations use DECIMAL128; only displayed values are rounded. */
public final class Quantities {

    public static final MathContext MATH = MathContext.DECIMAL128;

    private Quantities() {
    }

    /** Rounds to two decimal places for display only; stored values are never rewritten. */
    public static String display(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.signum() == 0) {
            return "0";
        }
        return rounded.toPlainString();
    }

    /** Parses user text into a BigDecimal, or returns null if it is not a number. */
    public static BigDecimal parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
