package mealplanner.ui.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;

import mealplanner.domain.Catalogue;
import mealplanner.domain.Ingredient;
import mealplanner.domain.Quantities;
import mealplanner.service.PantryInput;

/** F02: pantry table with ingredient selector, quantity editor and Remove action. */
class PantryPanel extends JPanel {

    private final PlannerGUI gui;
    private final DefaultTableModel model = new DefaultTableModel(new Object[] {"Ingredient ID", "Name", "Quantity", "Unit"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JComboBox<String> ingredient = new JComboBox<>();
    private final JTextField quantity = new JTextField(8);
    private final JLabel unit = new JLabel();
    private final JTextArea errors = Forms.errorArea();

    PantryPanel(PlannerGUI gui) {
        super(new BorderLayout(8, 8));
        this.gui = gui;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        Catalogue catalogue = gui.facade.catalogue();
        for (Ingredient i : catalogue.all()) {
            ingredient.addItem(Forms.label(i));
        }
        ingredient.addActionListener(e -> unit.setText(catalogue.unitLabel(Forms.idOf(ingredient.getSelectedItem()))));
        unit.setText(catalogue.unitLabel(Forms.idOf(ingredient.getSelectedItem())));
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                String id = (String) model.getValueAt(row, 0);
                ingredient.setSelectedItem(Forms.label(catalogue.get(id)));
                quantity.setText((String) model.getValueAt(row, 2));
            }
        });

        JButton set = new JButton("Set quantity");
        set.addActionListener(e -> change(PantryInput.set(Forms.idOf(ingredient.getSelectedItem()), quantity.getText())));
        JButton remove = new JButton("Remove selected");
        remove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                errors.setText("Select a pantry row to remove.");
                return;
            }
            change(PantryInput.remove((String) model.getValueAt(row, 0)));
        });
        JPanel editor = new JPanel(new FlowLayout(FlowLayout.LEFT));
        editor.add(new JLabel("Ingredient:"));
        editor.add(ingredient);
        editor.add(new JLabel("Quantity:"));
        editor.add(quantity);
        editor.add(unit);
        editor.add(set);
        editor.add(remove);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(editor, BorderLayout.NORTH);
        bottom.add(errors, BorderLayout.CENTER);
        bottom.add(new JLabel("Quantities use each ingredient's canonical unit. Zero means unavailable. "
                + "Creating plans never changes the pantry."), BorderLayout.SOUTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        gui.onRefresh(this::refresh);
    }

    private void change(PantryInput input) {
        gui.submit("Saving pantry...", () -> gui.facade.updatePantry(input), result -> {
            if (result.isSuccess()) {
                errors.setText("");
                gui.status(result.message());
                gui.refreshAll();
            } else {
                Forms.showErrors(errors, result.errors());
                gui.status("Pantry was not changed.");
            }
        });
    }

    private void refresh() {
        model.setRowCount(0);
        Catalogue catalogue = gui.facade.catalogue();
        for (Map.Entry<String, BigDecimal> e : gui.snapshot().getPantry().entrySet()) {
            model.addRow(new Object[] {e.getKey(), catalogue.nameOf(e.getKey()), Quantities.display(e.getValue()),
                    catalogue.unitLabel(e.getKey())});
        }
    }
}
