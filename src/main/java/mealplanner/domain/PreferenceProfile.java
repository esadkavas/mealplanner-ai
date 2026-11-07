package mealplanner.domain;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * Saved dietary preferences: the source of constraints for search, planning and the agent.
 * Immutable, so sharing it between a state and its copy is safe.
 */
public record PreferenceProfile(Diet diet, Set<String> excludedIds, int householdServings, int maxMinutes) {

    public PreferenceProfile {
        excludedIds = Collections.unmodifiableSortedSet(new TreeSet<>(excludedIds == null ? Set.of() : excludedIds));
    }

    public static PreferenceProfile defaults() {
        return new PreferenceProfile(Diet.UNRESTRICTED, Set.of(), 2, 240);
    }
}
