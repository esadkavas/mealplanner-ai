package mealplanner.domain;

import java.util.Locale;

public enum MealSlot {
    BREAKFAST,
    LUNCH,
    DINNER;

    /** Parses "dinner", "Lunch", etc.; returns null for unsupported labels. */
    public static MealSlot parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return MealSlot.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }
}
