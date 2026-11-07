package mealplanner.agent;

/** The four AI tasks (F05-F08), plus the provider connection check used by "llm-check". */
public enum TaskType {
    RECOMMEND,
    PLAN,
    REVISE,
    SUBSTITUTE,
    CONNECTION_CHECK
}
