package mealplanner.ui.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import mealplanner.ui.TextFormat;

/** F05: request box and ranked recommendations with reasons, shortages and the agent's steps. */
class RecommendPanel extends JPanel {

    RecommendPanel(PlannerGUI gui) {
        super(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTextArea request = new JTextArea("Use my lentils and keep meals under 30 minutes", 3, 60);
        request.setLineWrap(true);
        request.setWrapStyleWord(true);
        JTextArea output = PlannerGUI.textArea("Recommendations will appear here. Nothing is saved by this feature.");

        JButton recommend = new JButton("Recommend recipes (AI)");
        recommend.addActionListener(e -> {
            String text = request.getText();
            gui.submit("Asking the AI agent for recommendations (up to 90 s)...", () -> gui.facade.recommend(text), result -> {
                if (result.isSuccess()) {
                    output.setText(result.message() + "\n\n" + TextFormat.recommendations(result.data(), gui.facade.catalogue()));
                    gui.status(result.message());
                } else {
                    output.setText(TextFormat.errors(result));
                    gui.status("No recommendations; nothing was saved.");
                }
                output.setCaretPosition(0);
            });
        });

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel("What would you like? (up to 2,000 characters; your saved preferences and pantry are used automatically)"),
                BorderLayout.NORTH);
        top.add(new JScrollPane(request), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(recommend);
        top.add(buttons, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(output), BorderLayout.CENTER);
    }
}
