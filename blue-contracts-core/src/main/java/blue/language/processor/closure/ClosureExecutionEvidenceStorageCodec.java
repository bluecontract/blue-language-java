package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ExactEventIdentityEvidenceStorageCodec;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Lossless physical retention of original invocation and continuation evidence.
 * Bytes MUST come from an authenticated, pinned host storage record. Checksums
 * and canonical framing detect corruption; they are not processor signatures or
 * permission to accept user-supplied serialized capabilities. Decode never calls
 * a provider, re-admits an event, or executes processing. Normal invocation,
 * historical-publication and prospective-birth checks remain mandatory at use.
 *
 * <p>The separate typed entry points share one closed field vocabulary. Complete
 * results keep their existing format; original inputs, suspended attempts and
 * standalone source receipts do not require that a whole result was retained.</p>
 */
public final class ClosureExecutionEvidenceStorageCodec {
    private static final String INVOCATION = "blue-contracts/execution-storage/invocation/1";
    private static final String ATTEMPT = "blue-contracts/execution-storage/attempt/1";
    private static final String RECEIPT = "blue-contracts/execution-storage/transition-receipt/1";
    private static final String DEMAND = "blue-contracts/execution-storage/resource-demand/1";
    private static final String RETRY = "blue-contracts/execution-storage/retry/1";
    private static final String CAUSE = "blue-contracts/execution-storage/processing-cause/1";
    private final ExactNodeStorageCodec nodes;
    private final ExactEventIdentityEvidenceStorageCodec events;
    private final AffectedClosureSnapshotStorageCodec snapshots;
    private final ClosureProcessResultStorageCodec results;
    private final int maximumBytes;

