package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Invocation-local capture; incomplete or failed attempts never export it. */
final class SourceObservationRecorder {
    private static final Object LEGACY_ATTEMPT = new Object();
    private final List<Builder> entered = new ArrayList<Builder>();
    private final Deque<Builder> active = new ArrayDeque<Builder>();
    private java.util.Set<DocumentId> owned;
    private final List<Tagged<SourceObservationProgram.ReferenceProjection>> projections = new ArrayList<>();
    private final List<Tagged<SourceObservationProgram>> borrowed = new ArrayList<>();
    private int acceptedViewOrdinal;
    private int acceptedInitializationOrdinal;
    private int skippedWorkOrdinal;
    private final Map<Object, AttemptCapture> byAttempt = new IdentityHashMap<>();
    private final java.util.Map<SourceObservationProgram, String> comparedPrograms = new java.util.IdentityHashMap<>();

    void borrowedProgram(SourceObservationProgram program) {
        borrowedProgram(program, active.isEmpty() ? LEGACY_ATTEMPT : active.getLast().attemptToken);
    }

    void borrowedProgram(SourceObservationProgram program, Object attemptToken) {
        Tagged<SourceObservationProgram> reference = new Tagged<>(program, attemptToken, borrowed.size());
        borrowed.add(reference);
        attemptCapture(attemptToken).borrowed.add(reference);
    }

    void acceptedView(AcceptedAttachmentView view, Object attemptToken) {
        attemptCapture(attemptToken).acceptedViews.add(new Tagged<>(view, attemptToken, acceptedViewOrdinal++));
    }

    void acceptedInitialization(AcceptedInitializationInstallation installation, Object attemptToken) {
        attemptCapture(attemptToken).acceptedInitializations.add(new Tagged<>(installation, attemptToken, acceptedInitializationOrdinal++));
    }

    void skippedWork(ClosureWorkOccurrence work, String requestWorkIdentity, Object attemptToken) {
        Object token = attemptToken == null ? LEGACY_ATTEMPT : attemptToken;
        attemptCapture(token).skippedWork.add(new Tagged<>(new SourceObservationProgram.SkippedWork(work,
                requestWorkIdentity), token, skippedWorkOrdinal++));
    }

    private String programDigest(SourceObservationProgram program) {
        return comparedPrograms.computeIfAbsent(program, value -> SourceObservationProgramCodec.encode(value,
                (identity, bytes) -> { }, FrozenNodeEvidenceCodec.Limits.defaults()));
    }

    void ownedDocuments(java.util.Set<DocumentId> owned) {
        this.owned = new java.util.TreeSet<DocumentId>(java.util.Objects.requireNonNull(owned, "owned"));
    }

    void enter(DocumentStepInput input) {
        enter(input, null);
    }

    void enter(DocumentStepInput input, String retainedEntrySiteIdentity) {
        enter(input, retainedEntrySiteIdentity, LEGACY_ATTEMPT);
    }

    /** The original attempt token survives accepted joins; it is never a semantic identity. */
    void enter(DocumentStepInput input, String retainedEntrySiteIdentity, Object attemptToken) {
        Builder builder = new Builder(input, retainedEntrySiteIdentity == null
                ? ClosureIdentityService.INSTANCE.observationEntrySiteIdentity(input.work().workIdentity()) : retainedEntrySiteIdentity,
                attemptToken, entered.size());
        entered.add(builder);
        attemptCapture(attemptToken).entered.add(builder);
        active.addLast(builder);
    }

    void action(SourceObservationProgram.Action action) {
        if (active.isEmpty()) throw new IllegalStateException("Observation outside a source step");
        if (action instanceof SourceObservationProgram.Patch) {
            SourceObservationProgram.Patch patch = (SourceObservationProgram.Patch) action;
            Builder builder = active.getLast(); builder.patchOrdinal++;
            if (patch.siteIdentity() == null) {
                SourceObservationProgram.Patch identified = new SourceObservationProgram.Patch(
                        ClosureIdentityService.INSTANCE.observationPatchSiteIdentity(builder.entrySiteIdentity, builder.patchOrdinal),
                        patch.transitionIdentity(), patch.frozenDocument(), patch.patch(), patch.updates());
                action = patch.resultingBindings() == null ? identified : identified.withResultingBindings(patch.resultingBindings());
            }
        }
        active.getLast().actions.add(action);
    }

