package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable semantic skeleton for one completed affected-closure attempt. */
public final class ClosureProcessResult {

    private final ProcessorStatus status;
    private final String invocationIdentity;
    private final String inputClosureIdentity;
    private final String outputClosureIdentity;
    private final long graphGeneration;
    private final List<ManagedDocumentSnapshot> resultingDocuments;
    private final List<ComponentSnapshot> resultingComponents;
    private final List<ManagedOccurrenceBinding> occurrenceBindings;
    private final String occurrenceBindingSetIdentity;
    private final List<Node> publicEvents;
    private final long totalGas;
    private final ClosureCommitCompanion commitCompanion;
    private final ProcessorDiagnostic diagnostic;

    /**
     * Creates the bounded result skeleton for one completed closure attempt.
     *
     * @param status completed processor status
     * @param invocationIdentity exact invocation identity
     * @param inputClosureIdentity input closure identity
     * @param outputClosureIdentity output closure identity
     * @param graphGeneration resulting graph generation
     * @param resultingDocuments canonical resulting documents
     * @param resultingComponents canonical resulting components
     * @param occurrenceBindings canonical resulting binding rows
     * @param occurrenceBindingSetIdentity resulting row-set identity
     * @param publicEvents ordered public event nodes
     * @param totalGas exact admitted gas total
     * @param commitCompanion required atomic companion for success
     * @param diagnostic nullable stable diagnostic
     */
    public ClosureProcessResult(
            ProcessorStatus status,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long graphGeneration,
            List<ManagedDocumentSnapshot> resultingDocuments,
            List<ComponentSnapshot> resultingComponents,
            List<ManagedOccurrenceBinding> occurrenceBindings,
            String occurrenceBindingSetIdentity,
            List<Node> publicEvents,
            long totalGas,
            ClosureCommitCompanion commitCompanion,
            ProcessorDiagnostic diagnostic) {
        this.status = Objects.requireNonNull(status, "status");
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        this.inputClosureIdentity = ClosureValueSupport.requireSha256Identity(
                inputClosureIdentity, "inputClosureIdentity");
        this.outputClosureIdentity = ClosureValueSupport.requireSha256Identity(
                outputClosureIdentity, "outputClosureIdentity");
        this.graphGeneration = ClosureValueSupport.requireSafeInteger(
                graphGeneration, "graphGeneration");
        this.resultingDocuments = immutableCanonicalDocuments(
                resultingDocuments);
        this.resultingComponents = immutableCanonicalComponents(
                resultingComponents);
        this.occurrenceBindings = immutableCanonicalOccurrences(
                occurrenceBindings);
        this.occurrenceBindingSetIdentity =
                ClosureValueSupport.requireSha256Identity(
                        occurrenceBindingSetIdentity,
                        "occurrenceBindingSetIdentity");
        this.publicEvents = immutableNodes(publicEvents);
        this.totalGas = ClosureValueSupport.requireSafeInteger(
                totalGas, "totalGas");
        this.commitCompanion = commitCompanion;
        this.diagnostic = diagnostic;
        if (status.commits() != (commitCompanion != null)) {
            throw new IllegalArgumentException(
                    "Exactly a committing status requires a commit companion");
        }
        if (!status.commits() && !this.publicEvents.isEmpty()) {
            throw new IllegalArgumentException(
                    "A noncommitting closure result cannot publish events");
        }
        if (commitCompanion != null) {
            validateCompanion(commitCompanion);
        }
    }

    /**
     * Returns the documented value.
     *
     * @return completed processor status
     */
    public ProcessorStatus status() {
        return status;
    }

    /**
     * Returns the documented value.
     *
     * @return whether the result carries an atomic commit companion
     */
    public boolean commits() {
        return status.commits();
    }

    /**
     * Returns the documented value.
     *
     * @return exact invocation identity
     */
    public String invocationIdentity() {
        return invocationIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return input closure identity
     */
    public String inputClosureIdentity() {
        return inputClosureIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return output closure identity
     */
    public String outputClosureIdentity() {
        return outputClosureIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return resulting graph generation
     */
    public long graphGeneration() {
        return graphGeneration;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical resulting documents
     */
    public List<ManagedDocumentSnapshot> resultingDocuments() {
        return resultingDocuments;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical resulting components
     */
    public List<ComponentSnapshot> resultingComponents() {
        return resultingComponents;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical resulting occurrence rows
     */
    public List<ManagedOccurrenceBinding> occurrenceBindings() {
        return occurrenceBindings;
    }

    /**
     * Returns the documented value.
     *
     * @return resulting complete occurrence-row-set identity
     */
    public String occurrenceBindingSetIdentity() {
        return occurrenceBindingSetIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable defensive copies of ordered public events
     */
    public List<Node> publicEvents() {
        return immutableNodes(publicEvents);
    }

    /**
     * Returns the documented value.
     *
     * @return exact admitted gas total
     */
    public long totalGas() {
        return totalGas;
    }

    /**
     * Returns the documented value.
     *
     * @return atomic companion, or {@code null} for noncommitting status
     */
    public ClosureCommitCompanion commitCompanion() {
        return commitCompanion;
    }

    /**
     * Returns the documented value.
     *
     * @return stable diagnostic, or {@code null}
     */
    public ProcessorDiagnostic diagnostic() {
        return diagnostic;
    }

    private void validateCompanion(ClosureCommitCompanion companion) {
        if (!invocationIdentity.equals(companion.invocationIdentity())
                || !inputClosureIdentity.equals(
                        companion.inputClosureIdentity())
                || !outputClosureIdentity.equals(
                        companion.outputClosureIdentity())
                || graphGeneration != companion.outputGraphGeneration()
                || !occurrenceBindingSetIdentity.equals(
                        companion.outputOccurrenceBindingSetIdentity())) {
            throw new IllegalArgumentException(
                    "Commit companion disagrees with the closure result");
        }
    }

    private static List<ManagedDocumentSnapshot> immutableCanonicalDocuments(
            List<ManagedDocumentSnapshot> values) {
        ArrayList<ManagedDocumentSnapshot> copy = copyNonNull(
                values, "resultingDocuments");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("resultingDocuments must not be empty");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "Resulting documents are not canonical");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ComponentSnapshot> immutableCanonicalComponents(
            List<ComponentSnapshot> values) {
        ArrayList<ComponentSnapshot> copy = copyNonNull(
                values, "resultingComponents");
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("resultingComponents must not be empty");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "Resulting components are not canonical");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<ManagedOccurrenceBinding> immutableCanonicalOccurrences(
            List<ManagedOccurrenceBinding> values) {
        ArrayList<ManagedOccurrenceBinding> copy = copyNonNull(
                values, "occurrenceBindings");
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "Resulting occurrence bindings are not canonical");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<Node> immutableNodes(List<Node> values) {
        ArrayList<Node> copy = new ArrayList<Node>();
        for (Node value : Objects.requireNonNull(values, "publicEvents")) {
            copy.add(Objects.requireNonNull(value, "public event").clone());
        }
        return Collections.unmodifiableList(copy);
    }

    private static <T> ArrayList<T> copyNonNull(
            List<T> values,
            String field) {
        ArrayList<T> copy = new ArrayList<T>(
                Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return copy;
    }
}
