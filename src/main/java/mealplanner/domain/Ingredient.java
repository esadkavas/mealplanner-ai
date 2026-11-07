package mealplanner.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable catalogue entry with a stable ID and one canonical unit.
 *
 * @param dietTags the diets this ingredient is compatible with (e.g. lentils: all three;
 *                 milk: UNRESTRICTED and VEGETARIAN; chicken: UNRESTRICTED only)
 */
public record Ingredient(String id, String name, Unit unit, Set<Diet> dietTags) {

    public Ingredient {
        dietTags = dietTags == null || dietTags.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.of(Diet.UNRESTRICTED))
                : Collections.unmodifiableSet(EnumSet.copyOf(dietTags));
    }

    public boolean allows(Diet diet) {
        return diet == Diet.UNRESTRICTED || dietTags.contains(diet);
    }
}
