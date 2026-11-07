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

import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.domain.MealPlan;
import mealplanner.domain.PreferenceProfile;
import mealplanner.domain.Quantities;
import mealplanner.domain.Recipe;
import mealplanner.persistence.StateStore;
import mealplanner.persistence.StorageException;
import mealplanner.service.ConstraintValidator;
import mealplanner.service.DomainException;
import mealplanner.service.PantryInput;
import mealplanner.service.PlanService;
import mealplanner.service.ProfileInput;
import mealplanner.service.RecipeDocument;
import mealplanner.service.RecipeService;
import mealplanner.service.SearchFilter;
import mealplanner.service.ShoppingList;
import mealplanner.service.ShoppingService;
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

    private final ReentrantLock mutationLock = new ReentrantLock();
    private final StateStore store;
    private final ConstraintValidator validator;
    private final RecipeService recipes;
    private final PlanService plans;
    private final ShoppingService shopping;
    private volatile AppState state;

    public PlannerFacade(AppState initialState, StateStore store, ConstraintValidator validator,
                         RecipeService recipes, PlanService plans, ShoppingService shopping) {
        this.state = initialState;
        this.store = store;
        this.validator = validator;
        this.recipes = recipes;
        this.plans = plans;
        this.shopping = shopping;
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
                recipe, null, null, validator, plans);
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
