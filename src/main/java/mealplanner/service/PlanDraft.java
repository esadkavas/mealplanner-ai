package mealplanner.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Structured meal choices returned by the agent (F06/F07). Each entry names a day, a slot and a
 * recipe reference (alias such as R07, or UUID).
 *
 * <p>{@code retrievedRecipeIds} is attached by the AgentCoordinator from trusted tool results,
 * never from the model's own claims. New meal selections must come from that set.</p>
 */
public record PlanDraft(List<Entry> entries, String explanation, Set<UUID> retrievedRecipeIds) {

    public PlanDraft {
        entries = entries == null ? List.of() : List.copyOf(entries);
        retrievedRecipeIds = retrievedRecipeIds == null ? Set.of() : Set.copyOf(retrievedRecipeIds);
    }

    public record Entry(int day, String slot, String recipeRef) {
    }
}
