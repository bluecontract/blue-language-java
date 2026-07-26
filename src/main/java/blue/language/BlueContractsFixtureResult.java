package blue.language;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Machine-readable outcome for one exact fixture file. Contracts 1.0 has no
 * skip outcome: a fixture is either passed or failed.
 */
public final class BlueContractsFixtureResult {

    public enum Status {
        PASS,
        FAIL
    }

    private final String fixtureId;
    private final String path;
    private final String role;
    private final BlueContractsFixtureCategory category;
    private final String operation;
    private final List<String> vectors;
    private final Status status;
    private final BlueContractsConformanceFailure failure;

    public BlueContractsFixtureResult(String fixtureId,
                                      String path,
                                      String role,
                                      BlueContractsFixtureCategory category,
                                      String operation,
                                      List<String> vectors,
                                      Status status,
                                      BlueContractsConformanceFailure failure) {
        if (fixtureId == null || fixtureId.trim().isEmpty()) {
            throw new IllegalArgumentException("fixtureId is required");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        if (role == null || role.trim().isEmpty()) {
            throw new IllegalArgumentException("role is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (operation == null || operation.trim().isEmpty()) {
            throw new IllegalArgumentException("operation is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (status == Status.PASS && failure != null) {
            throw new IllegalArgumentException("A passed fixture cannot contain a failure");
        }
        if (status == Status.FAIL && failure == null) {
            throw new IllegalArgumentException("A failed fixture must contain a failure");
        }
        if (failure != null
                && !fixtureId.equals(failure.getFixtureId())) {
            throw new IllegalArgumentException(
                    "Fixture failure ID must match its result");
        }
        if (vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one fixture vector is required");
        }
        Set<String> uniqueVectors = new LinkedHashSet<>();
        for (String vector : vectors) {
            if (vector == null
                    || vector.trim().isEmpty()
                    || !uniqueVectors.add(vector)) {
                throw new IllegalArgumentException(
                        "Fixture vectors must be non-empty and unique");
            }
        }
        this.fixtureId = fixtureId;
        this.path = path;
        this.role = role;
        this.category = category;
        this.operation = operation;
        this.vectors = Collections.unmodifiableList(new ArrayList<>(vectors));
        this.status = status;
        this.failure = failure;
    }

    public String getFixtureId() {
        return fixtureId;
    }

    public String getPath() {
        return path;
    }

    public String getRole() {
        return role;
    }

    public BlueContractsFixtureCategory getCategory() {
        return category;
    }

    public String getOperation() {
        return operation;
    }

    public List<String> getVectors() {
        return vectors;
    }

    public Status getStatus() {
        return status;
    }

    public BlueContractsConformanceFailure getFailure() {
        return failure;
    }
}
