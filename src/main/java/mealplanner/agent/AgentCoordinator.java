package mealplanner.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs one bounded agent request (sequence diagram SD03).
 *
 * <ol>
 *   <li>Up to {@value #MAX_NEXT_STEPS} nextStep() calls. Each step may request exactly one tool;
 *       ToolRegistry checks the name and arguments, runs it on the read-only snapshot, and the
 *       call and result are appended to the context for the next step.</li>
 *   <li>A ready signal ends the loop early, but only after the model has retrieved the candidates
 *       the task needs (otherwise the coordinator tells it to retrieve first).</li>
 *   <li>One finish() call returns the structured draft (at most 6 model calls in total).</li>
 * </ol>
 * The whole request is limited to the total timeout. Retrieved IDs come from tool results only.
 * Any provider or format failure ends the run with an AgentException; nothing is ever saved here.
 */
public class AgentCoordinator {

    public static final int MAX_NEXT_STEPS = 5;

    private final LLMClient model;
    private final ToolRegistry tools;
    private final Duration totalTimeout;

    public AgentCoordinator(LLMClient model, ToolRegistry tools, Duration totalTimeout) {
        this.model = model;
        this.tools = tools;
        this.totalTimeout = totalTimeout;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public String modelDescription() {
        return model.describe();
    }

    public AgentRun run(TaskType task, String taskPrompt, ToolScope scope, JsonNode outputSchema) throws AgentException {
        Instant deadline = Instant.now().plus(totalTimeout);
        AgentContext context = new AgentContext(task, AgentPrompts.SYSTEM, taskPrompt, tools.specsFor(task), deadline);
        List<String> trace = new ArrayList<>();
        TokenUsage usage = TokenUsage.NONE;
        int calls = 0;
        boolean ready = false;
        try {
            for (int step = 1; step <= MAX_NEXT_STEPS && !ready; step++) {
                checkTime(deadline, trace);
                AgentStep next = model.nextStep(context);
                calls++;
                usage = usage.plus(next.usage());
                context.add(new AgentContext.ModelTurn(next));
                if (next instanceof AgentStep.ToolStep toolStep) {
                    List<ToolResult> results = new ArrayList<>();
                    if (toolStep.calls().size() != 1) {
                        for (ToolCall call : toolStep.calls()) {
                            results.add(ToolResult.error(call.name(), call.callId(),
                                    "Only one tool call per step is supported. Call a single tool and wait for its result."));
                        }
                        trace.add("Step " + step + ": model requested " + toolStep.calls().size()
                                + " tools at once -> rejected (one tool per step)");
                    } else {
                        ToolCall call = toolStep.calls().get(0);
                        ToolResult result = tools.execute(call, scope);
                        results.add(result);
                        trace.add("Step " + step + ": " + call.name() + describeArgs(call.arguments()) + " -> "
                                + (result.success() ? result.summary() : "error: " + String.join(" ", result.errors())));
                    }
                    context.add(new AgentContext.ToolResults(results));
                } else if (next instanceof AgentStep.FinalReady) {
                    if (scope.hasRequiredRetrieval()) {
                        ready = true;
                        trace.add("Step " + step + ": model is ready to answer");
                    } else {
                        String needed = task == TaskType.SUBSTITUTE ? "listIngredients" : "searchRecipes";
                        context.add(new AgentContext.Note("You must call " + needed
                                + " to retrieve candidates from the application before finishing."));
                        trace.add("Step " + step + ": model tried to finish without retrieving data -> told to call " + needed);
                    }
                } else if (next instanceof AgentStep.Invalid invalid) {
                    context.add(new AgentContext.Note(invalid.problem()
                            + " Call exactly one of the listed tools with valid arguments, or reply READY when finished."));
                    trace.add("Step " + step + ": unusable model output -> asked to retry");
                }
            }
            if (!scope.hasRequiredRetrieval()) {
                throw new AgentException("The AI used all " + MAX_NEXT_STEPS
                        + " tool steps without retrieving any candidates, so no answer was produced. Try rephrasing the request.", trace);
            }
            if (!ready) {
                trace.add("Tool-step budget (" + MAX_NEXT_STEPS + ") reached -> asking for the final answer");
            }
            checkTime(deadline, trace);
            AgentDraft draft = model.finish(context, outputSchema);
            calls++;
            usage = usage.plus(draft.usage());
            trace.add("Final: structured answer received (" + calls + " model calls, "
                    + usage.inputTokens() + " input + " + usage.outputTokens() + " output tokens)");
            return new AgentRun(draft.json(), scope.retrievedRecipeIds(), scope.retrievedIngredientIds(), trace, usage, calls);
        } catch (LLMException e) {
            trace.add("Stopped: " + e.kind());
            throw new AgentException(e.getMessage(), trace, e);
        }
    }

    private void checkTime(Instant deadline, List<String> trace) throws AgentException {
        if (Instant.now().isAfter(deadline)) {
            throw new AgentException("The AI request took longer than " + totalTimeout.toSeconds()
                    + " seconds and was stopped. Nothing was saved.", trace);
        }
    }

    private static String describeArgs(JsonNode args) {
        if (args == null || args.isNull() || args.isMissingNode() || args.size() == 0) {
            return "";
        }
        String text = args.toString();
        return " " + (text.length() > 160 ? text.substring(0, 160) + "...}" : text);
    }
}
