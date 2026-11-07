package mealplanner.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * A structured substitution proposed by the agent (F08): which ingredient replaces the selected
 * one, in what quantity (in the replacement's own canonical unit), and which steps change.
 */
public record SubstitutionDraft(String selectedIngredientId, String replacementIngredientId,
                                BigDecimal quantity, List<StepChange> stepChanges, String explanation) {

    public SubstitutionDraft {
        stepChanges = stepChanges == null ? List.of() : List.copyOf(stepChanges);
    }

    /** Replaces the text of one instruction step (1-based). */
    public record StepChange(int stepNumber, String newText) {
    }
}
