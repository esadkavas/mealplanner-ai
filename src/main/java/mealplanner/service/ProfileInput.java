package mealplanner.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Raw preference input exactly as typed in the GUI form or CLI flags. Numbers stay as text so
 * validation can report "servings: 'abc' is not a whole number" instead of crashing.
 */
public record ProfileInput(String diet, List<String> excludedIds, String servings, String maxMinutes) {

    public ProfileInput {
        List<String> cleaned = new ArrayList<>();
        if (excludedIds != null) {
            for (String id : excludedIds) {
                if (id != null && !id.isBlank()) {
                    cleaned.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        excludedIds = List.copyOf(cleaned);
    }
}
