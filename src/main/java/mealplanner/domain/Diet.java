package mealplanner.domain;

import java.util.Locale;

/**
 * Supported dietary filters. Eligibility comes from catalogue metadata only; the app does not
 * certify medical allergy safety or product-level religious compliance.
 */
public enum Diet {
    UNRESTRICTED,
    VEGETARIAN,
    VEGAN;

    /** Parses user input such as "vegan" or "Vegetarian"; returns null for unsupported labels. */
    public static Diet parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Diet.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
