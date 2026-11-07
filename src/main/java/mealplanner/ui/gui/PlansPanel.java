package mealplanner.ui.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

import mealplanner.domain.AppState;
import mealplanner.domain.MealPlan;
import mealplanner.domain.MealSlot;
import mealplanner.domain.PlanRequest;
import mealplanner.ui.TextFormat;

/** F06 generate (AI), F07 revise (AI) and F10 shopping list for saved plans. */
class PlansPanel extends JPanel {

    private final PlannerGUI gui;
    private final JTextField start = new JTextField(10);
    private final JSpinner days = new JSpinner(new SpinnerNumberModel(7, 1, 7, 1));
    private final JCheckBox breakfast = new JCheckBox("Breakfast");
    private final JCheckBox lunch = new JCheckBox("Lunch");
    private final JCheckBox dinner = new JCheckBox("Dinner", true);
    private final JTextField servings = new JTextField(3);
    private final JCheckBox noRepeat = new JCheckBox("No repeats");
    private final JTextField wishes = new JTextField(28);
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> plans = new JList<>(listModel);
    private final List<String> aliases = new ArrayList<>();
    private final JTextArea detail = PlannerGUI.textArea("Select a saved plan.");

    PlansPanel(PlannerGUI gui) {
        super(new BorderLayout(8, 8));
        this.gui = gui;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        start.setText(LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString());

        JButton generate = new JButton("Generate plan (AI)");
        generate.addActionListener(e -> generate());
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Start (YYYY-MM-DD):"));
        row1.add(start);
        row1.add(new JLabel("Days:"));
        row1.add(days);
        row1.add(breakfast);
        row1.add(lunch);
        row1.add(dinner);
        row1.add(new JLabel("Servings:"));
        row1.add(servings);
        row1.add(noRepeat);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(new JLabel("Wishes (optional):"));
        row2.add(wishes);
        row2.add(generate);
        JPanel generator = new JPanel(new BorderLayout());
        generator.setBorder(BorderFactory.createTitledBorder("New plan - preview before saving"));
        generator.add(row1, BorderLayout.NORTH);
        generator.add(row2, BorderLayout.SOUTH);

        plans.setFont(PlannerGUI.MONO);
        plans.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        plans.addListSelectionListener(e -> showSelected());
        JPanel left = new JPanel(new BorderLayout());
        left.add(new JLabel("Saved plans"), BorderLayout.NORTH);
        left.add(new JScrollPane(plans), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(detail));
        split.setResizeWeight(0.35);
        split.setDividerLocation(470);

        JButton revise = new JButton("Revise with AI...");
        revise.addActionListener(e -> revise());
        JButton shopping = new JButton("Shopping list");
        shopping.addActionListener(e -> shopping());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(revise);
        actions.add(shopping);
        actions.add(new JLabel("Revisions are saved as new plans; the original is never overwritten."));

        add(generator, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        gui.onRefresh(this::refresh);
    }

    private void refresh() {
        AppState state = gui.snapshot();
        if (servings.getText().isBlank()) {
            servings.setText(String.valueOf(state.getProfile().householdServings()));
        }
        listModel.clear();
        aliases.clear();
        for (MealPlan plan : state.getPlans().values()) {
            aliases.add(state.planAlias(plan.getId()));
            listModel.addElement(TextFormat.planLine(state, plan));
        }
        detail.setText(state.getPlans().isEmpty() ? "No saved plans yet. Generate one above." : "Select a saved plan.");
    }

    private String selectedAlias() {
        int index = plans.getSelectedIndex();
        return index < 0 ? null : aliases.get(index);
    }

    private void showSelected() {
        String alias = selectedAlias();
        if (alias != null) {
            AppState state = gui.snapshot();
            state.findPlan(alias).ifPresent(plan -> {
                detail.setText(TextFormat.plan(state, plan));
                detail.setCaretPosition(0);
            });
        }
    }

    private void generate() {
        LocalDate date;
        int servingCount;
        try {
            date = LocalDate.parse(start.getText().trim());
        } catch (DateTimeParseException e) {
            gui.showText("Invalid plan request", "start: '" + start.getText() + "' is not a date like 2026-11-09.", true);
            return;
        }
        try {
            servingCount = Integer.parseInt(servings.getText().trim());
        } catch (NumberFormatException e) {
            gui.showText("Invalid plan request", "servings: '" + servings.getText() + "' is not a whole number.", true);
            return;
        }
        Set<MealSlot> slots = EnumSet.noneOf(MealSlot.class);
        if (breakfast.isSelected()) {
            slots.add(MealSlot.BREAKFAST);
        }
        if (lunch.isSelected()) {
            slots.add(MealSlot.LUNCH);
        }
        if (dinner.isSelected()) {
            slots.add(MealSlot.DINNER);
        }
        PlanRequest request = new PlanRequest(date, (Integer) days.getValue(), slots, servingCount, noRepeat.isSelected());
        String text = wishes.getText();
        gui.submit("The AI agent is planning " + request.mealCount() + " meal(s) (up to 90 s)...",
                () -> gui.facade.createPlan(request, text), gui::preview);
    }

    private void revise() {
        String alias = selectedAlias();
        if (alias == null) {
            gui.showText("Revise plan", "Select a saved plan first.", true);
            return;
        }
        String request = JOptionPane.showInputDialog(gui.frame(), "How should " + alias + " change?\n"
                + "Example: Replace Tuesday dinner with a lentil dish", "Revise " + alias + " with AI", JOptionPane.QUESTION_MESSAGE);
        if (request == null) {
            return;
        }
        gui.submit("The AI agent is revising " + alias + " (up to 90 s)...", () -> gui.facade.revisePlan(alias, request), gui::preview);
    }

    private void shopping() {
        String alias = selectedAlias();
        if (alias == null) {
            gui.showText("Shopping list", "Select a saved plan first.", true);
            return;
        }
        gui.submit("Totalling ingredients...", () -> gui.facade.shopping(alias), result -> {
            if (result.isSuccess()) {
                detail.setText(TextFormat.shopping(gui.snapshot(), result.data()));
                detail.setCaretPosition(0);
                gui.status(result.message());
            } else {
                gui.showErrors(result);
            }
        });
    }
}