    /**
     * Creates operationally bounded storage, without semantic gas or graph limits.
     * @param maximumBytes maximum complete envelope size
     * @param maximumDepth maximum physical field/snapshot depth, 1..256
     */
    public ClosureExecutionEvidenceStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, new ClosureProcessResultStorageCodec(maximumBytes, maximumDepth));
    }

    /**
     * Creates evidence storage with a configured complete-result codec for nested frames.
     * Only complete terminal results use that codec's optional retention policy;
     * invocation, retry, attempt and demand associations are reconstructed and checked normally.
     * @param maximumBytes maximum complete envelope size
     * @param maximumDepth maximum physical field/snapshot depth, 1..256
     * @param results final library codec with exactly the same byte and depth profile
     */
    public ClosureExecutionEvidenceStorageCodec(int maximumBytes, int maximumDepth,
            ClosureProcessResultStorageCodec results) {
        nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
        events = new ExactEventIdentityEvidenceStorageCodec(maximumBytes, maximumDepth);
        snapshots = new AffectedClosureSnapshotStorageCodec(maximumBytes, maximumDepth);
        this.results = Objects.requireNonNull(results, "results");
        this.results.requireProfile(maximumBytes, maximumDepth);
        this.maximumBytes = maximumBytes;
    }

    /**
     * Retains the full original input, including its private frozen rooted binding.
     * @param input original ordinary, expanded, or completed invocation input
     * @return newly owned exact storage bytes
     */
    public byte[] encodeInvocation(ClosureInvocationInput input) {
        return encode(INVOCATION, Objects.requireNonNull(input, "input"));
    }

    /**
     * Restores the original input without executing its invocation verifier.
     * @param bytes authenticated pinned original-input bytes
     * @return immutable original input, not a new admission decision
     */
    public ClosureInvocationInput decodeInvocation(byte[] bytes) {
        return decode(INVOCATION, bytes, ClosureInvocationInput.class);
    }

    /**
     * Retains a standalone demand, including its original nullable issued marker.
     * Consumed expansion and feeder records may outlive the issuing attempt.
     * This does not associate the demand with a new invocation or list position.
     * @param demand original issued or unissued demand
     * @return newly owned exact demand bytes
     */
    public byte[] encodeResourceDemand(ClosureResourceDemand demand) {
        return encode(DEMAND, Objects.requireNonNull(demand, "demand"));
    }

    /**
     * Restores the original demand without stamping or inferring issued authority.
     * The enclosing record must retain its original producer association; active
     * continuations should use the checked input/attempt envelope instead.
     * @param bytes authenticated pinned original-demand bytes
     * @return immutable demand with its exact original nullable marker
     */
    public ClosureResourceDemand decodeResourceDemand(byte[] bytes) {
        return decode(DEMAND, bytes, ClosureResourceDemand.class);
    }

    /**
     * Retains a prepared retry before its next attempt exists.
     * @param retry exact retry, original base input and canonical resolutions
     * @return newly owned exact retry bytes
     */
    public byte[] encodeRetry(ClosureProcessRetryInput retry) {
        return encode(RETRY, Objects.requireNonNull(retry, "retry"));
    }

    /**
     * Restores a retry without fabricating an attempt or processing its base.
     * Canonical retry and resolution identities are checked, but this is not a
     * decision that the restored retry is usable in another host context.
     * @param bytes authenticated pinned prepared-retry bytes
     * @return original immutable retry and complete base input
     */
    public ClosureProcessRetryInput decodeRetry(byte[] bytes) {
        return decode(RETRY, bytes, ClosureProcessRetryInput.class);
    }

    /**
     * Retains an independent historical cause before a consumer input is captured.
     * @param cause exact original external, revision, representation or admission cause
     * @return newly owned exact cause bytes
     */
    public byte[] encodeProcessingCause(ProcessingCause cause) {
        return encode(CAUSE, Objects.requireNonNull(cause, "cause"));
    }

    /**
     * Restores complete cause evidence, including representation provenance.
     * No consumer input is invented and normal historical guards remain required.
     * @param bytes authenticated pinned original-cause bytes
     * @return original immutable cause, not a new selection or publication
     */
    public ProcessingCause decodeProcessingCause(byte[] bytes) {
        return decode(CAUSE, bytes, ProcessingCause.class);
    }

    /**
     * Reads the original rooted association from an already retained input.
     * This does not re-derive the context from an expanded snapshot or expose
     * authenticated birth-map mutation. Enclosing host records can compare
     * their separately retained identity fields to these original operands.
     * @param input exact original or restored input
     * @return immutable original binding projection, or null for ordinary input
     */
    public OriginalRootedBinding originalRootedBinding(ClosureInvocationInput input) {
        RootedInvocationBinding binding = Objects.requireNonNull(input, "input").rootedBinding();
        return binding == null ? null : new OriginalRootedBinding(binding);
    }

    /** Read-only original association; no public construction or birth-map API. */
    public static final class OriginalRootedBinding {
        private final RootedProcessingContext context;
        private final String delivery;
        private final String entry;
        private OriginalRootedBinding(RootedInvocationBinding binding) {
            context = binding.context; delivery = binding.deliveryIdentity; entry = binding.entryInvocationIdentity;
        }
        /** Returns the immutable original context, not a re-derived current owner.
         * @return original rooted processing context */
        public RootedProcessingContext context() { return context; }
        /** Returns the original delivery basis.
         * @return exact retained delivery identity */
        public String deliveryBasisIdentity() { return delivery; }
        /** Returns the original pre-expansion invocation identity.
         * @return exact retained entry invocation identity */
        public String entryInvocationIdentity() { return entry; }
    }

    /**
     * Retains an attempt together with the actual ordinary or retry input.
     * A selected demand must be the identical member of the attempt list, not
     * an equal public copy. Selected managed demands must already have been
     * emitted by this exact producer; storage never stamps an unissued demand.
     * @param input original input (the unchanged base for a process retry)
     * @param retry nullable exact process retry
     * @param attempt actual complete or suspended attempt
     * @param selectedDemand nullable exact list member retained by the host
     * @return newly owned exact associated envelope
     */
    public byte[] encodeAttempt(ClosureInvocationInput input, ClosureProcessRetryInput retry,
            ClosureAttemptResult attempt, ClosureResourceDemand selectedDemand) {
        Objects.requireNonNull(attempt, "attempt");
        int index = -1;
        if (selectedDemand != null) {
            for (int i = 0; i < attempt.resourceDemands().size(); i++)
                if (attempt.resourceDemands().get(i) == selectedDemand) index = i;
            if (index < 0) throw invalid("Selected demand is not the original attempt list member");
        }
        StoredAttempt retained = new StoredAttempt(input, retry, attempt, index);
        validateAttempt(retained);
        return encode(ATTEMPT, retained);
    }

    /**
     * Restores one input/attempt association, preserving selected-demand identity.
     * @param bytes authenticated pinned continuation bytes
     * @return original input, optional retry, attempt and its exact selected member
     */
    public StoredAttempt decodeAttempt(byte[] bytes) {
        try (ExecutionStorageCall call = newCall()) { return decodeAttemptInCall(bytes, call); }
    }

    // Package-only seam for deterministic enabled/disabled physical-work controls.
    StoredAttempt decodeAttemptInCall(byte[] bytes, ExecutionStorageCall call) {
        StoredAttempt result = decode(ATTEMPT, bytes, StoredAttempt.class, call);
        validateAttempt(result);
        return result;
    }

    /**
     * Retains a standalone receipt with its original typed event capabilities.
     * @param receipt authenticated original source transition receipt
     * @return newly owned exact receipt bytes
     */
    public byte[] encodeTransitionReceipt(ManagedDocumentTransitionReceipt receipt) {
        return encode(RECEIPT, Objects.requireNonNull(receipt, "receipt"));
    }

    /**
     * Restores a receipt without event/provider re-admission or a whole result.
     * @param bytes authenticated pinned source receipt bytes
     * @return complete immutable receipt; enclosing history authentication still applies
     */
    public ManagedDocumentTransitionReceipt decodeTransitionReceipt(byte[] bytes) {
        return decode(RECEIPT, bytes, ManagedDocumentTransitionReceipt.class);
    }

    /** Original associated values; construction is confined to this storage codec. */
    public static final class StoredAttempt {
        private final ClosureInvocationInput input;
        private final ClosureProcessRetryInput retry;
        private final ClosureAttemptResult attempt;
        private final int selectedIndex;
        private StoredAttempt(ClosureInvocationInput input, ClosureProcessRetryInput retry,
                ClosureAttemptResult attempt, int selectedIndex) {
            this.input = Objects.requireNonNull(input, "input");
            this.retry = retry;
            this.attempt = Objects.requireNonNull(attempt, "attempt");
            if (selectedIndex < -1 || selectedIndex >= attempt.resourceDemands().size())
                throw invalid("Selected demand index is outside its original attempt");
            this.selectedIndex = selectedIndex;
        }
        /** Returns the retained original input.
         * @return original input, also the exact base when a retry is present */
        public ClosureInvocationInput input() { return input; }
        /** Returns the retained retry discriminator and resolutions.
         * @return optional exact retry, or null for ordinary execution */
        public ClosureProcessRetryInput retry() { return retry; }
        /** Returns the retained complete or suspended attempt.
         * @return complete original attempt */
        public ClosureAttemptResult attempt() { return attempt; }
        /** Resolves the retained selection within the restored attempt list.
         * @return exact member of attempt.resourceDemands(), or null */
        public ClosureResourceDemand selectedDemand() {
            return selectedIndex < 0 ? null : attempt.resourceDemands().get(selectedIndex);
        }
    }

    private byte[] encode(String format, Object value) {
        return encode(format, value, null);
    }
    private byte[] encode(String format, Object value, ExecutionStorageCall call) {
        return nodes.encodeEnvelope(format, out -> writer(out, call).w(value));
    }
    private <T> T decode(String format, byte[] bytes, Class<T> type) {
        try (ExecutionStorageCall call = newCall()) { return decode(format, bytes, type, call); }
    }
    private <T> T decode(String format, byte[] bytes, Class<T> type, ExecutionStorageCall call) {
        Object value = nodes.decodeEnvelope(bytes, format, in -> reader(in, call).r());
        if (!type.isInstance(value)) throw invalid("Wrong typed execution storage payload");
        if (!Arrays.equals(bytes, encode(format, value, call))) throw invalid("Noncanonical execution storage envelope");
        return type.cast(value);
    }
    private ExecutionStorageCall newCall() {
        // Physical memo bounds, not a processing or managed-document limit.
        return new ExecutionStorageCall(maximumBytes, 4096);
    }
    private ClosureResultStorageValues.Writer writer(DataOutputStream out, ExecutionStorageCall call) {
        return new ClosureResultStorageValues.Writer(out, nodes, events, this, call);
    }
    private ClosureResultStorageValues.Reader reader(DataInputStream in, ExecutionStorageCall call) {
        return new ClosureResultStorageValues.Reader(in, nodes, events, this, call);
    }

    private void validateAttempt(StoredAttempt value) {
        String producer = value.input.invocationIdentity();
        if (value.retry != null) {
            if (!Arrays.equals(encodeInvocation(value.input), encodeInvocation(value.retry.baseInvocation())))
                throw invalid("Stored retry base differs from the original input");
            producer = value.retry.retryInvocationIdentity();
        }
        if (value.attempt.isComplete()) {
            ClosureProcessResult result = value.attempt.processResult();
            if (!producer.equals(result.invocationIdentity())
                    || !Arrays.equals(snapshots.encode(value.input.snapshot()), snapshots.encode(result.storageInputSnapshot())))
                throw invalid("Completed attempt belongs to another original input");
            // Every successful execution session projects its rooted ownership;
            // early deterministic failures legitimately have no publication.
            boolean expectedProjection = result.commits() && value.input.rootedBinding() != null;
            if (expectedProjection != (result.rootedProjection() != null))
                throw invalid("Completed attempt has inconsistent rooted publication presence");
            if (result.rootedProjection() != null) {
                RootedInvocationBinding before = value.input.rootedBinding();
                RootedInvocationBinding after = result.rootedProjection().storageOwnership().binding;
                if (before == null || !sameBinding(before, after))
                    throw invalid("Completed rooted attempt belongs to another original owner binding");
            }
        }
        for (ClosureResourceDemand resource : value.attempt.resourceDemands()) {
            if (!(resource instanceof ManagedOccurrenceEvidenceDemand)) continue;
            ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) resource;
            String issued = demand.storageEmittedInvocationIdentity();
            if (issued != null && (!issued.equals(producer)
                    || !demand.logicalCauseIdentity().equals(value.input.cause().causeIdentity())
                    || !demand.inputClosureIdentity().equals(value.input.snapshot().closureIdentity())
                    || demand.inputGraphGeneration() != value.input.snapshot().graphGeneration()))
                throw invalid("Stored emitted demand belongs to another original input");
            if (resource == value.selectedDemand() && issued == null)
                throw invalid("Selected managed demand was not emitted by the original invocation");
        }
    }

    private static boolean sameBinding(RootedInvocationBinding left, RootedInvocationBinding right) {
        return left.context.identity().equals(right.context.identity())
                && left.context.entryOwners().equals(right.context.entryOwners())
                && left.context.storageEntryClosureIdentity().equals(right.context.storageEntryClosureIdentity())
                && left.deliveryIdentity.equals(right.deliveryIdentity)
                && left.entryInvocationIdentity.equals(right.entryInvocationIdentity)
                && left.birthParents.equals(right.birthParents);
    }

    boolean writeValue(ClosureResultStorageValues.Writer w, Object value) throws IOException {
        if (value instanceof ClosureInvocationInput) {
            ClosureInvocationInput v = (ClosureInvocationInput) value;
            w.fields("ClosureInvocationInput", v.operation().name(), v.invocationIdentity());
            writeBytes(w.out, w.executionCall == null ? snapshots.encode(v.snapshot())
                    : w.executionCall.encodeSnapshot(v.snapshot(), snapshots));
            w.w(v.cause()); w.w(v.admissionCandidate()); w.w(v.admissionCandidateIdentity());
            w.w(v.directDeliveries()); w.w(v.directDeliverySnapshotIdentity());
            w.w(v.executionPolicy()); w.w(v.environment()); w.w(v.rootedBinding()); return true;
        }
        if (value instanceof RootedInvocationBinding) {
            RootedInvocationBinding v = (RootedInvocationBinding) value;
            RootedProcessingContext c = v.context;
            w.fields("RootedInvocationBinding", c.entryOwners(), c.ownerDescriptor(), c.storageEntryClosureIdentity(),
                    c.identity(), v.deliveryIdentity, v.entryInvocationIdentity, v.birthParents); return true;
        }
        if (value instanceof ClosureProcessRetryInput) {
            ClosureProcessRetryInput v = (ClosureProcessRetryInput) value;
            w.fields("ClosureProcessRetryInput", v.retryInvocationIdentity(), v.baseInvocation(),
                    v.resolutions(), v.resolutionSetIdentity()); return true;
        }
        if (value instanceof StoredAttempt) {
            StoredAttempt v = (StoredAttempt) value;
            w.fields("StoredAttempt", v.input, v.retry, v.attempt, v.selectedIndex); return true;
        }
        if (value instanceof ClosureAttemptResult) {
            ClosureAttemptResult v = (ClosureAttemptResult) value;
            w.fields("ClosureAttemptResult", v.isComplete(), v.processResult(), v.resourceDemands()); return true;
        }
        if (value instanceof ClosureProcessResult) {
            writeText(w.out, "ClosureProcessResult");
            writeBytes(w.out, w.executionCall == null ? results.encode((ClosureProcessResult) value)
                    : w.executionCall.encodeResult((ClosureProcessResult) value, results)); return true;
        }
        if (value instanceof ExactNodeDemand) {
            ExactNodeDemand v = (ExactNodeDemand) value;
            w.fields("ExactNodeDemand", v.demandIdentity(), v.blueId(), v.sourceDocumentId(), v.logicalPath()); return true;
        }
        if (value instanceof ExternalOrderKey) {
            w.fields("ExternalOrderKey", ((ExternalOrderKey) value).components()); return true;
        }
        if (value instanceof ExecutionPolicy) {
            ExecutionPolicy v = (ExecutionPolicy) value;
            w.fields("ExecutionPolicy", v.identity(), v.sharedLimit(), v.localLimits(), v.label()); return true;
        }
        if (value instanceof DirectLogicalDelivery) {
            DirectLogicalDelivery v = (DirectLogicalDelivery) value;
            w.fields("DirectLogicalDelivery", v.targetScope(), v.channelKey(), v.logicalDeliveryKey(), v.rawOccurrenceOrder()); return true;
        }
        if (value instanceof AdmissionCause) {
            AdmissionCause v = (AdmissionCause) value;
            w.fields("AdmissionCause", v.causeIdentity(), v.admissionKind().name(), v.label(), v.triggeringEventBlueId(),
                    v.parentTransitionIdentity(), v.policyIdentity()); return true;
        }
        if (value instanceof ExternalEventCause) {
            ExternalEventCause v = (ExternalEventCause) value;
            w.fields("ExternalEventCause", v.causeIdentity(), v.event(), v.eventBlueId(), v.sourceOrder(), v.externalOrderPolicyIdentity()); return true;
        }
        if (value instanceof ManagedRevisionCause) {
            ManagedRevisionCause v = (ManagedRevisionCause) value;
            w.fields("ManagedRevisionCause", v.causeIdentity(), v.targetOccurrenceIdentity(), v.childDocumentId(), v.fromEpoch(), v.toEpoch(),
                    v.beforeBlueId(), v.afterBlueId(), v.afterDocument(), v.originalSourceCauseIdentity(), v.sourceRevisionReceiptIdentity(),
                    v.sourceTransitionReceipt().orElse(null), v.afterCyclicProof().orElse(null), v.successorRepresentationCause().orElse(null)); return true;
        }
        if (value instanceof ManagedRepresentationCause) {
            ManagedRepresentationCause v = (ManagedRepresentationCause) value;
            w.fields("ManagedRepresentationCause", v.targetOccurrenceIdentity(), v.transition(), v.targetPositionIdentity(),
                    v.nextRevisionReceiptIdentity(), v.afterCyclicProof().orElse(null)); return true;
        }
        if (value instanceof ManagedRepresentationTransition) {
            ManagedRepresentationTransition v = (ManagedRepresentationTransition) value;
            w.fields("ManagedRepresentationTransition", v.documentId(), v.epoch(), v.anchorReceiptIdentity(), v.predecessorPositionIdentity(),
                    v.originalInput(), v.originalResult(), v.transitionReceipt().transitionReceiptIdentity()); return true;
        }
        return false;
    }

    Object readValue(ClosureResultStorageValues.Reader r, String tag) throws IOException {
        switch (tag) {
            case "ClosureInvocationInput": return readInvocation(r);
            case "RootedInvocationBinding": return readBinding(r);
            case "ClosureProcessRetryInput": {
                ClosureProcessRetryInput retry = new ClosureProcessRetryInput(r.r(), r.r(), r.r());
                if (!retry.resolutionSetIdentity().equals(r.r())) throw invalid("Stored retry resolution identity differs");
                return retry;
            }
            case "StoredAttempt": return new StoredAttempt(r.r(), r.r(), r.r(), r.integer());
            case "ClosureAttemptResult": {
                boolean complete = r.flag(); ClosureProcessResult result = r.r(); List<ClosureResourceDemand> demands = r.r();
                if (complete) {
                    if (!demands.isEmpty()) throw invalid("Complete attempt retains suspension demands");
                    return ClosureAttemptResult.complete(result);
                }
                if (result != null) throw invalid("Suspension retains a completed result");
                return ClosureAttemptResult.needsResources(demands);
            }
            case "ClosureProcessResult": return r.executionCall == null ? results.decode(readBytes(r.in))
                    : r.executionCall.decodeResult(readBytes(r.in), results);
            case "StoredManagedOccurrenceEvidenceDemand": return ManagedOccurrenceEvidenceDemand.fromTrustedStorage(
                    r.r(), r.r(), r.r(), r.number(), r.r(), r.r(), r.r(), r.r(), r.number(), r.r(), r.r());
            case "ExactNodeDemand": return new ExactNodeDemand(r.r(), r.r(), r.r(), r.r());
            case "ExternalOrderKey": return ExternalOrderKey.of(r.r());
            case "ExecutionPolicy": return new ExecutionPolicy(r.r(), r.number(), r.r(), r.r());
            case "DirectLogicalDelivery": return new DirectLogicalDelivery(r.r(), r.r(), r.r(), r.number());
            case "AdmissionCause": return new AdmissionCause(r.r(), AdmissionKind.valueOf(r.r()), r.r(), r.r(), r.r(), r.r());
            case "ExternalEventCause": return new ExternalEventCause(r.r(), r.r(), r.r(), r.r(), r.r());
            case "ManagedRevisionCause": return readRevision(r);
            case "ManagedRepresentationCause": return new ManagedRepresentationCause(r.r(), r.r(), r.r(), r.r(), r.r());
            case "ManagedRepresentationTransition": return new ManagedRepresentationTransition(r.r(), r.number(), r.r(), r.r(), r.r(), r.r(), r.r());
            default: throw invalid("Unknown execution storage field: " + tag);
        }
    }

    private ClosureInvocationInput readInvocation(ClosureResultStorageValues.Reader r) throws IOException {
        ClosureInvocationInput.Operation operation = ClosureInvocationInput.Operation.valueOf(r.r());
        String identity = r.r();
        AffectedClosureSnapshot snapshot = r.executionCall == null ? snapshots.decode(readBytes(r.in))
                : r.executionCall.decodeSnapshot(readBytes(r.in), snapshots);
        ProcessingCause cause = r.r(); AdmissionCandidate candidate = r.r(); String candidateId = r.r();
        List<DirectLogicalDelivery> deliveries = r.r(); String deliveryId = r.r();
        ExecutionPolicy policy = r.r(); ClosureEnvironment environment = r.r(); RootedInvocationBinding binding = r.r();
        ClosureInvocationInput result;
        if (operation == ClosureInvocationInput.Operation.ADMIT_CLOSURE) {
            if (!deliveries.isEmpty()) throw invalid("Admission input retains direct deliveries");
            result = ClosureInvocationInput.admitClosure(identity, snapshot, (AdmissionCause) cause, candidate, candidateId, deliveryId, policy, environment);
        } else {
            if (candidate != null || candidateId != null) throw invalid("Processing input retains an admission candidate");
            result = ClosureInvocationInput.processClosure(identity, snapshot, cause, deliveries, deliveryId, policy, environment);
        }
        if (binding != null) {
            if (!RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY.equals(environment.contractsSpecificationIdentity()))
                throw invalid("Stored rooted input requires its exact Contracts profile");
            // Expanded inputs deliberately retain the ORIGINAL entry binding.
            // Do not re-derive its owner or substitute the expanded snapshot.
            if (identity.equals(binding.entryInvocationIdentity)) binding.context.requireEntrySnapshot(snapshot);
            result = result.withRootedBinding(binding);
        }
        return result;
    }

    private RootedInvocationBinding readBinding(ClosureResultStorageValues.Reader r) throws IOException {
        List<DocumentId> owners = r.r(); Map<String, Object> descriptor = r.r(); String entryClosure = r.r(), identity = r.r();
        ClosureValueSupport.requireSha256Identity(entryClosure, "entryClosureIdentity");
        RootedProcessingContext context = new RootedProcessingContext(owners, descriptor, entryClosure);
        if (!context.identity().equals(identity)) throw invalid("Stored rooted context identity differs");
        List<DocumentId> described = new ArrayList<DocumentId>();
        for (Object member : (List<?>) context.ownerDescriptor().get("members"))
            described.add(new DocumentId((String) ((Map<?, ?>) member).get("documentId")));
        if (!owners.equals(described)) throw invalid("Stored rooted owners differ from their descriptor");
        String delivery = r.r(), entryInvocation = r.r(); Map<DocumentId, DocumentId> births = r.r();
        ClosureValueSupport.requireSha256Identity(entryInvocation, "entryInvocationIdentity");
        for (Map.Entry<DocumentId, DocumentId> birth : births.entrySet()) {
            Objects.requireNonNull(birth.getKey(), "stored birth lineage");
            Objects.requireNonNull(birth.getValue(), "stored birth parent");
        }
        return new RootedInvocationBinding(context, delivery, entryInvocation, births);
    }

    private ManagedRevisionCause readRevision(ClosureResultStorageValues.Reader r) throws IOException {
        String identity = r.r(), occurrence = r.r(); DocumentId child = r.r(); long from = r.number(), to = r.number();
        String before = r.r(), after = r.r(); Node body = r.r(); String original = r.r(), receiptId = r.r();
        ManagedDocumentTransitionReceipt receipt = r.r(); CyclicSetProof proof = r.r(); ManagedRepresentationCause successor = r.r();
        ManagedRevisionCause result;
        if (receipt == null) result = new ManagedRevisionCause(identity, occurrence, child, from, to, before, after, body, original, receiptId, proof);
        else {
            if (!receiptId.equals(receipt.transitionReceiptIdentity())) throw invalid("Stored source receipt identity differs");
            result = new ManagedRevisionCause(identity, occurrence, child, from, to, before, after, body, original, receipt, proof);
        }
        return successor == null ? result : result.withSuccessorRepresentationCause(successor);
    }
}
