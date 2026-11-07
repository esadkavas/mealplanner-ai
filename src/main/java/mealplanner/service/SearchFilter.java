package mealplanner.service;

import java.util.List;
import java.util.Locale;

/**
 * F04 search filters. All fields are optional. maxMinutes can tighten, but never loosen, the
 * saved profile time limit.
 */
public record SearchFilter(String text, List<String> ingredientIds, Integer maxMinutes, boolean pantryOnly) {

    public SearchFilter {
        text = text == null ? "" : text.trim();
        ingredientIds = ingredientIds == null ? List.of()
                : ingredientIds.stream().filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
    }

    public static SearchFilter all() {
        return new SearchFilter("", List.of(), null, false);
    }
}
