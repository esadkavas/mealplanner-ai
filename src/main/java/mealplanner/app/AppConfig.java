package mealplanner.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import mealplanner.llm.GeminiAdapter;

/**
 * Local configuration. Values come from a ".env" file in the working directory (ignored by Git)
 * and are overridden by real environment variables with the same names. The API key is never
 * printed; {@link #toString()} masks it.
 */
public record AppConfig(String geminiApiKey, String geminiModel, String geminiBaseUrl,
                        Duration callTimeout, Duration totalTimeout, Path dataDir) {

    public static final String PLACEHOLDER_KEY = "YOUR_LOCAL_API_KEY_HERE";
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";

    public static AppConfig load(Path workingDir, Map<String, String> environment) {
        Map<String, String> values = new HashMap<>(readDotEnv(workingDir.resolve(".env")));
        environment.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                values.put(k, v);
            }
        });
        String dataDir = values.getOrDefault("MEALPLANNER_DATA_DIR", "data");
        return new AppConfig(
                values.getOrDefault("GEMINI_API_KEY", "").trim(),
                values.getOrDefault("GEMINI_MODEL", DEFAULT_MODEL).trim(),
                values.getOrDefault("GEMINI_BASE_URL", GeminiAdapter.DEFAULT_BASE_URL).trim(),
                Duration.ofSeconds(seconds(values.get("LLM_CALL_TIMEOUT_SECONDS"), 15)),
                Duration.ofSeconds(seconds(values.get("LLM_TOTAL_TIMEOUT_SECONDS"), 90)),
                workingDir.resolve(dataDir).normalize());
    }

    /** Null if AI can be used, otherwise a user-facing explanation (never containing the key). */
    public String aiProblem() {
        if (geminiApiKey.isEmpty() || geminiApiKey.equals(PLACEHOLDER_KEY)) {
            return "AI features are off because no Gemini API key is configured. Copy .env.example to .env, "
                    + "set GEMINI_API_KEY, and restart. All other features still work.";
        }
        if (geminiModel.isEmpty()) {
            return "AI features are off because GEMINI_MODEL is empty in .env.";
        }
        return null;
    }

    public AppConfig withDataDir(Path dir) {
        return new AppConfig(geminiApiKey, geminiModel, geminiBaseUrl, callTimeout, totalTimeout, dir);
    }

    @Override
    public String toString() {
        return "AppConfig[model=" + geminiModel + ", key=" + (geminiApiKey.isEmpty() ? "(none)" : "(set, hidden)")
                + ", dataDir=" + dataDir + "]";
    }

    static Map<String, String> readDotEnv(Path file) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException e) {
            // An unreadable .env behaves like a missing one; aiProblem() explains what to do.
        }
        return values;
    }

    private static long seconds(String text, long fallback) {
        try {
            long value = Long.parseLong(text == null ? "" : text.trim());
            return value > 0 && value <= 600 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
