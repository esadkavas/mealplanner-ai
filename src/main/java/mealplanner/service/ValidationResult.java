package mealplanner.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Separates structural errors (the object is malformed and cannot be stored) from eligibility
 * violations (the object is valid but conflicts with the current preferences).
 */
public final class ValidationResult {

    private final List<String> structuralErrors = new ArrayList<>();
    private final List<String> eligibilityViolations = new ArrayList<>();

    public static ValidationResult ok() {
        return new ValidationResult();
    }

    public ValidationResult error(String message) {
        structuralErrors.add(message);
        return this;
    }

    public ValidationResult violation(String message) {
        eligibilityViolations.add(message);
        return this;
    }

    public ValidationResult merge(ValidationResult other) {
        structuralErrors.addAll(other.structuralErrors);
        eligibilityViolations.addAll(other.eligibilityViolations);
        return this;
    }

    public boolean isStructurallyValid() {
        return structuralErrors.isEmpty();
    }

    public boolean isEligible() {
        return eligibilityViolations.isEmpty();
    }

    public boolean isValid() {
        return isStructurallyValid() && isEligible();
    }

    public List<String> structuralErrors() {
        return Collections.unmodifiableList(structuralErrors);
    }

    public List<String> eligibilityViolations() {
        return Collections.unmodifiableList(eligibilityViolations);
    }

    /** Every message, structural errors first. */
    public List<String> allMessages() {
        List<String> all = new ArrayList<>(structuralErrors);
        all.addAll(eligibilityViolations);
        return all;
    }
}
