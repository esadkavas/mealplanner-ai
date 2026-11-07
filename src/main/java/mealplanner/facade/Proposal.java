package mealplanner.facade;

import java.util.List;
import java.util.UUID;

import mealplanner.domain.AppState;
import mealplanner.domain.MealPlan;
import mealplanner.domain.Recipe;
import mealplanner.service.ConstraintValidator;
import mealplanner.service.PlanService;
import mealplanner.service.ValidationResult;

/**
 * A preview waiting for the user's decision. It holds a complete candidate state that would be
 * saved on acceptance, and the state revision it was created from. If anything is saved in the
 * meantime the proposal is stale and must be regenerated; once accepted it is consumed.
 */
public final class Proposal {

    private final UUID proposalId = UUID.randomUUID();
    private final ProposalKind kind;
    private final long baseRevision;
    private final AppState candidate;
    private final String explanation;
    private final List<String> warnings;
    private final Recipe recipe;
    private final MealPlan plan;
    private final List<PlanService.SlotChange> changes;
    private final ConstraintValidator validator;
    private final PlanService plans;
    private boolean consumed;

    Proposal(ProposalKind kind, long baseRevision, AppState candidate, String explanation, List<String> warnings,
             Recipe recipe, MealPlan plan, List<PlanService.SlotChange> changes,
             ConstraintValidator validator, PlanService plans) {
        this.kind = kind;
        this.baseRevision = baseRevision;
        this.candidate = candidate;
        this.explanation = explanation == null ? "" : explanation;
        this.warnings = List.copyOf(warnings);
        this.recipe = recipe;
        this.plan = plan;
        this.changes = changes == null ? List.of() : List.copyOf(changes);
        this.validator = validator;
        this.plans = plans;
    }

    /** True only if nothing has been saved since the preview and it has not been used yet. */
    public synchronized boolean isCurrent(long revision) {
        return !consumed && revision == baseRevision;
    }

    /** Re-checks the proposed object with the same rules used for the preview. */
    public ValidationResult validate(AppState state) {
        return switch (kind) {
            case IMPORT_RECIPE, SCALE_RECIPE -> {
                ValidationResult r = validator.recipe(recipe, null);
                yield r;
            }
            case SUBSTITUTE_RECIPE -> validator.recipe(recipe, state.getProfile());
            case PLAN -> plans.validate(plan, state.getProfile());
        };
    }

    synchronized void markConsumed() {
        consumed = true;
    }

    public synchronized boolean isConsumed() {
        return consumed;
    }

    AppState candidate() {
        return candidate;
    }

    public UUID id() {
        return proposalId;
    }

    public ProposalKind kind() {
        return kind;
    }

    public long baseRevision() {
        return baseRevision;
    }

    public String explanation() {
        return explanation;
    }

    /** Eligibility warnings, e.g. "ineligible for your vegan profile" for an imported recipe. */
    public List<String> warnings() {
        return warnings;
    }

    /** The proposed recipe (recipe kinds) or null. */
    public Recipe recipe() {
        return recipe;
    }

    /** The proposed plan (PLAN kind) or null. */
    public MealPlan plan() {
        return plan;
    }

    /** For plan revisions: the slots that changed. */
    public List<PlanService.SlotChange> changes() {
        return changes;
    }
}
