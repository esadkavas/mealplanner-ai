package mealplanner.agent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import mealplanner.domain.AppState;
import mealplanner.domain.MealPlan;
import mealplanner.domain.PlanRequest;

/**
 * Everything the tools may read for one request: a read-only snapshot of the saved state, the
 * fixed PlanRequest (plan generation) or source plan (revision), and the IDs retrieved so far.
 * Tools can never save anything; only PlannerFacade.accept() does that.
 */
public final class ToolScope {

    private final TaskType task;
    private final AppState snapshot;
    private final PlanRequest planRequest;
    private final MealPlan sourcePlan;
    private final Set<UUID> retrievedRecipeIds = new HashSet<>();
    private final Set<String> retrievedIngredientIds = new HashSet<>();
    private boolean searched;
    private boolean listedIngredients;

    private ToolScope(TaskType task, AppState snapshot, PlanRequest planRequest, MealPlan sourcePlan) {
        this.task = task;
        this.snapshot = snapshot;
        this.planRequest = planRequest;
        this.sourcePlan = sourcePlan;
    }

    public static ToolScope recommend(AppState snapshot) {
        return new ToolScope(TaskType.RECOMMEND, snapshot, null, null);
    }

    public static ToolScope plan(AppState snapshot, PlanRequest request) {
        return new ToolScope(TaskType.PLAN, snapshot, request, null);
    }

    public static ToolScope revise(AppState snapshot, MealPlan sourcePlan) {
        return new ToolScope(TaskType.REVISE, snapshot, PlanRequest.of(sourcePlan), sourcePlan);
    }

    public static ToolScope substitute(AppState snapshot) {
        return new ToolScope(TaskType.SUBSTITUTE, snapshot, null, null);
    }

    void recordRetrieval(ToolResult result) {
        retrievedRecipeIds.addAll(result.retrievedRecipeIds());
        retrievedIngredientIds.addAll(result.retrievedIngredientIds());
        if ("searchRecipes".equals(result.toolName()) && result.success()) {
            searched = true;
        }
        if ("listIngredients".equals(result.toolName()) && result.success()) {
            listedIngredients = true;
        }
    }

    /** True once the model has retrieved the candidates this task must choose from. */
    boolean hasRequiredRetrieval() {
        return task == TaskType.SUBSTITUTE ? listedIngredients : searched;
    }

    public TaskType task() {
        return task;
    }

    public AppState snapshot() {
        return snapshot;
    }

    public PlanRequest planRequest() {
        return planRequest;
    }

    public MealPlan sourcePlan() {
        return sourcePlan;
    }

    public Set<UUID> retrievedRecipeIds() {
        return Set.copyOf(retrievedRecipeIds);
    }

    public Set<String> retrievedIngredientIds() {
        return Set.copyOf(retrievedIngredientIds);
    }
}
