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

    /** Exhaustive fixture execution outcome. */
    public enum Status {
        /** Fixture passed. */
        PASS,
        /** Fixture failed. */
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

    /**
     * Creates one validated fixture result.
     *
     * @param fixtureId stable fixture identity
     * @param path fixture resource path
     * @param role manifest file role
     * @param category fixture category
     * @param operation exercised operation
     * @param vectors normative vector identifiers
     * @param status pass/fail outcome
     * @param failure failure details, required exactly when status is FAIL
     * @throws IllegalArgumentException when required evidence is inconsistent
     */
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

    /**

     * Returns the fixture identity.

     *

     * @return fixture identity

     */
    public String getFixtureId() {
        return fixtureId;
    }

    /**

     * Returns the fixture path.

     *

     * @return resource path

     */
    public String getPath() {
        return path;
    }

    /**

     * Returns the manifest role.

     *

     * @return file role

     */
    public String getRole() {
        return role;
    }

    /**

     * Returns the fixture category.

     *

     * @return fixture category

     */
    public BlueContractsFixtureCategory getCategory() {
        return category;
    }

    /**

     * Returns the exercised operation.

     *

     * @return operation name

     */
    public String getOperation() {
        return operation;
    }

    /**

     * Returns normative vectors.

     *

     * @return immutable vector list

     */
    public List<String> getVectors() {
        return vectors;
    }

    /**

     * Returns the execution outcome.

     *

     * @return pass/fail status

     */
    public Status getStatus() {
        return status;
    }

    /**

     * Returns failure details.

     *

     * @return failure or {@code null} for PASS

     */
    public BlueContractsConformanceFailure getFailure() {
        return failure;
    }
}
