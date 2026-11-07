package mealplanner.agent;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds and checks the small JSON-Schema subset used for tool parameters and final answers:
 * object, string (optional enum), integer, number, boolean and array.
 */
public final class Schemas {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Schemas() {
    }

    public static ObjectNode object(String[] required, Object... namesAndSchemas) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "object");
        ObjectNode properties = node.putObject("properties");
        for (int i = 0; i < namesAndSchemas.length; i += 2) {
            properties.set((String) namesAndSchemas[i], (JsonNode) namesAndSchemas[i + 1]);
        }
        ArrayNode req = node.putArray("required");
        for (String name : required) {
            req.add(name);
        }
        return node;
    }

    public static ObjectNode string(String description) {
        return typed("string", description);
    }

    public static ObjectNode enumeration(String description, String... values) {
        ObjectNode node = typed("string", description);
        ArrayNode list = node.putArray("enum");
        for (String value : values) {
            list.add(value);
        }
        return node;
    }

    public static ObjectNode integer(String description) {
        return typed("integer", description);
    }

    public static ObjectNode number(String description) {
        return typed("number", description);
    }

    public static ObjectNode bool(String description) {
        return typed("boolean", description);
    }

    public static ObjectNode array(String description, JsonNode items) {
        ObjectNode node = typed("array", description);
        node.set("items", items);
        return node;
    }

    public static ObjectNode array(String description, JsonNode items, int maxItems) {
        ObjectNode node = array(description, items);
        node.put("maxItems", maxItems);
        return node;
    }

    private static ObjectNode typed(String type, String description) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        if (description != null) {
            node.put("description", description);
        }
        return node;
    }

    /** Returns problems with {@code value} against {@code schema}; empty means valid. */
    public static void check(JsonNode schema, JsonNode value, String path, List<String> problems) {
        String type = schema.path("type").asText();
        switch (type) {
            case "object" -> {
                if (!value.isObject()) {
                    problems.add(path + " must be an object.");
                    return;
                }
                JsonNode properties = schema.path("properties");
                for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = it.next();
                    if (!properties.has(field.getKey())) {
                        problems.add(path + "." + field.getKey() + " is not a supported argument.");
                    } else if (!field.getValue().isNull()) {
                        check(properties.get(field.getKey()), field.getValue(), path + "." + field.getKey(), problems);
                    }
                }
                for (JsonNode name : schema.path("required")) {
                    if (!value.hasNonNull(name.asText())) {
                        problems.add(path + "." + name.asText() + " is required.");
                    }
                }
            }
            case "string" -> {
                if (!value.isTextual()) {
                    problems.add(path + " must be a string.");
                } else if (value.asText().length() > 2000) {
                    problems.add(path + " is too long.");
                } else if (schema.has("enum")) {
                    boolean found = false;
                    for (JsonNode allowed : schema.get("enum")) {
                        found |= allowed.asText().equalsIgnoreCase(value.asText());
                    }
                    if (!found) {
                        problems.add(path + " must be one of " + schema.get("enum") + ".");
                    }
                }
            }
            case "integer" -> {
                if (!(value.isIntegralNumber() || (value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0))) {
                    problems.add(path + " must be a whole number.");
                }
            }
            case "number" -> {
                if (!value.isNumber()) {
                    problems.add(path + " must be a number.");
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    problems.add(path + " must be true or false.");
                }
            }
            case "array" -> {
                if (!value.isArray()) {
                    problems.add(path + " must be a list.");
                    return;
                }
                if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                    problems.add(path + " may contain at most " + schema.get("maxItems").asInt() + " items.");
                }
                int i = 0;
                for (JsonNode item : value) {
                    check(schema.path("items"), item, path + "[" + i++ + "]", problems);
                }
            }
            default -> problems.add(path + " has an unsupported schema type.");
        }
    }
}
