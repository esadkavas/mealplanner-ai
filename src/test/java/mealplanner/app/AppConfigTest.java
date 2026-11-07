package mealplanner.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import mealplanner.persistence.StorageException;

public class AppConfigTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsDotEnvAndEnvironmentOverrides() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Files.writeString(dir.resolve(".env"), "# comment\nGEMINI_API_KEY=\"from-file\"\nexport GEMINI_MODEL=model-a\n"
                + "LLM_CALL_TIMEOUT_SECONDS=20\n");
        AppConfig config = AppConfig.load(dir, Map.of("GEMINI_MODEL", "model-b"));
        assertEquals("from-file", config.geminiApiKey());
        assertEquals("model-b", config.geminiModel());
        assertEquals(20, config.callTimeout().toSeconds());
        assertEquals(90, config.totalTimeout().toSeconds());
        assertNull(config.aiProblem());
        assertFalse("toString must hide the key", config.toString().contains("from-file"));
    }

    @Test
    public void placeholderOrMissingKeyDisablesAiWithExplanation() throws Exception {
        Path dir = tmp.getRoot().toPath();
        assertNotNull(AppConfig.load(dir, Map.of()).aiProblem());
        Files.writeString(dir.resolve(".env"), "GEMINI_API_KEY=" + AppConfig.PLACEHOLDER_KEY + "\n");
        String problem = AppConfig.load(dir, Map.of()).aiProblem();
        assertTrue(problem.contains("Copy .env.example to .env"));
    }

    @Test
    public void startupSeedsDataAndRefusesSecondProcess() throws Exception {
        AppConfig config = AppConfig.load(tmp.getRoot().toPath(), Map.of());
        try (AppContext context = AppContext.start(config)) {
            assertEquals(21, context.facade().snapshot().getRecipes().size());
            assertTrue(Files.exists(config.dataDir().resolve("state.json")));
            assertNotNull(context.facade().aiUnavailableReason());
            assertThrows(StorageException.class, () -> AppContext.start(config));
        }
        try (AppContext again = AppContext.start(config)) {
            assertEquals(21, again.facade().snapshot().getRecipes().size());
        }
    }

    @Test
    public void corruptStateStopsStartupWithoutOverwriting() throws Exception {
        AppConfig config = AppConfig.load(tmp.getRoot().toPath(), Map.of());
        Files.createDirectories(config.dataDir());
        Files.writeString(config.dataDir().resolve("state.json"), "{\"revision\": \"oops\"");
        StorageException e = assertThrows(StorageException.class, () -> AppContext.start(config));
        assertTrue(e.getMessage().contains("not valid"));
        assertEquals("{\"revision\": \"oops\"", Files.readString(config.dataDir().resolve("state.json")));
    }
}
