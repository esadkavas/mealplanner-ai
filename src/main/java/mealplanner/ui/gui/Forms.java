package mealplanner.ui.gui;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import mealplanner.domain.Ingredient;

/** Small layout helpers shared by the GUI panels. */
final class Forms {

    private Forms() {
    }

    /** A two-column label/field form. */
    static JPanel form(Object... labelsAndFields) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < labelsAndFields.length; i += 2) {
            c.gridx = 0;
            c.gridy = i / 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            panel.add(new JLabel((String) labelsAndFields[i]), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            panel.add((JComponent) labelsAndFields[i + 1], c);
        }
        return panel;
    }

    /** A red, read-only area for field-level validation errors. */
    static JTextArea errorArea() {
        JTextArea area = new JTextArea(3, 40);
        area.setEditable(false);
        area.setOpaque(false);
        area.setForeground(PlannerGUI.ERROR);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        return area;
    }

    static void showErrors(JTextArea area, List<String> errors) {
        area.setText(String.join("\n", errors));
    }

    /** Display text for an ingredient in combo boxes and lists, e.g. "rice - Rice (g)". */
    static String label(Ingredient ingredient) {
        return ingredient.id() + " - " + ingredient.name() + " (" + ingredient.unit().label() + ")";
    }

    /** Recovers the ingredient ID from {@link #label(Ingredient)}. */
    static String idOf(Object label) {
        String text = String.valueOf(label);
        int dash = text.indexOf(" - ");
        return dash < 0 ? text.trim() : text.substring(0, dash).trim();
    }
}
