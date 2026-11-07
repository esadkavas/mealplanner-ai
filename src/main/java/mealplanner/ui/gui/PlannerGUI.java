package mealplanner.ui.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import mealplanner.app.AppContext;
import mealplanner.domain.AppState;
import mealplanner.facade.PlannerFacade;
import mealplanner.facade.Proposal;
import mealplanner.facade.Result;
import mealplanner.llm.LoggingLLMClient;
import mealplanner.ui.TextFormat;

/**
 * Swing desktop interface. Like the CLI it only collects input and displays results; all rules
 * live behind PlannerFacade. Every facade call goes through {@link #submit}, which runs it on a
 * SwingWorker background thread (file and network work never block the event-dispatch thread)
 * and blocks further input until it finishes.
 */
public class PlannerGUI {

    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    static final Color ERROR = new Color(170, 20, 20);

    final PlannerFacade facade;
    private final LoggingLLMClient llm;
    private final List<Runnable> refreshers = new ArrayList<>();
    private JFrame frame;
    private JLabel statusLabel;
    private JLabel aiLabel;
    private JProgressBar progress;
    private JComponent blocker;

    public PlannerGUI(AppContext context) {
        this(context.facade(), context.llm());
    }

    public PlannerGUI(PlannerFacade facade, LoggingLLMClient llm) {
        this.facade = facade;
        this.llm = llm;
    }

    public void show() {
        frame = new JFrame("MealPlanner AI");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1000, 680));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Preferences (F01)", new PreferencesPanel(this));
        tabs.addTab("Pantry (F02)", new PantryPanel(this));
        tabs.addTab("Recipes (F03 F04 F08 F09)", new RecipesPanel(this));
        tabs.addTab("Recommend - AI (F05)", new RecommendPanel(this));
        tabs.addTab("Meal plans (F06 F07 F10)", new PlansPanel(this));

        statusLabel = new JLabel("Ready.");
        aiLabel = new JLabel();
        progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(120, 14));
        JPanel status = new JPanel(new BorderLayout(8, 0));
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.add(statusLabel, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(progress);
        right.add(aiLabel);
        status.add(right, BorderLayout.EAST);

        frame.getContentPane().add(tabs, BorderLayout.CENTER);
        frame.getContentPane().add(status, BorderLayout.SOUTH);

        blocker = new JPanel();
        blocker.setOpaque(false);
        blocker.addMouseListener(new MouseAdapter() { });
        blocker.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        frame.setGlassPane(blocker);

        updateAiLabel();
        refreshAll();
        frame.pack();
        frame.setSize(1150, 760);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ---- shared behaviour used by the panels -----------------------------------------------------------

    /**
     * Runs one facade operation in the background, then shows the result on the EDT. While it runs,
     * input is blocked and a progress bar is shown.
     */
    <T> void submit(String busyText, Supplier<Result<T>> action, Consumer<Result<T>> display) {
        setBusy(true, busyText);
        new SwingWorker<Result<T>, Void>() {
            @Override
            protected Result<T> doInBackground() {
                return action.get();
            }

            @Override
            protected void done() {
                setBusy(false, null);
                try {
                    Result<T> result = get();
                    display.accept(result);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    showText("Unexpected error", "Unexpected error: " + e.getCause(), true);
                }
                updateAiLabel();
            }
        }.execute();
    }

    /** Shows an AI or recipe preview with Accept / Reject buttons. Saving only happens on Accept. */
    void preview(Result<Proposal> result) {
        if (!result.isSuccess()) {
            showErrors(result);
            return;
        }
        Proposal proposal = result.data();
        JTextArea text = textArea(TextFormat.proposal(facade.snapshot(), proposal, facade.catalogue()));
        JDialog dialog = new JDialog(frame, "Preview - not saved yet", true);
        JButton accept = new JButton("Accept and save");
        JButton reject = new JButton("Reject");
        accept.addActionListener(e -> {
            dialog.dispose();
            submit("Saving...", () -> facade.accept(proposal), saved -> {
                if (saved.isSuccess()) {
                    status(saved.data());
                    refreshAll();
                } else {
                    showErrors(saved);
                }
            });
        });
        reject.addActionListener(e -> {
            dialog.dispose();
            status(facade.reject(proposal).message());
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(reject);
        buttons.add(accept);
        dialog.getContentPane().add(new JScrollPane(text), BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.setSize(820, 620);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    void showErrors(Result<?> result) {
        showText("Could not complete the request", TextFormat.errors(result), true);
        status("Request failed; nothing was saved.");
    }

    void showText(String title, String body, boolean error) {
        JTextArea text = textArea(body);
        if (error) {
            text.setForeground(ERROR);
        }
        JScrollPane scroll = new JScrollPane(text);
        scroll.setPreferredSize(new Dimension(720, Math.min(480, 80 + 18 * body.split("\n").length)));
        JOptionPane.showMessageDialog(frame, scroll, title, error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    void status(String message) {
        statusLabel.setText(message);
    }

    void onRefresh(Runnable refresher) {
        refreshers.add(refresher);
    }

    void refreshAll() {
        refreshers.forEach(Runnable::run);
    }

    AppState snapshot() {
        return facade.snapshot();
    }

    JFrame frame() {
        return frame;
    }

    static JTextArea textArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setFont(MONO);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        return area;
    }

    private void setBusy(boolean busy, String text) {
        blocker.setVisible(busy);
        progress.setVisible(busy);
        if (text != null) {
            status(text);
        }
    }

    private void updateAiLabel() {
        if (facade.aiUnavailableReason() != null) {
            aiLabel.setText("AI unavailable (no API key) - other features work");
            aiLabel.setToolTipText(facade.aiUnavailableReason());
            aiLabel.setForeground(ERROR);
        } else if (llm != null) {
            LoggingLLMClient.UsageSummary u = llm.usage();
            aiLabel.setText("AI: " + facade.aiDescription() + " | session: " + u.calls() + " calls, "
                    + (u.inputTokens() + u.outputTokens()) + " tokens");
            aiLabel.setForeground(Color.DARK_GRAY);
        }
    }
}
