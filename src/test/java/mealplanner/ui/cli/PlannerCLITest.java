package mealplanner.ui.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.Test;

import mealplanner.Fixtures;
import mealplanner.agent.AgentCoordinator;
import mealplanner.agent.ScriptedLLM;
import mealplanner.agent.ToolRegistry;
import mealplanner.facade.PlannerFacade;

/** Drives the CLI with scripted input against the real facade (and a scripted model for AI). */
public class PlannerCLITest {

    private final Fixtures f = new Fixtures();
    private final ScriptedLLM model = new ScriptedLLM();

    private String run(PlannerFacade facade, String... lines) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new PlannerCLI(facade, null).run(new BufferedReader(new StringReader(String.join("\n", lines) + "\nquit\n")), out);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private PlannerFacade facade() {
        return new PlannerFacade(f.seedState(), new Fixtures.MemoryStore(), f.validator, f.recipes, f.plans, f.shopping,
                new AgentCoordinator(model, new ToolRegistry(f.recipes, f.plans, f.validator), Duration.ofSeconds(30)), null);
    }

    @Test
    public void tokenizerKeepsQuotedText() {
        assertEquals(List.of("agent", "recommend", "--request", "Use my lentils, please"),
                PlannerCLI.tokenize("agent recommend --request \"Use my lentils, please\""));
    }

    @Test
    public void stage1ProfileAndPantryCommandsWork() {
        PlannerFacade facade = facade();
        String out = run(facade,
                "profile set --diet vegetarian --servings 2 --max-minutes 30 --exclude peanut",
                "pantry set --ingredient rice --quantity 500",
                "pantry set --ingredient rice --quantity -1",
                "profile set --diet keto");
        assertTrue(out.contains("Preferences saved."));
        assertTrue(out.contains("Pantry: Rice = 500 g."));
        assertTrue(out.contains("quantity: must not be negative."));
        assertTrue(out.contains("diet: 'keto' is not supported"));
        assertEquals(30, facade.snapshot().getProfile().maxMinutes());
    }

    @Test
    public void importPreviewAcceptAndSearch() {
        PlannerFacade facade = facade();
        String out = run(facade,
                "recipe import --file samples/lentil-soup.json",
                "accept",
                "recipe search --text soup --max-minutes 30",
                "recipe import --file samples/invalid-recipe.json",
                "recipe import --file samples/malformed-recipe.json");
        assertTrue(out.contains("PREVIEW (new recipe)"));
        assertTrue(out.contains("Saved recipe R22 'Lentil Spinach Soup'."));
        assertTrue(out.contains("R22  Lentil Spinach Soup"));
        assertTrue(out.contains("'dragon-fruit-powder' is not a known ingredient ID"));
        assertTrue(out.contains("Malformed recipe JSON"));
        assertEquals(22, facade.snapshot().getRecipes().size());
    }

    @Test
    public void scaleThenRejectSavesNothing() {
        PlannerFacade facade = facade();
        String out = run(facade, "recipe scale --id R01 --servings 5", "reject", "accept");
        assertTrue(out.contains("PREVIEW (scaled recipe variant)"));
        assertTrue(out.contains("Preview discarded; nothing was saved."));
        assertTrue(out.contains("There is no preview waiting."));
        assertEquals(21, facade.snapshot().getRecipes().size());
    }

    @Test
    public void agentPlanThenShoppingList() {
        PlannerFacade facade = facade();
        String entries = "[{\"day\":1,\"slot\":\"DINNER\",\"recipeId\":\"R01\"},{\"day\":2,\"slot\":\"DINNER\",\"recipeId\":\"R01\"}]";
        model.tool("searchRecipes", "{\"text\":\"lentil\"}").ready()
             .finalJson("{\"status\":\"OK\",\"message\":\"\",\"explanation\":\"Soup twice.\",\"entries\":" + entries + "}");
        String out = run(facade,
                "agent plan --start 2026-11-09 --days 2 --slots dinner --servings 2",
                "accept",
                "plan list",
                "shopping generate --plan P01");
        assertTrue(out, out.contains("Saved plan P01"));
        assertTrue(out.contains("AI agent steps:"));
        assertTrue(out.contains("Total ingredients required for P01"));
        assertTrue("2 dinners x 2 servings of a 4-serving soup = 250 g lentils", out.contains("Red lentils (lentils)"));
        assertTrue(out.contains("250 g"));
    }

    @Test
    public void unknownCommandsAndMissingOptionsAreExplained() {
        String out = run(facade(), "bake cake", "recipe scale --id R01", "agent recommend");
        assertTrue(out.contains("Unknown command 'bake'"));
        assertTrue(out.contains("--servings is required."));
        assertTrue(out.contains("--request is required."));
        assertFalse(out.contains("Exception"));
    }
}
