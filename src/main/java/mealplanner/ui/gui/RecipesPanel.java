package mealplanner.ui.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;

import mealplanner.domain.AppState;
import mealplanner.domain.IngredientAmount;
import mealplanner.domain.Recipe;
import mealplanner.service.SearchFilter;
import mealplanner.ui.TextFormat;

/**
 * F03 create/import, F04 search, F08 substitution (AI) and F09 scaling. The list shows either
 * every stored recipe (with "ineligible" labels) or the current search result.
 */
class RecipesPanel extends JPanel {

    private final PlannerGUI gui;
    private final JTextField text = new JTextField(12);
    private final JTextField ingredients = new JTextField(12);
    private final JTextField maxMinutes = new JTextField(4);
    private final JCheckBox pantryOnly = new JCheckBox("Pantry only");
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> list = new JList<>(listModel);
    private final List<String> aliases = new ArrayList<>();
    private final JTextArea detail = PlannerGUI.textArea("Select a recipe.");
    private final JLabel listTitle = new JLabel("All stored recipes");
    private List<Recipe> lastSearch;

    RecipesPanel(PlannerGUI gui) {
        super(new BorderLayout(8, 8));
        this.gui = gui;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton search = new JButton("Search");
        search.addActionListener(e -> search());
        JButton all = new JButton("Show all");
        all.addActionListener(e -> {
            lastSearch = null;
            refresh();
        });
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filters.add(new JLabel("Title contains:"));
        filters.add(text);
        filters.add(new JLabel("Ingredient IDs (comma):"));
        filters.add(ingredients);
        filters.add(new JLabel("Max minutes:"));
        filters.add(maxMinutes);
        filters.add(pantryOnly);
        filters.add(search);
        filters.add(all);

        list.setFont(PlannerGUI.MONO);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> showSelected());
        JPanel left = new JPanel(new BorderLayout());
        left.add(listTitle, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(detail));
        split.setResizeWeight(0.35);
        split.setDividerLocation(470);

        JButton create = new JButton("New recipe...");
        create.addActionListener(e -> new RecipeFormDialog(gui).open());
        JButton importJson = new JButton("Import JSON...");
        importJson.addActionListener(e -> importJson());
        JButton scale = new JButton("Scale servings...");
        scale.addActionListener(e -> scale());
        JButton substitute = new JButton("Substitute ingredient (AI)...");
        substitute.addActionListener(e -> substitute());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(create);
        actions.add(importJson);
        actions.add(scale);
        actions.add(substitute);

        add(filters, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
        gui.onRefresh(this::refresh);
    }

    private void refresh() {
        AppState state = gui.snapshot();
        List<Recipe> recipes = lastSearch != null ? lastSearch : new ArrayList<>(state.getRecipes().values());
        listTitle.setText(lastSearch != null ? "Search results (" + recipes.size() + ")" : "All stored recipes (" + recipes.size() + ")");
        listModel.clear();
        aliases.clear();
        for (Recipe recipe : recipes) {
            if (state.getRecipes().containsKey(recipe.getId())) {
                aliases.add(state.recipeAlias(recipe.getId()));
                listModel.addElement(TextFormat.recipeLine(state, recipe, gui.facade.isEligible(recipe)));
            }
        }
        detail.setText(recipes.isEmpty() && lastSearch != null
                ? "No eligible recipe matches these filters.\nTry changing the filters; your saved preferences are always applied."
                : "Select a recipe.");
    }

    private void search() {
        Integer max;
        try {
            max = maxMinutes.getText().isBlank() ? null : Integer.valueOf(maxMinutes.getText().trim());
        } catch (NumberFormatException e) {
            gui.showText("Invalid filter", "max-minutes: '" + maxMinutes.getText() + "' is not a whole number.", true);
            return;
        }
        SearchFilter filter = new SearchFilter(text.getText(), List.of(ingredients.getText().split(",")), max, pantryOnly.isSelected());
        gui.submit("Searching...", () -> gui.facade.search(filter), result -> {
            if (result.isSuccess()) {
                lastSearch = result.data();
                refresh();
                gui.status(result.message());
            } else {
                gui.showErrors(result);
            }
        });
    }

    private Recipe selected() {
        int index = list.getSelectedIndex();
        if (index < 0) {
            return null;
        }
        return gui.snapshot().findRecipe(aliases.get(index)).orElse(null);
    }

    private void showSelected() {
        Recipe recipe = selected();
        if (recipe != null) {
            detail.setText(TextFormat.recipe(gui.snapshot(), recipe, gui.facade.catalogue(), gui.facade.isEligible(recipe)));
            detail.setCaretPosition(0);
        }
    }

    private void importJson() {
        JFileChooser chooser = new JFileChooser(new File("samples").isDirectory() ? new File("samples") : new File("."));
        chooser.setFileFilter(new FileNameExtensionFilter("Recipe JSON (*.json)", "json"));
        if (chooser.showOpenDialog(gui.frame()) == JFileChooser.APPROVE_OPTION) {
            Path path = chooser.getSelectedFile().toPath();
            gui.submit("Reading " + path.getFileName() + "...", () -> gui.facade.previewRecipeFile(path), gui::preview);
        }
    }

    private void scale() {
        Recipe recipe = selected();
        if (recipe == null) {
            gui.showText("Scale servings", "Select a recipe first.", true);
            return;
        }
        String answer = JOptionPane.showInputDialog(gui.frame(), "New serving count for '" + recipe.getTitle()
                + "' (currently " + recipe.getServings() + ", allowed 1-12):", "Scale servings", JOptionPane.QUESTION_MESSAGE);
        if (answer == null) {
            return;
        }
        int servings;
        try {
            servings = Integer.parseInt(answer.trim());
        } catch (NumberFormatException e) {
            gui.showText("Scale servings", "servings: '" + answer + "' is not a whole number.", true);
            return;
        }
        String alias = gui.snapshot().recipeAlias(recipe.getId());
        gui.submit("Scaling...", () -> gui.facade.scale(alias, servings), gui::preview);
    }

    private void substitute() {
        Recipe recipe = selected();
        if (recipe == null) {
            gui.showText("Substitute ingredient", "Select a recipe first.", true);
            return;
        }
        JComboBox<String> choice = new JComboBox<>();
        for (IngredientAmount amount : recipe.getIngredients()) {
            choice.addItem(Forms.label(gui.facade.catalogue().get(amount.ingredientId())));
        }
        JTextField request = new JTextField("Use a vegan alternative", 30);
        JPanel form = Forms.form("Ingredient to replace:", choice, "What do you want?", request);
        int ok = JOptionPane.showConfirmDialog(gui.frame(), form, "Substitute ingredient in " + recipe.getTitle() + " (AI)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        String alias = gui.snapshot().recipeAlias(recipe.getId());
        String ingredientId = Forms.idOf(choice.getSelectedItem());
        String text = request.getText();
        gui.submit("Asking the AI agent for a substitution (up to 90 s)...",
                () -> gui.facade.substitute(alias, ingredientId, text), gui::preview);
    }
}
