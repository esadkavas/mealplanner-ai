package mealplanner.ui.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import mealplanner.domain.Ingredient;
import mealplanner.service.RecipeDocument;

/** F03 recipe form: fields are converted to a RecipeDocument and previewed before saving. */
class RecipeFormDialog {

    private final PlannerGUI gui;

    RecipeFormDialog(PlannerGUI gui) {
        this.gui = gui;
    }

    void open() {
        JDialog dialog = new JDialog(gui.frame(), "New recipe", true);
        JTextField title = new JTextField(30);
        JTextField servings = new JTextField("2", 4);
        JTextField minutes = new JTextField("20", 4);
        JTextArea ingredientLines = new JTextArea("rice=200\ngarlic=2", 8, 24);
        JTextArea steps = new JTextArea("Cook the rice.\nFry the garlic and mix.", 8, 40);
        JTextArea errors = Forms.errorArea();

        DefaultListModel<String> catalogueModel = new DefaultListModel<>();
        for (Ingredient i : gui.facade.catalogue().all()) {
            catalogueModel.addElement(Forms.label(i));
        }
        JList<String> catalogue = new JList<>(catalogueModel);
        catalogue.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && catalogue.getSelectedValue() != null) {
                    ingredientLines.append((ingredientLines.getText().endsWith("\n") || ingredientLines.getText().isEmpty()
                            ? "" : "\n") + Forms.idOf(catalogue.getSelectedValue()) + "=");
                }
            }
        });
        JPanel catalogueBox = new JPanel(new BorderLayout());
        catalogueBox.add(new JLabel("Catalogue (double-click to add):"), BorderLayout.NORTH);
        JScrollPane catalogueScroll = new JScrollPane(catalogue);
        catalogueScroll.setPreferredSize(new Dimension(260, 160));
        catalogueBox.add(catalogueScroll, BorderLayout.CENTER);

        JPanel lines = new JPanel(new GridLayout(1, 3, 8, 0));
        lines.add(labelled("Ingredients, one per line: id=quantity", ingredientLines));
        lines.add(catalogueBox);
        lines.add(labelled("Steps, one per line", steps));

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        center.add(Forms.form("Title:", title, "Base servings (1-12):", servings, "Preparation minutes (1-240):", minutes),
                BorderLayout.NORTH);
        center.add(lines, BorderLayout.CENTER);
        center.add(errors, BorderLayout.SOUTH);

        JButton preview = new JButton("Preview");
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose());
        preview.addActionListener(e -> {
            List<String> problems = new ArrayList<>();
            RecipeDocument document = toDocument(title.getText(), servings.getText(), minutes.getText(),
                    ingredientLines.getText(), steps.getText(), problems);
            if (!problems.isEmpty()) {
                Forms.showErrors(errors, problems);
                return;
            }
            gui.submit("Checking recipe...", () -> gui.facade.previewRecipe(document), result -> {
                if (result.isSuccess()) {
                    dialog.dispose();
                    gui.preview(result);
                } else {
                    Forms.showErrors(errors, result.errors());
                }
            });
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(preview);

        dialog.getContentPane().add(center, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(gui.frame());
        dialog.setVisible(true);
    }

    private static JPanel labelled(String label, JTextArea area) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        return panel;
    }

    /** Converts the text fields; number format problems are reported per field. */
    static RecipeDocument toDocument(String title, String servings, String minutes, String ingredientText,
                                     String stepText, List<String> problems) {
        Integer servingCount = wholeNumber("servings", servings, problems);
        Integer minuteCount = wholeNumber("minutes", minutes, problems);
        List<RecipeDocument.Line> lines = new ArrayList<>();
        for (String raw : ingredientText.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                problems.add("ingredients: '" + line + "' must look like id=quantity.");
                continue;
            }
            try {
                lines.add(new RecipeDocument.Line(line.substring(0, eq).trim(), new BigDecimal(line.substring(eq + 1).trim())));
            } catch (NumberFormatException e) {
                problems.add("ingredients: quantity in '" + line + "' is not a number.");
            }
        }
        List<String> steps = new ArrayList<>();
        for (String step : stepText.split("\n")) {
            if (!step.isBlank()) {
                steps.add(step.trim());
            }
        }
        return new RecipeDocument(title, servingCount, minuteCount, lines, steps);
    }

    private static Integer wholeNumber(String field, String text, List<String> problems) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            problems.add(field + ": '" + text + "' is not a whole number.");
            return null;
        }
    }
}
