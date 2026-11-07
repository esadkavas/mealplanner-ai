package mealplanner.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A provider-neutral tool declaration. {@code parameters} is a small JSON-Schema subset
 * (object / string / integer / number / boolean / array, properties, required, items, enum)
 * that the adapter translates for its provider and ToolRegistry uses to check arguments.
 */
public record ToolSpec(String name, String description, JsonNode parameters) {
}