    /** Completes the pending patch before any nested callback can change the current topology. */
    void completePatchBindings(List<ManagedOccurrenceBinding> rows) {
        if (active.isEmpty()) throw new IllegalStateException("Patch topology outside a source step");
        Builder builder = active.getLast();
        int index = builder.actions.size() - 1;
        if (index < 0 || !(builder.actions.get(index) instanceof SourceObservationProgram.Patch))
            throw new IllegalStateException("No current patch to complete");
        SourceObservationProgram.Patch patch = (SourceObservationProgram.Patch) builder.actions.get(index);
        if (patch.resultingBindings() != null) throw new IllegalStateException("Patch topology is already complete");
        for (ManagedOccurrenceBinding row : Objects.requireNonNull(rows, "rows"))
            if (!row.sourceDocumentId().equals(builder.input.work().targetDocumentId()))
                throw new IllegalArgumentException("Patch topology includes another source document");
        builder.actions.set(index, patch.withResultingBindings(rows));
    }

    void referenceProjection(SourceObservationProgram.ReferenceProjection projection) {
        referenceProjection(projection, active.isEmpty() ? LEGACY_ATTEMPT : active.getLast().attemptToken);
    }

    void referenceProjection(SourceObservationProgram.ReferenceProjection projection, Object attemptToken) {
        Tagged<SourceObservationProgram.ReferenceProjection> reference = new Tagged<>(projection, attemptToken, projections.size());
        projections.add(reference);
        attemptCapture(attemptToken).projections.add(reference);
    }

    void exit(LocalDocumentStepResult result) {
        Builder builder = active.getLast();
        if (!builder.input.work().workIdentity().equals(result.workOccurrenceIdentity())) {
            throw new IllegalStateException("Source observation step ownership changed");
        }
        active.removeLast();
        builder.result = result;
    }

    /** Unwind exactly one failed continuation; its partial actions are not a successful step. */
    void abortStep() {
        Builder builder = active.removeLast();
        builder.aborted = true;
    }

