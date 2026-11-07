package mealplanner.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import org.junit.Test;

import mealplanner.Fixtures;
import mealplanner.domain.AppState;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PlanRequest;
import mealplanner.domain.Recipe;
import mealplanner.facade.PlannerFacade;
import mealplanner.facade.Proposal;
import mealplanner.facade.ProposalKind;
import mealplanner.facade.Result;
import mealplanner.service.PantryInput;
import mealplanner.service.ProfileInput;
import mealplanner.service.RecommendationList;

/** F05-F08 agent behaviour with a scripted model: tools, budgets, and independent Java validation. */
public class AgentWorkflowTest {

    private final Fixtures f = new Fixtures();
    private final Fixtures.MemoryStore store = new Fixtures.MemoryStore();
    private final ScriptedLLM model = new ScriptedLLM();
    private final ToolRegistry tools = new ToolRegistry(f.recipes, f.plans, f.validator);

    private PlannerFacade facade(Duration timeout) {
        return new PlannerFacade(f.seedState(), store, f.validator, f.recipes, f.plans, f.shopping,
                new AgentCoordinator(model, tools, timeout), null);
    }

    private PlannerFacade facade() {
        return facade(Duration.ofSeconds(30));
    }

    private static String alias(PlannerFacade facade, String title) {
        AppState s = facade.snapshot();
        Recipe r = s.getRecipes().values().stream().filter(x -> x.getTitle().equals(title)).findFirst().orElseThrow();
        return s.recipeAlias(r.getId());
    }

    // ---- F05 ---------------------------------------------------------------------------------------

    @Test
    public void recommendUsesToolsAndJavaComputesShortages() {
        PlannerFacade facade = facade();
        facade.updatePantry(PantryInput.set("lentils", "100"));
        String soup = alias(facade, "Red Lentil Soup");
        model.tool("readPantry", "{}")
             .tool("searchRecipes", "{\"ingredientIds\":[\"lentils\"],\"maxMinutes\":40}")
             .ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"Uses your lentils.\",\"appliedConstraints\":{\"maxMinutes\":40,"
                     + "\"mustIncludeIngredientIds\":[\"lentils\"],\"avoidIngredientIds\":[]},"
                     + "\"recommendations\":[{\"recipeId\":\"" + soup + "\",\"reason\":\"Uses lentils.\"}]}");
        Result<RecommendationList> r = facade.recommend("Use my lentils, under 40 minutes");

