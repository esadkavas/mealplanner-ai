package mealplanner.facade;

/** What a pending preview would save, which decides the rules re-checked on acceptance. */
public enum ProposalKind {
    /** F03: structurally valid recipe; may carry an "ineligible" warning. */
    IMPORT_RECIPE,
    /** F09: structurally valid scaled variant; may carry an "ineligible" warning. */
    SCALE_RECIPE,
    /** F08: variant must be structurally valid AND eligible for the current profile. */
    SUBSTITUTE_RECIPE,
    /** F06/F07: complete plan validation. */
    PLAN
}
