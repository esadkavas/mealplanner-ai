package mealplanner.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import mealplanner.agent.AgentCoordinator;
import mealplanner.agent.ToolRegistry;
import mealplanner.domain.AppState;
import mealplanner.domain.Catalogue;
import mealplanner.domain.MealEntry;
import mealplanner.domain.MealPlan;
import mealplanner.domain.Recipe;
import mealplanner.facade.PlannerFacade;
import mealplanner.llm.GeminiAdapter;
import mealplanner.llm.LoggingLLMClient;
import mealplanner.persistence.DataDirectoryLock;
import mealplanner.persistence.JsonStateStore;
import mealplanner.persistence.StorageException;
import mealplanner.service.ConstraintValidator;
import mealplanner.service.PlanService;
import mealplanner.service.RecipeService;
import mealplanner.service.ShoppingService;
import mealplanner.service.ValidationResult;

/**
 * Application startup: builds and injects every dependency, takes the data-directory lock,
 * loads (or seeds) the saved state, and wires the LLM chain
 * LoggingLLMClient -> GeminiAdapter when an API key is configured.
 */
public final class AppContext implements AutoCloseable {

    private final AppConfig config;
    private final DataDirectoryLock lock;
    private final PlannerFacade facade;
    private final LoggingLLMClient llm;

    private AppContext(AppConfig config, DataDirectoryLock lock, PlannerFacade facade, LoggingLLMClient llm) {
        this.config = config;
        this.lock = lock;
        this.facade = facade;
        this.llm = llm;
    }

    public static AppContext start(AppConfig config) throws StorageException {
        Catalogue catalogue = SeedData.loadCatalogue();
        ConstraintValidator validator = new ConstraintValidator(catalogue);
        RecipeService recipes = new RecipeService(validator);
        PlanService plans = new PlanService(validator);
        ShoppingService shopping = new ShoppingService(catalogue);

        DataDirectoryLock lock = DataDirectoryLock.acquire(config.dataDir());
        try {
            JsonStateStore store = new JsonStateStore(config.dataDir().resolve("state.json"));
            AppState state = store.load().orElse(null);
            if (state == null) {
                state = SeedData.initialState(validator, recipes);
                store.save(state);
            } else {
                checkIntegrity(state, validator);
            }
            LoggingLLMClient llm = null;
            AgentCoordinator agent = null;
            String aiProblem = config.aiProblem();
            if (aiProblem == null) {
                llm = new LoggingLLMClient(new GeminiAdapter(config.geminiApiKey(), config.geminiModel(),
                        config.geminiBaseUrl(), config.callTimeout()), config.dataDir().resolve("logs").resolve("agent.log"));
                agent = new AgentCoordinator(llm, new ToolRegistry(recipes, plans, validator), config.totalTimeout());
            }
            PlannerFacade facade = new PlannerFacade(state, store, validator, recipes, plans, shopping, agent, aiProblem);
            return new AppContext(config, lock, facade, llm);
        } catch (StorageException | RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    /** Invalid stored data is reported, never silently replaced. */
    private static void checkIntegrity(AppState state, ConstraintValidator validator) throws StorageException {
        List<String> problems = new ArrayList<>();
        problems.addAll(validator.profile(state.getProfile()).structuralErrors());
        for (Recipe recipe : state.getRecipes().values()) {
            ValidationResult check = validator.recipe(recipe, null);
            check.structuralErrors().forEach(e -> problems.add("Recipe " + state.recipeAlias(recipe.getId()) + ": " + e));
        }
        for (MealPlan plan : state.getPlans().values()) {
            for (MealEntry entry : plan.getMeals()) {
                ValidationResult check = validator.recipe(entry.getRecipeSnapshot(), null);
                check.structuralErrors().forEach(e -> problems.add("Plan " + state.planAlias(plan.getId()) + ": " + e));
            }
        }
        if (!problems.isEmpty()) {
            throw new StorageException("Saved data is invalid and was not changed: "
                    + String.join("; ", problems.subList(0, Math.min(5, problems.size()))));
        }
    }

    public PlannerFacade facade() {
        return facade;
    }

    public AppConfig config() {
        return config;
    }

    /** The logging LLM client, or null when AI is not configured. */
    public LoggingLLMClient llm() {
        return llm;
    }

    public Path dataDir() {
        return config.dataDir();
    }

    @Override
    public void close() {
        lock.close();
    }
}