        assertTrue(String.join(" ", r.errors()), r.isSuccess());
        RecommendationList.Recommendation rec = r.data().items().get(0);
        assertEquals("Red Lentil Soup", rec.recipe().getTitle());
        BigDecimal missingLentils = rec.missing().stream().filter(m -> m.ingredientId().equals("lentils"))
                .findFirst().orElseThrow().missing();
        assertEquals(0, new BigDecimal("150").compareTo(missingLentils));
        assertEquals(3, model.nextStepCalls);
        assertEquals(1, model.finishCalls);
        assertTrue(r.data().agentTrace().get(1).contains("searchRecipes"));
        assertEquals("recommendations never save", 1, store.saves);
    }

    @Test
    public void recommendRejectsRecipeThatWasNotRetrieved() {
        PlannerFacade facade = facade();
        String salmon = alias(facade, "Lemon Garlic Salmon with Potatoes");
        model.tool("searchRecipes", "{\"text\":\"soup\"}").ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"appliedConstraints\":{\"maxMinutes\":0,"
                     + "\"mustIncludeIngredientIds\":[],\"avoidIngredientIds\":[]},"
                     + "\"recommendations\":[{\"recipeId\":\"" + salmon + "\",\"reason\":\"x\"}]}");
        Result<RecommendationList> r = facade.recommend("soup please");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("was not retrieved"));
        assertFalse(r.details().isEmpty());
    }

    @Test
    public void recommendRejectsUnknownIdAndViolatedRequestConstraint() {
        PlannerFacade facade = facade();
        String risotto = alias(facade, "Mushroom Risotto");
        model.tool("searchRecipes", "{}").ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"appliedConstraints\":{\"maxMinutes\":30,"
                     + "\"mustIncludeIngredientIds\":[],\"avoidIngredientIds\":[]},"
                     + "\"recommendations\":[{\"recipeId\":\"R99\",\"reason\":\"x\"},{\"recipeId\":\"" + risotto + "\",\"reason\":\"y\"}]}");
        Result<RecommendationList> r = facade.recommend("quick dinner under 30 minutes");
        assertFalse(r.isSuccess());
        String all = String.join(" ", r.errors());
        assertTrue(all.contains("R99"));
        assertTrue(all.contains("over the requested 30"));
    }

    @Test
    public void readyWithoutRetrievalIsRedirectedToSearch() {
        PlannerFacade facade = facade();
        String toast = alias(facade, "Peanut Butter Banana Toast");
        model.ready().tool("searchRecipes", "{\"text\":\"toast\"}").ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"appliedConstraints\":{\"maxMinutes\":0,"
                     + "\"mustIncludeIngredientIds\":[],\"avoidIngredientIds\":[]},"
                     + "\"recommendations\":[{\"recipeId\":\"" + toast + "\",\"reason\":\"quick\"}]}");
        Result<RecommendationList> r = facade.recommend("something with toast");
        assertTrue(String.join(" ", r.errors()), r.isSuccess());
        assertTrue(r.data().agentTrace().get(0).contains("told to call searchRecipes"));
        AgentContext last = model.seenContexts.get(model.seenContexts.size() - 1);
        assertTrue(last.transcript().stream().anyMatch(m -> m instanceof AgentContext.Note));
    }

    @Test
    public void batchesUnknownToolsAndBadArgumentsBecomeToolErrors() {
        PlannerFacade facade = facade();
        model.batch("readPantry", "readProfile")
             .tool("deleteEverything", "{}")
             .tool("searchRecipes", "{\"maxMinutes\":\"soon\",\"color\":\"red\"}")
             .tool("searchRecipes", "{}")
             .ready()
             .finalJson("{\"status\":\"NO_MATCH\",\"message\":\"Nothing suitable.\",\"appliedConstraints\":{\"maxMinutes\":0,"
                     + "\"mustIncludeIngredientIds\":[],\"avoidIngredientIds\":[]},\"recommendations\":[]}");
        Result<RecommendationList> r = facade.recommend("anything");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).startsWith("No suitable stored recipe"));
        List<String> trace = r.details();
        assertTrue(trace.get(0).contains("rejected (one tool per step)"));
        assertTrue(trace.get(1).contains("Unknown or unavailable tool"));
        assertTrue(trace.get(2).contains("must be a whole number"));
        assertTrue(trace.get(2).contains("color is not a supported argument"));
        assertTrue(trace.get(3).contains("recipe(s)"));
    }

    @Test
    public void budgetIsAtMostFiveStepsPlusFinish() {
        PlannerFacade facade = facade();
        for (int i = 0; i < 7; i++) {
            model.tool("readPantry", "{}");
        }
        Result<RecommendationList> r = facade.recommend("anything");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("without retrieving"));
        assertEquals(5, model.nextStepCalls);
        assertEquals(0, model.finishCalls);
    }

    @Test
    public void finalAnswerWithWrongShapeIsRejected() {
        PlannerFacade facade = facade();
        model.tool("searchRecipes", "{}").ready().finalJson("{\"status\":\"OK\",\"recommendations\":\"R01\"}");
        Result<RecommendationList> r = facade.recommend("anything");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("did not follow the required format"));
    }

    @Test
    public void overallTimeoutStopsTheAgent() {
        model.delayMillis = 60;
        model.tool("searchRecipes", "{}").tool("searchRecipes", "{}").tool("searchRecipes", "{}");
        Result<RecommendationList> r = facade(Duration.ofMillis(100)).recommend("anything");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("took longer than"));
    }

    @Test
    public void missingConfigurationFailsClearlyAndDeterministicFeaturesStillWork() {
        PlannerFacade noAi = new PlannerFacade(f.seedState(), store, f.validator, f.recipes, f.plans, f.shopping,
                null, "AI features need a Gemini API key.");
        Result<RecommendationList> r = noAi.recommend("anything");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("Gemini API key"));
        assertTrue(noAi.updatePantry(PantryInput.set("rice", "100")).isSuccess());
    }

    // ---- F06 ---------------------------------------------------------------------------------------

    @Test
    public void planIsBuiltFromRetrievedRecipesAndSavedOnlyOnAccept() {
        PlannerFacade facade = facade();
        String a = alias(facade, "Red Lentil Soup");
        String b = alias(facade, "Chickpea Coconut Curry");
        String entries = "[{\"day\":1,\"slot\":\"DINNER\",\"recipeId\":\"" + a + "\"},"
                + "{\"day\":2,\"slot\":\"DINNER\",\"recipeId\":\"" + b + "\"}]";
        model.tool("searchRecipes", "{}")
             .tool("checkPlan", "{\"entries\":" + entries + "}")
             .ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"explanation\":\"Two hearty dinners.\",\"entries\":" + entries + "}");
        PlanRequest request = new PlanRequest(LocalDate.of(2026, 11, 9), 2, EnumSet.of(MealSlot.DINNER), 3, true);
        Result<Proposal> r = facade.createPlan(request, "hearty dinners");

        assertTrue(String.join(" ", r.errors()), r.isSuccess());
        Proposal p = r.data();
        assertEquals(ProposalKind.PLAN, p.kind());
        assertEquals(3, p.plan().getMeals().get(0).getServings());
        assertTrue(p.agentTrace().get(1).contains("checkPlan") && p.agentTrace().get(1).endsWith("valid"));
        assertTrue(facade.snapshot().getPlans().isEmpty());
        assertTrue(facade.accept(p).isSuccess());
        assertEquals(1, facade.snapshot().getPlans().size());
    }

    @Test
    public void checkPlanReportsProblemsToTheModel() {
        PlannerFacade facade = facade();
        String a = alias(facade, "Red Lentil Soup");
        model.tool("searchRecipes", "{}")
             .tool("checkPlan", "{\"entries\":[{\"day\":1,\"slot\":\"DINNER\",\"recipeId\":\"" + a + "\"}]}")
             .ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"explanation\":\"\",\"entries\":"
                     + "[{\"day\":1,\"slot\":\"DINNER\",\"recipeId\":\"" + a + "\"}]}");
        Result<Proposal> r = facade.createPlan(new PlanRequest(LocalDate.of(2026, 11, 9), 2,
                EnumSet.of(MealSlot.DINNER), 2, false), "");
        assertFalse("incomplete plans are never previewed", r.isSuccess());
        assertTrue(r.details().get(1).contains("problem(s)"));
        assertTrue(String.join(" ", r.errors()).contains("missing day 2 Dinner"));
    }

    @Test
    public void impossibleNoRepeatPlanFailsBeforeAnyModelCall() {
        PlannerFacade facade = facade();
        facade.updateProfile(new ProfileInput("vegetarian", List.of(), "2", "240"));
        Result<Proposal> r = facade.createPlan(new PlanRequest(LocalDate.of(2026, 11, 9), 7,
                EnumSet.allOf(MealSlot.class), 2, true), "");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("needs 21 different recipes"));
        assertEquals(0, model.nextStepCalls);
    }

    @Test
    public void clarificationRequestIsShownNotSaved() {
        PlannerFacade facade = facade();
        model.tool("searchRecipes", "{}").ready()
             .finalJson("{\"status\":\"NEEDS_CLARIFICATION\",\"message\":\"Which day do you mean?\",\"explanation\":\"\",\"entries\":[]}");
        Result<Proposal> r = facade.createPlan(new PlanRequest(LocalDate.of(2026, 11, 9), 1,
                EnumSet.of(MealSlot.DINNER), 2, false), "the usual");
        assertFalse(r.isSuccess());
        assertTrue(r.errors().get(0).contains("Which day do you mean?"));
    }

    // ---- F07 ---------------------------------------------------------------------------------------

    @Test
    public void revisionReplacesOnlyRequestedSlotAndKeepsSourcePlan() {
        PlannerFacade facade = facade();
        String a = alias(facade, "Red Lentil Soup");
        String b = alias(facade, "Chickpea Coconut Curry");
        String c = alias(facade, "Black Bean Tacos");
        String entries = "[{\"day\":1,\"slot\":\"DINNER\",\"recipeId\":\"" + a + "\"},{\"day\":2,\"slot\":\"DINNER\",\"recipeId\":\"" + b + "\"}]";
        model.tool("searchRecipes", "{}").ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"explanation\":\"x\",\"entries\":" + entries + "}");
        Proposal created = facade.createPlan(new PlanRequest(LocalDate.of(2026, 11, 9), 2,
                EnumSet.of(MealSlot.DINNER), 2, true), "").data();
        facade.accept(created);

        String replacement = "[{\"day\":2,\"slot\":\"DINNER\",\"recipeId\":\"" + c + "\"}]";
        model.tool("readPlan", "{\"planId\":\"P01\"}")
             .tool("searchRecipes", "{\"text\":\"tacos\"}")
             .tool("checkPlan", "{\"entries\":" + replacement + "}")
             .ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"explanation\":\"Tacos on Tuesday.\",\"replacements\":" + replacement + "}");
        Result<Proposal> r = facade.revisePlan("P01", "Replace Tuesday dinner with tacos");

        assertTrue(String.join(" ", r.errors()), r.isSuccess());
        assertEquals(1, r.data().changes().size());
        assertEquals("Black Bean Tacos", r.data().changes().get(0).after());
        assertTrue(r.data().agentTrace().get(0).contains("readPlan"));
        assertTrue(facade.accept(r.data()).isSuccess());
        AppState after = facade.snapshot();
        MealPlan original = after.findPlan("P01").orElseThrow();
        MealPlan revised = after.findPlan("P02").orElseThrow();
        assertEquals("Chickpea Coconut Curry", original.find(2, MealSlot.DINNER).orElseThrow().getRecipeSnapshot().getTitle());
        assertEquals("Black Bean Tacos", revised.find(2, MealSlot.DINNER).orElseThrow().getRecipeSnapshot().getTitle());
        assertEquals(original.getId(), revised.getDerivedFrom());
        assertNotEquals(original.getId(), revised.getId());
    }

    // ---- F08 ---------------------------------------------------------------------------------------

    @Test
    public void substitutionCreatesEligibleVariantFromRetrievedIngredient() {
        PlannerFacade facade = facade();
        facade.updateProfile(new ProfileInput("vegetarian", List.of("milk"), "2", "240"));
        String pancakes = alias(facade, "Fluffy Pancakes");
        model.tool("readProfile", "{}")
             .tool("listIngredients", "{\"query\":\"milk\"}")
             .ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"replacementIngredientId\":\"oat-milk\",\"quantity\":300,"
                     + "\"stepChanges\":[{\"stepNumber\":1,\"newText\":\"Whisk the flour, oat milk, eggs and half the melted butter into a batter.\"}],"
                     + "\"explanation\":\"Oat milk works 1:1 in pancakes.\"}");
        Result<Proposal> r = facade.substitute(pancakes, "milk", "Use a dairy-free milk");

        assertTrue(String.join(" ", r.errors()), r.isSuccess());
        Recipe variant = r.data().recipe();
        assertTrue(variant.usesIngredient("oat-milk"));
        assertFalse(variant.usesIngredient("milk"));
        assertTrue(variant.getSteps().get(0).contains("oat milk"));
        assertTrue(facade.accept(r.data()).isSuccess());
        assertTrue("source recipe unchanged", facade.snapshot().findRecipe(pancakes).orElseThrow().usesIngredient("milk"));
    }

    @Test
    public void substitutionRejectsIngredientThatWasNotRetrievedOrIsExcluded() {
        PlannerFacade facade = facade();
        facade.updateProfile(new ProfileInput("vegetarian", List.of("soy-milk"), "2", "240"));
        String pancakes = alias(facade, "Fluffy Pancakes");
        model.tool("listIngredients", "{\"query\":\"oat\"}").ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"replacementIngredientId\":\"soy-milk\",\"quantity\":300,"
                     + "\"stepChanges\":[],\"explanation\":\"\"}");
        Result<Proposal> notRetrieved = facade.substitute(pancakes, "milk", "dairy-free");
        assertFalse(notRetrieved.isSuccess());
        assertTrue(notRetrieved.errors().get(0).contains("not retrieved"));

        model.tool("listIngredients", "{\"query\":\"milk\"}").ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"replacementIngredientId\":\"soy-milk\",\"quantity\":300,"
                     + "\"stepChanges\":[],\"explanation\":\"\"}");
        Result<Proposal> excluded = facade.substitute(pancakes, "milk", "dairy-free");
        assertFalse(excluded.isSuccess());
        assertTrue(String.join(" ", excluded.errors()).contains("excluded ingredient soy-milk"));
    }
}
