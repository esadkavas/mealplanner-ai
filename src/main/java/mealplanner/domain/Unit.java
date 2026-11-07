package mealplanner.domain;

/** The single canonical unit of a catalogue ingredient. No automatic conversion between units. */
public enum Unit {
    GRAM("g"),
    MILLILITRE("ml"),
    COUNT("count");

    private final String label;

    Unit(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
