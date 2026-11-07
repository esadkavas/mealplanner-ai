package mealplanner.facade;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import mealplanner.agent.AgentCoordinator;
import mealplanner.agent.AgentException;
import mealplanner.agent.AgentPrompts;
import mealplanner.agent.AgentRun;
import mealplanner.agent.Drafts;
import mealplanner.agent.TaskType;
import mealplanner.agent.ToolScope;
import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.domain.MealPlan;
import mealplanner.domain.PlanRequest;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;
import mealplanner.persistence.StateStore;
import mealplanner.persistence.StorageException;
import mealplanner.service.ConstraintValidator;
import mealplanner.service.DomainException;
import mealplanner.service.PantryInput;
import mealplanner.service.PlanDraft;
import mealplanner.service.PlanService;
import mealplanner.service.ProfileInput;
import mealplanner.service.RecipeDocument;
import mealplanner.service.RecipeService;
import mealplanner.service.RecommendationList;
import mealplanner.service.SearchFilter;
import mealplanner.service.ShoppingList;
import mealplanner.service.ShoppingService;
import mealplanner.service.SubstitutionDraft;
import mealplanner.service.ValidationResult;

/**
 * Facade pattern: the single entry point used by both PlannerGUI and PlannerCLI.
 *
 * <p>It hides the services, validator, agent and persistence behind focused operations that
 * return {@link Result}. Every change is applied to a deep copy of the active state, saved, and
 * only then published, under one lock held from the revision check through the save. A failed
 * save therefore leaves both the file and the active state unchanged.</p>
 */
public class PlannerFacade {

    /** Maximum length of a natural-language request (Stage 1: 2,000 characters). */
    public static final int MAX_REQUEST_CHARS = 2000;

    private final ReentrantLock mutationLock = new ReentrantLock();
    private final StateStore store;
    private final ConstraintValidator validator;
    private final RecipeService recipes;
    private final PlanService plans;
    private final ShoppingService shopping;
    private final AgentCoordinator agent;
    private final String aiUnavailableReason;
    private volatile AppState state;

    /** Facade without AI (deterministic features only). */
    public PlannerFacade(AppState initialState, StateStore store, ConstraintValidator validator,
                         RecipeService recipes, PlanService plans, ShoppingService shopping) {
        this(initialState, store, validator, recipes, plans, shopping, null, "AI features are not configured.");
    }

    /**
     * @param agent               the AI agent, or null if unavailable
     * @param aiUnavailableReason why AI cannot be used (e.g. missing API key), or null if it can
     */
    public PlannerFacade(AppState initialState, StateStore store, ConstraintValidator validator,
                         RecipeService recipes, PlanService plans, ShoppingService shopping,
                         AgentCoordinator agent, String aiUnavailableReason) {
        this.state = initialState;
        this.store = store;
        this.validator = validator;
        this.recipes = recipes;
        this.plans = plans;
        this.shopping = shopping;
        this.agent = agent;
        this.aiUnavailableReason = agent == null && aiUnavailableReason == null
                ? "AI features are not configured." : aiUnavailableReason;
    }

    // ---- read access ---------------------------------------------------------------------------

    /** An independent copy of the current state for display; changing it has no effect. */
    public AppState snapshot() {
        return state.deepCopy();
    }

    public Catalogue catalogue() {
        return validator.catalogue();
    }

    public ConstraintValidator validator() {
        return validator;
    }

    /** Null if AI features can be used, otherwise the reason they cannot (shown in the UI). */
    public String aiUnavailableReason() {
        return aiUnavailableReason;
    }

    public String aiDescription() {
        return agent == null ? "AI unavailable" : agent.modelDescription();
    }

    /** True if the recipe satisfies the saved preferences (used for "ineligible" labels). */
    public boolean isEligible(Recipe recipe) {
        return validator.isEligible(recipe, state.getProfile());
    }

    // ---- F01 preferences -----------------------------------------------------------------------

    public Result<PreferenceProfile> updateProfile(ProfileInput input) {
        ValidationResult check = validator.profile(input);
        if (!check.isStructurallyValid()) {
            return Result.failure(check.structuralErrors());
        }
        PreferenceProfile profile = validator.toProfile(input);
        return commit(candidate -> candidate.setProfile(profile), profile, "Preferences saved.");
    }