    /**
     * Selects only successful evidence owned by one final group. The caller supplies the union of
     * original surviving tokens, not a mutable representative/root token. Completed nested work of
     * invalidated attempts is excluded as well as their failed outer continuation.
     */
    Captured captureOwned(Set<DocumentId> ownedDocuments, Set<Object> validOriginalTokens) {
        Set<DocumentId> selectedOwners = new TreeSet<>(Objects.requireNonNull(ownedDocuments, "ownedDocuments"));
        if (selectedOwners.isEmpty()) throw new IllegalArgumentException("An observation capture requires owned documents");
        Set<Object> valid = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object token : Objects.requireNonNull(validOriginalTokens, "validOriginalTokens"))
            valid.add(Objects.requireNonNull(token, "original attempt token"));
        if (valid.isEmpty()) throw new IllegalArgumentException("An observation capture requires surviving attempts");
        return capture(selectedOwners, valid, true);
    }

    private Captured capture(Set<DocumentId> selectedOwners, Set<Object> validTokens, boolean filterOwners) {
        // Read only selected attempts: extracting N independent groups must not scan all N traces
        // once per group. Physical ordinals merely merge retained encounter order; none is encoded.
        List<Builder> selectedBuilders = new ArrayList<>();
        List<Tagged<SourceObservationProgram.ReferenceProjection>> selectedReferences = new ArrayList<>();
        List<Tagged<SourceObservationProgram>> selectedDependencies = new ArrayList<>();
        List<Tagged<AcceptedAttachmentView>> selectedViews = new ArrayList<>();
        List<Tagged<AcceptedInitializationInstallation>> selectedInitializations = new ArrayList<>();
        List<Tagged<SourceObservationProgram.SkippedWork>> selectedSkippedWork = new ArrayList<>();
        for (Object token : validTokens) {
            AttemptCapture attempt = byAttempt.get(token);
            if (attempt == null) continue;
            selectedBuilders.addAll(attempt.entered);
            selectedReferences.addAll(attempt.projections);
            selectedDependencies.addAll(attempt.borrowed);
            selectedViews.addAll(attempt.acceptedViews);
            selectedInitializations.addAll(attempt.acceptedInitializations);
            selectedSkippedWork.addAll(attempt.skippedWork);
        }
        selectedBuilders.sort((left, right) -> Integer.compare(left.enteredOrdinal, right.enteredOrdinal));
        selectedReferences.sort((left, right) -> Integer.compare(left.ordinal, right.ordinal));
        selectedViews.sort((left, right) -> Integer.compare(left.ordinal, right.ordinal));
        selectedInitializations.sort((left, right) -> Integer.compare(left.ordinal, right.ordinal));
        selectedSkippedWork.sort((left, right) -> Integer.compare(left.ordinal, right.ordinal));
        List<SourceObservationProgram.Step> steps = new ArrayList<>();
        for (Builder builder : selectedBuilders) {
            if (filterOwners && !selectedOwners.contains(builder.input.work().targetDocumentId())) continue;
            if (builder.aborted || builder.result == null)
                throw new IllegalStateException("Selected observation attempt has an aborted or incomplete step");
            steps.add(new SourceObservationProgram.Step(builder.input, builder.result, builder.actions, builder.entrySiteIdentity));
        }
        List<SourceObservationProgram.ReferenceProjection> selectedProjections = new ArrayList<>();
        for (Tagged<SourceObservationProgram.ReferenceProjection> projection : selectedReferences)
            if (!filterOwners || selectedOwners.contains(projection.value.targetDocumentId()))
                selectedProjections.add(projection.value);
        Map<String, SourceObservationProgram> selectedBorrowed = new TreeMap<>();
        Map<String, String> borrowedOwners = new TreeMap<>();
        for (Tagged<SourceObservationProgram> reference : selectedDependencies) {
            SourceObservationProgram program = reference.value;
            if (filterOwners && !Collections.disjoint(selectedOwners, program.ownedDocumentIds()))
                throw new IllegalArgumentException("A group cannot borrow its own source operation");
            for (DocumentId owner : program.ownedDocumentIds()) {
                String previous = borrowedOwners.putIfAbsent(SourceObservationProgram.borrowedOwnershipKey(program, owner), program.invocationIdentity());
                if (previous != null && !previous.equals(program.invocationIdentity()))
                    throw new IllegalArgumentException("Conflicting borrowed operation for one source lineage");
            }
            SourceObservationProgram previous = selectedBorrowed.putIfAbsent(program.invocationIdentity(), program);
            if (previous != null && previous != program && !programDigest(previous).equals(programDigest(program)))
                throw new IllegalArgumentException("Borrowed program differs for the same producing operation");
        }
        List<AcceptedAttachmentView> retainedViews = new ArrayList<>();
        for (Tagged<AcceptedAttachmentView> view : selectedViews)
            if (!filterOwners || selectedOwners.contains(view.value.selection().creatorLineage()))
                retainedViews.add(view.value);
        List<AcceptedInitializationInstallation> retainedInitializations = new ArrayList<>();
        for (Tagged<AcceptedInitializationInstallation> installation : selectedInitializations)
            if (!filterOwners || selectedOwners.contains(installation.value.selection().creatorLineage()))
                retainedInitializations.add(installation.value);
        List<SourceObservationProgram.SkippedWork> retainedSkippedWork = new ArrayList<>();
        for (Tagged<SourceObservationProgram.SkippedWork> skipped : selectedSkippedWork)
            if (!filterOwners || selectedOwners.contains(skipped.value.targetDocumentId())) retainedSkippedWork.add(skipped.value);
        return new Captured(steps, selectedProjections, new ArrayList<>(selectedBorrowed.values()), retainedViews,
                retainedInitializations, retainedSkippedWork);
    }

    private AttemptCapture attemptCapture(Object token) {
        return byAttempt.computeIfAbsent(Objects.requireNonNull(token, "attemptToken"), ignored -> new AttemptCapture());
    }

    private static final class AttemptCapture {
        final List<Builder> entered = new ArrayList<>();
        final List<Tagged<SourceObservationProgram.ReferenceProjection>> projections = new ArrayList<>();
        final List<Tagged<SourceObservationProgram>> borrowed = new ArrayList<>();
        final List<Tagged<AcceptedAttachmentView>> acceptedViews = new ArrayList<>();
        final List<Tagged<AcceptedInitializationInstallation>> acceptedInitializations = new ArrayList<>();
        final List<Tagged<SourceObservationProgram.SkippedWork>> skippedWork = new ArrayList<>();
    }

    SourceObservationProgram finish(ClosureInvocationInput input, ClosureProcessResult result) {
        if (!active.isEmpty()) throw new IllegalStateException("Incomplete observation program");
        java.util.Set<DocumentId> owners = owned;
        if (owners == null) {
            owners = new java.util.TreeSet<DocumentId>();
            for (ManagedDocumentSnapshot document : input.snapshot().managedDocuments()) owners.add(document.documentId());
        }
        for (Builder builder : entered) if (builder.attemptToken != LEGACY_ATTEMPT)
            throw new IllegalStateException("Attempt-owned observations require a final group capture");
        Captured captured = capture(owners, Collections.singleton(LEGACY_ATTEMPT), false);
        return new SourceObservationProgram(input, result, captured.steps(), owners,
                captured.projections(), captured.borrowedPrograms(), captured.acceptedViews(), captured.acceptedInitializations(), captured.skippedWork());
    }

    static final class Captured {
        private final List<SourceObservationProgram.Step> steps;
        private final List<SourceObservationProgram.ReferenceProjection> projections;
        private final List<SourceObservationProgram> borrowed;
        private final List<AcceptedAttachmentView> acceptedViews;
        private final List<AcceptedInitializationInstallation> acceptedInitializations;
        private final List<SourceObservationProgram.SkippedWork> skippedWork;
        private Captured(List<SourceObservationProgram.Step> steps,
                List<SourceObservationProgram.ReferenceProjection> projections, List<SourceObservationProgram> borrowed,
                List<AcceptedAttachmentView> acceptedViews, List<AcceptedInitializationInstallation> acceptedInitializations,
                List<SourceObservationProgram.SkippedWork> skippedWork) {
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.projections = Collections.unmodifiableList(new ArrayList<>(projections));
            this.borrowed = Collections.unmodifiableList(new ArrayList<>(borrowed));
            this.acceptedViews = Collections.unmodifiableList(new ArrayList<>(acceptedViews));
            this.acceptedInitializations = Collections.unmodifiableList(new ArrayList<>(acceptedInitializations));
            this.skippedWork = Collections.unmodifiableList(new ArrayList<>(skippedWork));
        }
        List<SourceObservationProgram.Step> steps() { return steps; }
        List<SourceObservationProgram.ReferenceProjection> projections() { return projections; }
        List<SourceObservationProgram> borrowedPrograms() { return borrowed; }
        List<AcceptedAttachmentView> acceptedViews() { return acceptedViews; }
        List<AcceptedInitializationInstallation> acceptedInitializations() { return acceptedInitializations; }
        List<SourceObservationProgram.SkippedWork> skippedWork() { return skippedWork; }
    }

    private static final class Tagged<T> {
        final T value;
        final Object token;
        final int ordinal;
        Tagged(T value, Object token, int ordinal) { this.value = Objects.requireNonNull(value, BlueLanguageConstants.OBJECT_VALUE);
            this.token = Objects.requireNonNull(token, "attemptToken"); this.ordinal = ordinal; }
    }

    private static final class Builder {
        final DocumentStepInput input;
        final String entrySiteIdentity;
        final Object attemptToken;
        final int enteredOrdinal;
        boolean aborted;
        long patchOrdinal;
        final List<SourceObservationProgram.Action> actions = new ArrayList<SourceObservationProgram.Action>();
        LocalDocumentStepResult result;
        Builder(DocumentStepInput input, String entrySiteIdentity, Object attemptToken, int enteredOrdinal) {
            this.input = Objects.requireNonNull(input, "input");
            this.attemptToken = Objects.requireNonNull(attemptToken, "attemptToken");
            this.enteredOrdinal = enteredOrdinal;
            this.entrySiteIdentity = ClosureValueSupport.requireSha256Identity(entrySiteIdentity, "entrySiteIdentity");
        }
    }
}
