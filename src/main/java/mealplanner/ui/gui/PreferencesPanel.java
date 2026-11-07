package mealplanner.ui.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

import mealplanner.domain.Ingredient;
import mealplanner.domain.PreferenceProfile;
import mealplanner.service.ProfileInput;

/** F01: preferences form with Save and field-level validation messages. */
class PreferencesPanel extends JPanel {

    private final PlannerGUI gui;
    private final JComboBox<String> diet = new JComboBox<>(new String[] {"unrestricted", "vegetarian", "vegan"});
    private final JTextField servings = new JTextField(6);
    private final JTextField maxMinutes = new JTextField(6);
    private final DefaultListModel<String> ingredientModel = new DefaultListModel<>();
    private final JList<String> excluded = new JList<>(ingredientModel);
    private final JTextArea errors = Forms.errorArea();

    PreferencesPanel(PlannerGUI gui) {
        super(new BorderLayout(8, 8));
        this.gui = gui;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        for (Ingredient ingredient : gui.facade.catalogue().all()) {
            ingredientModel.addElement(Forms.label(ingredient));
        }
        excluded.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        excluded.setVisibleRowCount(14);

        JPanel form = Forms.form(
                "Diet:", diet,
                "Household servings (1-12):", servings,
                "Maximum preparation minutes (1-240):", maxMinutes);
        JPanel exclusions = new JPanel(new BorderLayout(4, 4));
        exclusions.add(new JLabel("Excluded ingredients (Ctrl/Cmd-click to select several):"), BorderLayout.NORTH);
        exclusions.add(new JScrollPane(excluded), BorderLayout.CENTER);

        JButton save = new JButton("Save preferences");
        save.addActionListener(e -> save());
        JButton revert = new JButton("Revert");
        revert.addActionListener(e -> refresh());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(save);
        buttons.add(revert);

        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.CENTER);
        top.add(errors, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(exclusions, BorderLayout.CENTER);
        add(new JLabel("Diet eligibility uses catalogue metadata only; it is not a medical allergy check."), BorderLayout.SOUTH);
        gui.onRefresh(this::refresh);
    }

    private void refresh() {
        PreferenceProfile profile = gui.snapshot().getProfile();
        diet.setSelectedItem(profile.diet().label());
        servings.setText(String.valueOf(profile.householdServings()));
        maxMinutes.setText(String.valueOf(profile.maxMinutes()));
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < ingredientModel.size(); i++) {
            if (profile.excludedIds().contains(Forms.idOf(ingredientModel.get(i)))) {
                selected.add(i);
            }
        }
        excluded.setSelectedIndices(selected.stream().mapToInt(Integer::intValue).toArray());
        errors.setText("");
    }

    private void save() {
        List<String> ids = new ArrayList<>();
        for (String label : excluded.getSelectedValuesList()) {
            ids.add(Forms.idOf(label));
        }
        ProfileInput input = new ProfileInput((String) diet.getSelectedItem(), ids, servings.getText(), maxMinutes.getText());
        gui.submit("Saving preferences...", () -> gui.facade.updateProfile(input), result -> {
            if (result.isSuccess()) {
                gui.status(result.message());
                gui.refreshAll();
            } else {
                Forms.showErrors(errors, result.errors());
                gui.status("Preferences were not saved; fix the highlighted fields.");
            }
        });
    }
}
