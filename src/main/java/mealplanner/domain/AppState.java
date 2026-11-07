package mealplanner.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Everything the user has saved: profile, pantry, recipes, plans and a revision counter.
 *
 * <p>Changes are always made to a {@link #deepCopy()} and only become active after the copy has
 * been saved, so a failed save leaves the active state untouched.</p>
 *
 * <p>Recipes and plans keep insertion order, which gives every stored object a stable short
 * alias (R01, R02, ... and P01, P02, ...) for the CLI and the agent tools.</p>
 */
public class AppState {

    public static final int SCHEMA_VERSION = 1;

    private int schemaVersion = SCHEMA_VERSION;
    private long revision;
    private PreferenceProfile profile = PreferenceProfile.defaults();
    private TreeMap<String, BigDecimal> pantry = new TreeMap<>();
    private LinkedHashMap<UUID, Recipe> recipes = new LinkedHashMap<>();
    private LinkedHashMap<UUID, MealPlan> plans = new LinkedHashMap<>();

    public AppState() {
    }

    public AppState deepCopy() {
        AppState copy = new AppState();
        copy.schemaVersion = schemaVersion;
        copy.revision = revision;
        copy.profile = profile;                       // immutable record
        copy.pantry = new TreeMap<>(pantry);          // BigDecimal is immutable
        recipes.forEach((id, r) -> copy.recipes.put(id, r.deepCopy()));
        plans.forEach((id, p) -> copy.plans.put(id, p.deepCopy()));
        return copy;
    }

    // ---- aliases -------------------------------------------------------------------------

    public String recipeAlias(UUID id) {
        return alias("R", new ArrayList<>(recipes.keySet()), id);
    }

    public String planAlias(UUID id) {
        return alias("P", new ArrayList<>(plans.keySet()), id);
    }

    /** Finds a recipe by alias (R03), full UUID, or a unique UUID prefix of 4+ characters. */
    public Optional<Recipe> findRecipe(String ref) {
        return resolve(ref, "R", recipes);
    }

    /** Finds a plan by alias (P01), full UUID, or a unique UUID prefix of 4+ characters. */
    public Optional<MealPlan> findPlan(String ref) {
        return resolve(ref, "P", plans);
    }

    private static String alias(String prefix, List<UUID> ids, UUID id) {
        int index = ids.indexOf(id);
        return index < 0 ? "?" : String.format(Locale.ROOT, "%s%02d", prefix, index + 1);
    }

    private static <T> Optional<T> resolve(String ref, String prefix, Map<UUID, T> map) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String text = ref.trim();
        if (text.toUpperCase(Locale.ROOT).startsWith(prefix) && text.length() > 1
                && text.substring(1).chars().allMatch(Character::isDigit)) {
            int index = Integer.parseInt(text.substring(1)) - 1;
            List<T> values = new ArrayList<>(map.values());
            return index >= 0 && index < values.size() ? Optional.of(values.get(index)) : Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<T> matches = new ArrayList<>();
        for (Map.Entry<UUID, T> e : map.entrySet()) {
            String id = e.getKey().toString();
            if (id.equals(lower) || (lower.length() >= 4 && id.startsWith(lower))) {
                matches.add(e.getValue());
            }
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    // ---- accessors ------------------------------------------------------------------------

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public long getRevision() {
        return revision;
    }

    public void incrementRevision() {
        revision++;
    }

    public PreferenceProfile getProfile() {
        return profile;
    }

    public void setProfile(PreferenceProfile profile) {
        this.profile = profile;
    }

    public Map<String, BigDecimal> getPantry() {
        return Collections.unmodifiableMap(pantry);
    }

    public BigDecimal pantryQuantity(String ingredientId) {
        return pantry.getOrDefault(ingredientId, BigDecimal.ZERO);
    }

    public void setPantryQuantity(String ingredientId, BigDecimal quantity) {
        pantry.put(ingredientId, quantity);
    }

    public void removePantryItem(String ingredientId) {
        pantry.remove(ingredientId);
    }

    public Map<UUID, Recipe> getRecipes() {
        return Collections.unmodifiableMap(recipes);
    }

    public void addRecipe(Recipe recipe) {
        recipes.put(recipe.getId(), recipe);
    }

    public Map<UUID, MealPlan> getPlans() {
        return Collections.unmodifiableMap(plans);
    }

    public void addPlan(MealPlan plan) {
        plans.put(plan.getId(), plan);
    }
}