    // ---- F02 pantry ------------------------------------------------------------------------------

    public Result<Map<String, BigDecimal>> updatePantry(PantryInput input) {
        ValidationResult check = validator.pantry(input);
        if (!check.isStructurallyValid()) {
            return Result.failure(check.structuralErrors());
        }
        String id = input.ingredientId();
        if (input.action() == PantryInput.Action.REMOVE) {
            if (!state.getPantry().containsKey(id)) {
                return Result.failure("'" + id + "' is not in the pantry.");
            }
            Result<AppState> saved = commit(candidate -> candidate.removePantryItem(id), null, null);
            return saved.isSuccess()
                    ? Result.success(state.getPantry(), "Removed " + catalogue().nameOf(id) + " from the pantry.")
                    : Result.failure(saved.errors());
        }
        BigDecimal quantity = Quantities.parse(input.quantity());
        Result<AppState> saved = commit(candidate -> candidate.setPantryQuantity(id, quantity), null, null);
        return saved.isSuccess()
                ? Result.success(state.getPantry(), "Pantry: " + catalogue().nameOf(id) + " = "
                        + Quantities.display(quantity) + " " + catalogue().unitLabel(id)
                        + (quantity.signum() == 0 ? " (treated as unavailable)." : "."))
                : Result.failure(saved.errors());
    }

    // ---- F03 create / import recipes -------------------------------------------------------------

    /** Previews a recipe entered in the GUI form or with CLI flags. */
    public Result<Proposal> previewRecipe(RecipeDocument document) {
        try {
            return recipePreview(recipes.fromDocument(document), ProposalKind.IMPORT_RECIPE, "");
        } catch (DomainException e) {
            return Result.failure(e.messages());
        }
    }

    /** Previews a recipe from JSON text. */
    public Result<Proposal> previewRecipeJson(String json) {
        try {
            return recipePreview(recipes.parse(json), ProposalKind.IMPORT_RECIPE, "");
        } catch (DomainException e) {
            return Result.failure(e.messages());
        }
    }

