package mealplanner.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The fixed ingredient catalogue (stable IDs and canonical units). Immutable after loading. */
public final class Catalogue {

    private final Map<String, Ingredient> ingredients;

    public Catalogue(List<Ingredient> list) {
        Map<String, Ingredient> map = new LinkedHashMap<>();
        for (Ingredient ingredient : list) {
            if (map.put(ingredient.id(), ingredient) != null) {
                throw new IllegalArgumentException("Duplicate catalogue ingredient ID: " + ingredient.id());
            }
        }
        this.ingredients = Collections.unmodifiableMap(map);
    }

    public Optional<Ingredient> find(String id) {
        return Optional.ofNullable(id == null ? null : ingredients.get(id));
    }

    public Ingredient get(String id) {
        Ingredient ingredient = ingredients.get(id);
        if (ingredient == null) {
            throw new IllegalArgumentException("Unknown ingredient ID: " + id);
        }
        return ingredient;
    }

    public boolean contains(String id) {
        return id != null && ingredients.containsKey(id);
    }

    public Collection<Ingredient> all() {
        return ingredients.values();
    }

    public String unitLabel(String id) {
        return find(id).map(i -> i.unit().label()).orElse("?");
    }

    public String nameOf(String id) {
        return find(id).map(Ingredient::name).orElse(id);
    }
}
