package mealplanner.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import mealplanner.service.DomainException;
import mealplanner.service.PlanDraft;
import mealplanner.service.RecommendationDraft;
import mealplanner.service.SubstitutionDraft;

/**
 * Converts the model's final JSON into typed drafts. Schema checks catch format problems first;
 * the trusted retrieved IDs are attached from the AgentRun, never from the model's JSON. Domain
 * validation happens afterwards in the services.
 */
public final class Drafts {

    public enum Status { OK, NO_MATCH, NEEDS_CLARIFICATION, UNSUPPORTED }

    private Drafts() {
    }

    /** Checks the answer against its schema and returns the model's status. */
    public static Status status(AgentRun run, JsonNode schema) {
        List<String> problems = new ArrayList<>();
        Schemas.check(schema, run.draft(), "answer", problems);
        if (!problems.isEmpty()) {
            List<String> messages = new ArrayList<>();
            messages.add("The AI's answer did not follow the required format, so it was not used:");
            messages.addAll(problems);
            throw new DomainException(messages);
        }
        return Status.valueOf(run.draft().get("status").asText().toUpperCase(Locale.ROOT));
    }

    public static String message(AgentRun run) {
        return run.draft().path("message").asText("").trim();
    }

    public static RecommendationDraft recommendation(AgentRun run) {
        JsonNode json = run.draft();
        JsonNode constraints = json.path("appliedConstraints");
        List<RecommendationDraft.Item> items = new ArrayList<>();
        for (JsonNode item : json.path("recommendations")) {
            items.add(new RecommendationDraft.Item(item.path("recipeId").asText(), item.path("reason").asText("")));
        }
        return new RecommendationDraft(message(run), constraints.path("maxMinutes").asInt(0),
                ids(constraints.path("mustIncludeIngredientIds")), ids(constraints.path("avoidIngredientIds")),
                items, run.retrievedRecipeIds());
    }

    /** Reads "entries" (new plan) or "replacements" (revision) into a PlanDraft. */
    public static PlanDraft planEntries(AgentRun run, String field) {
        List<PlanDraft.Entry> entries = new ArrayList<>();
        for (JsonNode entry : run.draft().path(field)) {
            entries.add(new PlanDraft.Entry(entry.path("day").asInt(), entry.path("slot").asText().toUpperCase(Locale.ROOT),
                    entry.path("recipeId").asText()));
        }
        return new PlanDraft(entries, run.draft().path("explanation").asText("").trim(), run.retrievedRecipeIds());
    }

    public static SubstitutionDraft substitution(AgentRun run, String selectedIngredientId) {
        JsonNode json = run.draft();
        String replacement = json.path("replacementIngredientId").asText("").trim().toLowerCase(Locale.ROOT);
        if (!run.retrievedIngredientIds().contains(replacement)) {
            throw new DomainException("The AI suggested '" + replacement
                    + "', which was not retrieved through listIngredients, so it cannot be used.");
        }
        List<SubstitutionDraft.StepChange> changes = new ArrayList<>();
        for (JsonNode change : json.path("stepChanges")) {
            changes.add(new SubstitutionDraft.StepChange(change.path("stepNumber").asInt(), change.path("newText").asText("")));
        }
        return new SubstitutionDraft(selectedIngredientId, replacement, json.path("quantity").decimalValue(),
                changes, json.path("explanation").asText("").trim());
    }

    private static List<String> ids(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(node.asText().trim().toLowerCase(Locale.ROOT));
        }
        return values;
    }
}