    /** Previews a recipe from a JSON file of at most 100 KB. */
    public Result<Proposal> previewRecipeFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Result.failure("File not found: " + file);
            }
            if (Files.size(file) > RecipeService.MAX_IMPORT_BYTES) {
                return Result.failure("Recipe imports are limited to 100 KB.");
            }
            return previewRecipeJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.failure("Could not read " + file + ": " + e.getMessage());
        }
    }

    // ---- F04 search ------------------------------------------------------------------------------

    public Result<List<Recipe>> search(SearchFilter filter) {
        try {
            List<Recipe> found = recipes.search(state, filter);
            String message = found.isEmpty()
                    ? "No eligible recipe matches these filters. Try changing the filters; your saved preferences are always applied."
                    : found.size() + " matching recipe(s).";
            return Result.success(found, message);
        } catch (DomainException e) {
            return Result.failure(e.messages());
        }
    }

    // ---- F05 recommend (hybrid) ------------------------------------------------------------------

    /** Read-only: the agent searches and ranks; Java validates and computes shortages. */
    public Result<RecommendationList> recommend(String request) {
        String text = request == null ? "" : request.trim();
        Result<RecommendationList> blocked = checkAiRequest(text, true);
        if (blocked != null) {
            return blocked;
        }
        AppState snapshot = state.deepCopy();
        if (plans.eligibleRecipes(snapshot).isEmpty()) {
            return Result.failure("No stored recipe fits your current preferences, so there is nothing to recommend.");
        }
        AgentRun run = null;
        try {
            JsonNode schema = AgentPrompts.recommendSchema();
            run = agent.run(TaskType.RECOMMEND, AgentPrompts.recommend(text), ToolScope.recommend(snapshot), schema);
            Drafts.Status status = Drafts.status(run, schema);
            if (status != Drafts.Status.OK) {
                return Result.failure(List.of(statusMessage(status, Drafts.message(run))), run.trace());
            }
            RecommendationList list = recipes.recommendations(Drafts.recommendation(run), snapshot);
            if (list.items().isEmpty()) {
                return Result.failure(List.of(statusMessage(Drafts.Status.NO_MATCH, list.message())), run.trace());
            }
            return Result.success(list.withTrace(run.trace()), list.items().size() + " recommendation(s). Nothing was saved.");
        } catch (AgentException e) {
            return Result.failure(List.of(e.getMessage()), e.trace());
        } catch (DomainException e) {
            return Result.failure(e.messages(), run == null ? List.of() : run.trace());
        }
    }

    // ---- F06 generate plan (hybrid) ----------------------------------------------------------------

    /** The plan's shape is fixed by the request; the agent only chooses recipes. Saved only on accept. */
    public Result<Proposal> createPlan(PlanRequest request, String wishes) {
        String text = wishes == null ? "" : wishes.trim();
        Result<Proposal> blocked = checkAiRequest(text, false);
        if (blocked != null) {
            return blocked;
        }
        ValidationResult requestCheck = plans.validateRequest(request);
        if (!requestCheck.isStructurallyValid()) {
            return Result.failure(requestCheck.structuralErrors());
        }
        AppState snapshot = state.deepCopy();
        AgentRun run = null;
        try {
            plans.checkFeasible(snapshot, request);
            JsonNode schema = AgentPrompts.planSchema();
            run = agent.run(TaskType.PLAN, AgentPrompts.plan(request, text), ToolScope.plan(snapshot, request), schema);
            Drafts.Status status = Drafts.status(run, schema);
            if (status != Drafts.Status.OK) {
                return Result.failure(List.of(statusMessage(status, Drafts.message(run))), run.trace());
            }
            PlanDraft draft = Drafts.planEntries(run, "entries");
            MealPlan plan = plans.fromDraft(draft, snapshot, request);
            return planPreview(plan, List.of(), snapshot, draft.explanation(), run.trace());
        } catch (AgentException e) {
            return Result.failure(List.of(e.getMessage()), e.trace());
        } catch (DomainException e) {
            return Result.failure(e.messages(), run == null ? List.of() : run.trace());
        }
    }

    // ---- F07 revise plan (hybrid) ------------------------------------------------------------------

    /** Revises a copy of a saved plan; accepted revisions are saved under a new plan ID. */
    public Result<Proposal> revisePlan(String planRef, String request) {
        String text = request == null ? "" : request.trim();
        Result<Proposal> blocked = checkAiRequest(text, true);
        if (blocked != null) {
            return blocked;
        }
        AppState snapshot = state.deepCopy();
        MealPlan source = snapshot.findPlan(planRef).orElse(null);
        if (source == null) {
            return Result.failure("No plan '" + planRef + "'. Use an alias such as P01 or a plan ID.");
        }
        AgentRun run = null;
        try {
            JsonNode schema = AgentPrompts.reviseSchema();
            run = agent.run(TaskType.REVISE, AgentPrompts.revise(snapshot, source, text), ToolScope.revise(snapshot, source), schema);
            Drafts.Status status = Drafts.status(run, schema);
            if (status != Drafts.Status.OK) {
                return Result.failure(List.of(statusMessage(status, Drafts.message(run))), run.trace());
            }
            PlanDraft draft = Drafts.planEntries(run, "replacements");
            MealPlan revised = plans.revise(source, draft, snapshot);
            return planPreview(revised, plans.diff(source, revised), snapshot, draft.explanation(), run.trace());
        } catch (AgentException e) {
            return Result.failure(List.of(e.getMessage()), e.trace());
        } catch (DomainException e) {
            return Result.failure(e.messages(), run == null ? List.of() : run.trace());
        }
    }

    // ---- F08 substitute (hybrid) -------------------------------------------------------------------

    /** The agent proposes a replacement; Java applies it to a copy and validates the full variant. */
    public Result<Proposal> substitute(String recipeRef, String ingredientId, String request) {
        String text = request == null ? "" : request.trim();
        Result<Proposal> blocked = checkAiRequest(text, true);
        if (blocked != null) {
            return blocked;
        }
        AppState snapshot = state.deepCopy();
        Recipe source = snapshot.findRecipe(recipeRef).orElse(null);
        if (source == null) {
            return Result.failure("No recipe '" + recipeRef + "'. Use an alias such as R01 or a recipe ID.");
        }
        String selected = ingredientId == null ? "" : ingredientId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!source.usesIngredient(selected)) {
            return Result.failure("'" + source.getTitle() + "' does not use '" + selected + "'.");
        }
        AgentRun run = null;
        try {
            JsonNode schema = AgentPrompts.substituteSchema();
            run = agent.run(TaskType.SUBSTITUTE, AgentPrompts.substitute(snapshot, source, selected, text, catalogue()),
                    ToolScope.substitute(snapshot), schema);
            Drafts.Status status = Drafts.status(run, schema);
            if (status != Drafts.Status.OK) {
                return Result.failure(List.of(statusMessage(status, Drafts.message(run))), run.trace());
            }
            SubstitutionDraft draft = Drafts.substitution(run, selected);
            Recipe variant = recipes.substitute(source, draft, snapshot.getProfile());
            AppState candidate = snapshot.deepCopy();
            candidate.addRecipe(variant.deepCopy());
            Proposal proposal = new Proposal(ProposalKind.SUBSTITUTE_RECIPE, snapshot.getRevision(), candidate,
                    draft.explanation(), List.of("AI suggestion: check that the result suits the dish before cooking."),
                    variant, null, null, run.trace(), validator, plans);
            return Result.success(proposal, "Preview ready. Accept to save it as a new recipe; the original is unchanged.");
        } catch (AgentException e) {
            return Result.failure(List.of(e.getMessage()), e.trace());
        } catch (DomainException e) {
            return Result.failure(e.messages(), run == null ? List.of() : run.trace());
        }
    }

    private <T> Result<T> checkAiRequest(String text, boolean required) {
        if (agent == null || aiUnavailableReason != null) {
            return Result.failure(aiUnavailableReason == null ? "AI features are not configured." : aiUnavailableReason);
        }
        if (required && text.isEmpty()) {
            return Result.failure("Please describe what you want.");
        }
        if (text.length() > MAX_REQUEST_CHARS) {
            return Result.failure("Requests are limited to " + MAX_REQUEST_CHARS + " characters.");
        }
        return null;
    }

    private static String statusMessage(Drafts.Status status, String message) {
        String detail = message == null || message.isBlank() ? "" : " " + message;
        return switch (status) {
            case NO_MATCH -> "No suitable stored recipe was found." + detail;
            case NEEDS_CLARIFICATION -> "The assistant needs clarification:" + detail + " Please revise the request and try again.";
            case UNSUPPORTED -> "This request is not supported:" + detail;
            case OK -> detail.trim();
        };
    }

    private Result<Proposal> planPreview(MealPlan plan, List<PlanService.SlotChange> changes, AppState base,
                                         String explanation, List<String> trace) {
        AppState candidate = base.deepCopy();
        candidate.addPlan(plan.deepCopy());
        Proposal proposal = new Proposal(ProposalKind.PLAN, base.getRevision(), candidate, explanation, List.of(),
                null, plan, changes, trace, validator, plans);
        return Result.success(proposal, "Preview ready. Accept to save it as a new plan, or reject.");
    }

    // ---- F09 scale -------------------------------------------------------------------------------

    public Result<Proposal> scale(String recipeRef, int servings) {
        AppState current = state;
        Recipe source = current.findRecipe(recipeRef).orElse(null);
        if (source == null) {
            return Result.failure("No recipe '" + recipeRef + "'. Use an alias such as R01 or a recipe ID.");
        }
        try {
            Recipe scaled = recipes.scale(source, servings, current.getProfile());
            return recipePreview(scaled, ProposalKind.SCALE_RECIPE, scaled.getNote());
        } catch (DomainException e) {
            return Result.failure(e.messages());
        }
    }

    // ---- F10 shopping list -----------------------------------------------------------------------

    public Result<ShoppingList> shopping(String planRef) {
        MealPlan plan = state.findPlan(planRef).orElse(null);
        if (plan == null) {
            return Result.failure("No plan '" + planRef + "'. Use an alias such as P01 or a plan ID.");
        }
        try {
            ShoppingList list = shopping.generate(plan);
            return Result.success(list, ShoppingList.LABEL + " for " + state.planAlias(plan.getId())
                    + " (pantry stock is not deducted).");
        } catch (DomainException e) {
            return Result.failure(e.messages());
        }
    }

    // ---- SD08 accept / reject ----------------------------------------------------------------------

    /**
     * Saves a pending proposal. Rejected if it is stale (anything was saved after the preview),
     * already consumed, or no longer valid. Persistence failures keep the previous state.
     */
    public Result<String> accept(Proposal proposal) {
        mutationLock.lock();
        try {
            if (proposal.isConsumed()) {
                return Result.failure("This preview was already accepted or rejected.");
            }
            if (!proposal.isCurrent(state.getRevision())) {
                return Result.failure("This preview is out of date because other changes were saved after it "
                        + "was created. Please generate it again.");
            }
            ValidationResult check = proposal.validate(state);
            boolean ok = switch (proposal.kind()) {
                case IMPORT_RECIPE, SCALE_RECIPE -> check.isStructurallyValid();
                case SUBSTITUTE_RECIPE, PLAN -> check.isValid();
            };
            if (!ok) {
                return Result.failure(check.allMessages());
            }
            AppState candidate = proposal.candidate().deepCopy();
            candidate.incrementRevision();
            store.save(candidate);
            state = candidate;
            proposal.markConsumed();
            return Result.success(describeSaved(proposal, candidate), describeSaved(proposal, candidate));
        } catch (StorageException e) {
            return Result.failure(e.getMessage());
        } finally {
            mutationLock.unlock();
        }
    }

    /** Discards a preview. Nothing is saved. */
    public Result<String> reject(Proposal proposal) {
        proposal.markConsumed();
        return Result.success("Discarded.", "Preview discarded; nothing was saved.");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private Result<Proposal> recipePreview(Recipe recipe, ProposalKind kind, String explanation) {
        AppState current = state;
        ValidationResult check = validator.recipe(recipe, current.getProfile());
        if (!check.isStructurallyValid()) {
            return Result.failure(check.structuralErrors());
        }
        List<String> warnings = new ArrayList<>();
        for (String violation : check.eligibilityViolations()) {
            warnings.add("Ineligible for your current preferences (it can be stored, but will not be "
                    + "recommended or planned): " + violation);
        }
        if (recipe.getId() == null) {
            recipe.setId(UUID.randomUUID());
        }
        AppState candidate = current.deepCopy();
        candidate.addRecipe(recipe.deepCopy());
        Proposal proposal = new Proposal(kind, current.getRevision(), candidate, explanation, warnings,
                recipe, null, null, List.of(), validator, plans);
        return Result.success(proposal, "Preview ready. Accept to save it as a new recipe, or reject.");
    }

    private <T> Result<T> commit(Consumer<AppState> change, T data, String message) {
        mutationLock.lock();
        try {
            AppState candidate = state.deepCopy();
            change.accept(candidate);
            candidate.incrementRevision();
            store.save(candidate);
            state = candidate;
            @SuppressWarnings("unchecked")
            T result = data != null ? data : (T) candidate;
            return Result.success(result, message);
        } catch (StorageException e) {
            return Result.failure(e.getMessage());
        } finally {
            mutationLock.unlock();
        }
    }

    private static String describeSaved(Proposal proposal, AppState saved) {
        if (proposal.kind() == ProposalKind.PLAN) {
            MealPlan plan = proposal.plan();
            return "Saved plan " + saved.planAlias(plan.getId()) + " (" + plan.getStartDate() + ", "
                    + plan.getDays() + " day(s)" + (plan.getDerivedFrom() == null ? ""
                    : ", revised from " + saved.planAlias(plan.getDerivedFrom())) + ").";
        }
        Recipe recipe = proposal.recipe();
        return "Saved recipe " + saved.recipeAlias(recipe.getId()) + " '" + recipe.getTitle() + "'.";
    }
}
