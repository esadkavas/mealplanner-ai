package mealplanner.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.awt.GraphicsEnvironment;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import mealplanner.llm.ConnectionCheck;
import mealplanner.llm.GeminiAdapter;
import mealplanner.llm.LoggingLLMClient;
import mealplanner.persistence.StorageException;
import mealplanner.ui.cli.PlannerCLI;
import mealplanner.ui.gui.PlannerGUI;

/**
 * Entry point.
 * <pre>
 *   java -jar mealplanner.jar            Swing GUI (default)
 *   java -jar mealplanner.jar gui        Swing GUI
 *   java -jar mealplanner.jar cli        interactive command-line session
 *   java -jar mealplanner.jar llm-check  live test of the Gemini connection
 *   add "--data DIR" to use another data directory
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String mode = "gui";
        Path dataDir = null;
        for (int i = 0; i < args.length; i++) {
            if ("--data".equals(args[i]) && i + 1 < args.length) {
                dataDir = Path.of(args[++i]).toAbsolutePath();
            } else if (args[i].equals("-h") || args[i].equals("--help") || args[i].equals("help")) {
                mode = "help";
            } else {
                mode = args[i].toLowerCase(java.util.Locale.ROOT);
            }
        }
        AppConfig config = AppConfig.load(Path.of("").toAbsolutePath(), System.getenv());
        if (dataDir != null) {
            config = config.withDataDir(dataDir);
        }
        switch (mode) {
            case "cli" -> System.exit(runCli(config));
            case "gui" -> runGui(config);
            case "llm-check" -> System.exit(runCheck(config));
            case "help" -> System.out.println(usage());
            default -> {
                System.err.println("Unknown mode '" + mode + "'.\n" + usage());
                System.exit(2);
            }
        }
    }

    private static String usage() {
        return """
                Usage: java -jar build/libs/mealplanner.jar [gui|cli|llm-check] [--data DIR]
                  gui        open the desktop window (default)
                  cli        start the interactive command-line session
                  llm-check  test the Gemini connection with your local .env key""";
    }

    private static int runCli(AppConfig config) {
        try (AppContext context = AppContext.start(config)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            new PlannerCLI(context).run(in, System.out);
            return 0;
        } catch (StorageException e) {
            System.err.println("Cannot start: " + e.getMessage());
            return 1;
        }
    }

    private static void runGui(AppConfig config) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No graphical display is available. Use 'cli' instead.");
            System.exit(1);
        }
        try {
            AppContext context = AppContext.start(config);
            Runtime.getRuntime().addShutdownHook(new Thread(context::close));
            SwingUtilities.invokeLater(() -> new PlannerGUI(context).show());
        } catch (StorageException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "MealPlanner cannot start", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private static int runCheck(AppConfig config) {
        String problem = config.aiProblem();
        if (problem != null) {
            System.out.println(problem);
            return 1;
        }
        System.out.println("Checking " + config.geminiModel() + " (3 small requests, up to "
                + config.totalTimeout().toSeconds() + " seconds)...");
        LoggingLLMClient client = new LoggingLLMClient(new GeminiAdapter(config.geminiApiKey(), config.geminiModel(),
                config.geminiBaseUrl(), config.callTimeout()), config.dataDir().resolve("logs").resolve("agent.log"));
        ConnectionCheck.Report report = ConnectionCheck.run(client, config.totalTimeout());
        report.lines().forEach(System.out::println);
        System.out.println(report.passed() ? "RESULT: PASSED - the AI features can be used."
                : "RESULT: FAILED - fix the problem above; deterministic features still work.");
        return report.passed() ? 0 : 1;
    }
}
