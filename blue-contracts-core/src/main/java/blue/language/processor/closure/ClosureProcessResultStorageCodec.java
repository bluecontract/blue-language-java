package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.ProcessorRuntimeAccess;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.ExactEventIdentityEvidenceStorageCodec;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Complete terminal-result storage under an authenticated pinned host root.
 * This physical format preserves rooted witness and publication evidence, not
 * just a flat result. It grants no provider, publication or prospective-birth
 * authority and never executes handlers. It does not restore suspended attempts.
 * Operational bound/corruption failures must abort host publication, not become
 * processor outcomes. Checksums are not substitutes for host authentication.
 */
public final class ClosureProcessResultStorageCodec {
    /** Versioned physical binding, not a semantic identity. */
    public static final String FORMAT = "blue-contracts/closure-process-result-storage/2";
    private final ExactNodeStorageCodec nodes;
    private final AffectedClosureSnapshotStorageCodec snapshots;
    private final ExactEventIdentityEvidenceStorageCodec events;
    private final int maximumBytes;
    private final int maximumDepth;
    private final Reuse reuse;

    /**
     * Creates a codec using bounded physical envelopes.
     * @param maximumBytes maximum complete result bytes
     * @param maximumDepth maximum physical value/snapshot traversal depth, 1..256
     */
    public ClosureProcessResultStorageCodec(int maximumBytes, int maximumDepth) {
        this(maximumBytes, maximumDepth, null);
    }

    /**
     * Creates a codec with optional host-owned, bounded retention of verified results.
     * The host must authenticate each selected frame before calling this codec.
     * Reuse does not authorize an invocation, publication, history position or owner.
     * @param maximumBytes maximum complete result bytes
     * @param maximumDepth maximum physical traversal depth, 1..256
     * @param reuse host retention port, or null for ordinary cold decoding
     */
    public ClosureProcessResultStorageCodec(int maximumBytes, int maximumDepth, Reuse reuse) {
        this(maximumBytes, maximumDepth, reuse, null);
    }

    ClosureProcessResultStorageCodec(int maximumBytes, int maximumDepth, Reuse reuse, Runnable beforeFullSnapshotVerification) {
        nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
        snapshots = new AffectedClosureSnapshotStorageCodec(maximumBytes, maximumDepth,
                Math.min(maximumBytes, 8 * 1024 * 1024), 32, beforeFullSnapshotVerification);
        events = new ExactEventIdentityEvidenceStorageCodec(maximumBytes, maximumDepth);
        this.maximumBytes = maximumBytes;
        this.maximumDepth = maximumDepth;
        this.reuse = reuse;
    }

    /**
     * Host retention policy for complete, library-verified terminal-result frames only.
     * Implementations should use one bounded cache, coalesce concurrent loads, and
     * release both handles and identity indexes on eviction. Never re-enter the codec
     * for the same selection while its supplied decoder is running. No attempt,
     * selected-demand association or mutable execution session is shared by this port.
     * For this complete physical format, retention may share exact frames across
     * byte bounds: a returned handle must have the same depth profile and its complete
     * bytes must fit the requesting byte bound. Format, depth and exact bytes remain
     * part of the selection. This rule does not generalize to other storage codecs.
     */
    public interface Reuse {
        /**
         * Returns a matching issued handle, invoking the decoder on a miss.
         * The decoder performs the complete ordinary verification before issuance.
         * A handle issued under another byte bound is compatible only when its full
         * frame fits this key's byte bound and its depth profile is identical.
         * @param key privately owned exact bytes and physical codec profile
         * @param fullyVerifiedDecode ordinary complete decoder, not another cache lookup
         * @return a previously issued matching handle or the decoder's result
         */
        VerifiedFrame getOrDecode(FrameKey key, Supplier<VerifiedFrame> fullyVerifiedDecode);

        /**
         * Optionally finds a retained handle for this exact decoded object identity.
         * Encoding a processor-produced or public result never issues a handle.
         * The complete retained frame must fit the requested byte bound and retain
         * the same depth profile; the issuance byte bound itself may differ.
         * @param exactResult exact previously decoded result object
         * @param maximumBytes requested physical byte bound
         * @param maximumDepth requested physical depth bound
         * @return matching retained handle, or null for ordinary encoding
         */
        default VerifiedFrame findEncoded(ClosureProcessResult exactResult, int maximumBytes, int maximumDepth) {
            return null;
        }
    }

    /** Library-created immutable physical selection; hashes alone are not a match. */
    public static final class FrameKey {
        private final byte[] bytes;
        private final int maximumBytes;
        private final int maximumDepth;
        private FrameKey(byte[] bytes, int maximumBytes, int maximumDepth) {
            this.bytes = bytes.clone(); this.maximumBytes = maximumBytes; this.maximumDepth = maximumDepth;
        }
        /** Returns the exact physical format.
         * @return versioned format */
        public String format() { return FORMAT; }
        /** Returns the byte bound at selection/handle issuance, not the frame's size.
         * @return maximum complete bytes */
        public int maximumBytes() { return maximumBytes; }
        /** Returns the selected depth profile.
         * @return maximum physical depth */
        public int maximumDepth() { return maximumDepth; }
        /** Returns a defensive copy.
         * @return complete owned exact selected bytes */
        public byte[] bytes() { return bytes.clone(); }
    }

    /**
     * Opaque, immutable receipt of a successful complete decode and canonical check.
     * It retains one owned frame as well as the decoded graph; hosts must account
     * for both. There is no public constructor or user-supplied trust flag.
     * Its issuance key is preserved when reused under a compatible byte bound.
     */
    public static final class VerifiedFrame {
        private final FrameKey key;
        private final ClosureProcessResult result;
        private VerifiedFrame(FrameKey key, ClosureProcessResult result) { this.key = key; this.result = result; }
        /** Returns immutable physical provenance.
         * @return exact frame and profile */
        public FrameKey key() { return key; }
        /** Returns the complete immutable result, not publication permission.
         * @return verified decoded result */
        public ClosureProcessResult result() { return result; }
    }

    void requireProfile(int bytes, int depth) {
        if (bytes != maximumBytes || depth != maximumDepth)
            throw invalid("Nested result codec requires the same physical profile");
    }

    private void requireHandle(VerifiedFrame frame) {
        if (frame == null) throw invalid("Result reuse returned no verified frame");
        // FORMAT 2 is complete: every nested envelope is physically contained in
        // these bytes. Its byte bound controls envelope size, not expanded graph
        // size or semantic validation. Depth remains an exact verification profile.
        if (frame.key.maximumDepth != maximumDepth)
            throw invalid("Result reuse requires the same physical depth profile");
        if (frame.key.bytes.length > maximumBytes)
            throw invalid("Reused result exceeds the requested physical byte bound");
    }

    /**
     * Encodes a complete terminal result with its original issued event evidence.
     * @param result completed result issued by the processor
     * @return newly owned, checksummed exact bytes
     */
    public byte[] encode(ClosureProcessResult result) {
        Objects.requireNonNull(result, "result");
        if (reuse != null) {
            VerifiedFrame frame = reuse.findEncoded(result, maximumBytes, maximumDepth);
            if (frame != null) {
                requireHandle(frame);
                if (frame.result != result) throw invalid("Result reuse returned another exact result object");
                return frame.key.bytes();
            }
        }
        try (SnapshotStorageCall call = snapshots.newCall()) { return encodeInCall(result, call); }
    }

    byte[] encodeInCall(ClosureProcessResult result, SnapshotStorageCall call) {
        Objects.requireNonNull(result, "result");
        return encodeExact(result, outputSnapshot(result), call);
    }

    /**
     * Compatibility overload. Original issued event evidence now carries its
     * storage authority; the external proof callback is never consulted.
     * @param result completed result issued by the processor
     * @param externalProofs non-null compatibility callback, never invoked
     * @return complete canonical storage bytes
     */
    public byte[] encode(ClosureProcessResult result, Function<String, CyclicSetProof> externalProofs) {
        Objects.requireNonNull(externalProofs, "externalProofs");
        return encode(result);
    }

    private byte[] encodeExact(ClosureProcessResult result, AffectedClosureSnapshot output, SnapshotStorageCall call) {
        return encodeExact(result, output, call, null);
    }

    // Only the completed decoder comparison supplies these privately owned bytes.
    // Public/fresh encoding still visits and verifies its supplied output normally.
    private byte[] encodeExact(ClosureProcessResult result, AffectedClosureSnapshot output, SnapshotStorageCall call,
            DecodedResult decoded) {
        return nodes.encodeEnvelope(FORMAT, out -> {
            ClosureResultStorageValues.Writer w = new ClosureResultStorageValues.Writer(out, nodes, events);
            writeSnapshot(out, result.storageInputSnapshot(), call);
            if (decoded == null) writeSnapshot(out, output, call);
            else writeBytes(out, decoded.outputBytesFor(result, output));
            w.w(result.status()); w.w(result.invocationIdentity()); w.w(result.resultingDocuments());
            w.w(result.graphChanges()); w.w(result.graphChangesIdentity());
            w.w(result.subscriptionDeltas()); w.w(result.subscriptionDeltasIdentity());
            w.w(result.checkpointWrites()); w.w(result.checkpointWritesIdentity());
            w.w(result.publicEvents()); w.w(result.publicEventsIdentity());
            w.w(result.totalGas()); w.w(result.gasTrace()); w.w(result.gasTraceIdentity());
            w.w(result.rejectedCharge()); w.w(result.rejectedWorkOccurrence()); writeCompanion(w, result.commitCompanion());
            w.w(result.diagnostic()); w.w(result.documentTransitionEvidence()); w.w(result.managedTransitionReceipts());
            w.w(result.storageReceiptSurfacePresent()); w.w(result.storageRejectedCandidate()); w.w(result.storageResolutions());
            RootedPublicationProjection rooted = result.rootedProjection(); out.writeBoolean(rooted != null);
            if (rooted != null) writeOwnership(w, rooted, call);
        });
    }

    /**
     * Restores complete terminal evidence, reusing normal exact transition validators.
     * @param bytes complete bytes selected through an authenticated pinned storage root
     * @return detached complete terminal result, with no live emitted-demand capability
     */
    public ClosureProcessResult decode(byte[] bytes) {
        if (reuse != null) {
            if (bytes == null || bytes.length > maximumBytes) throw invalid("Missing or oversized complete result frame");
            FrameKey key = new FrameKey(bytes, maximumBytes, maximumDepth);
            VerifiedFrame frame = reuse.getOrDecode(key, () -> {
                try (SnapshotStorageCall call = snapshots.newCall()) {
                    return new VerifiedFrame(key, decodeInCall(key.bytes, call));
                }
            });
            requireHandle(frame);
            if (!Arrays.equals(key.bytes, frame.key.bytes)) throw invalid("Result reuse returned another complete frame");
            return frame.result;
        }
        try (SnapshotStorageCall call = snapshots.newCall()) { return decodeInCall(bytes, call); }
    }

    ClosureProcessResult decodeInCall(byte[] bytes, SnapshotStorageCall call) {
        DecodedResult decoded = nodes.decodeEnvelope(bytes, FORMAT, in -> {
            ClosureResultStorageValues.Reader r = new ClosureResultStorageValues.Reader(in, nodes, events);
            AffectedClosureSnapshot input = readSnapshot(in, call), output = readSnapshot(in, call);
            ProcessorStatus status = r.r(); String invocation = r.r(); List<ResultingDocument> documents = r.r();
            List<GraphChange> changes = r.r(); String changesId = r.r();
            List<SubscriptionDelta> subscriptions = r.r(); String subscriptionsId = r.r();
            List<CheckpointWrite> checkpoints = r.r(); String checkpointsId = r.r();
            List<PublicEventOccurrence> events = r.r(); String eventsId = r.r();
            long gas = r.number(); List<GasTraceEntry> trace = r.r(); String traceId = r.r();
            RejectedCharge rejected = r.r(); ClosureWorkOccurrence rejectedWork = r.r(); ClosureCommitCompanion companion = readCompanion(r);
            ProcessorDiagnostic diagnostic = r.r(); List<DocumentTransitionEvidence> presentation = r.r();
            List<ManagedDocumentTransitionReceipt> receipts = r.r(); boolean receiptSurface = r.flag();
            AdmissionCandidate candidate = r.r(); List<ManagedOccurrenceEvidenceResolution> resolutions = r.r();
            ComponentFinalizationResult finalization = status.commits() ? finalizeOutput(input, output) : null;
            ClosureProcessResult restored = new ClosureProcessResult(input, status, invocation, output.closureIdentity(),
                    output.graphGeneration(), documents, output.components(), output.occurrences(), output.occurrenceBindingSetIdentity(),
                    changes, changesId, subscriptions, subscriptionsId, checkpoints, checkpointsId, events, eventsId,
                    gas, trace, traceId, rejected, rejectedWork, companion, diagnostic, finalization, presentation,
                    receipts, receiptSurface, candidate, resolutions, call);
            if (readBoolean(in)) {
                if (companion == null || !RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY.equals(
                        companion.storageEnvironment().contractsSpecificationIdentity()))
                    throw invalid("Stored rooted result requires the exact rooted Contracts profile");
                restored = restored.withRootedProjection(readOwnership(r, input, invocation, call));
                if (!restored.rootedProjection().companionIdentity().equals(r.rootedCompanion))
                    throw invalid("Stored rooted companion differs from complete result");
            }
            // This newly derived snapshot is independently validated and compared,
            // not replaced by the asserted stored output. Reuse it only afterwards.
            AffectedClosureSnapshot derivedOutput = outputSnapshot(restored);
            byte[] assertedOutputBytes = call.encode(output);
            byte[] derivedOutputBytes = call.encode(derivedOutput);
            if (!Arrays.equals(assertedOutputBytes, derivedOutputBytes))
                throw invalid("Stored result differs from its exact output snapshot");
            return new DecodedResult(restored, derivedOutput, derivedOutputBytes);
        });
        if (!Arrays.equals(bytes, encodeExact(decoded.result, decoded.output, call, decoded)))
            throw invalid("Noncanonical complete result storage");
        // These exact derived objects were built from this decoder's owned fields
        // and verified normally. Only complete outer acceptance can retain that
        // pure state fact; no input/public result or new role view is certified.
        decoded.output.acceptDerivedVerification(new DerivedSnapshotVerification(decoded.output));
        decoded.result.acceptDecodedOutput(new DecodedOutput(decoded.result, decoded.output));
        if (decoded.result.rootedProjection() != null) {
            AffectedClosureSnapshot projected = decoded.result.rootedProjection().resultingSnapshot();
            projected.acceptDerivedVerification(new DerivedSnapshotVerification(projected));
        }
        return decoded.result;
    }

    /** Issued only after the complete result's ordinary decode and canonical check. */
    static final class DerivedSnapshotVerification {
        private final AffectedClosureSnapshot subject;
        private DerivedSnapshotVerification(AffectedClosureSnapshot subject) { this.subject = subject; }
        boolean certifies(AffectedClosureSnapshot selected) { return subject == selected; }
    }

    /** Exact decoded-result association, created only after complete outer acceptance. */
    static final class DecodedOutput {
        private final ClosureProcessResult result;
        private final AffectedClosureSnapshot output;
        private DecodedOutput(ClosureProcessResult result, AffectedClosureSnapshot output) {
            this.result = result; this.output = output;
        }
        AffectedClosureSnapshot outputFor(ClosureProcessResult selected) {
            if (selected != result) throw invalid("Decoded output belongs to another exact result");
            return output;
        }
    }

    /**
     * Compatibility overload; restoration never consults a runtime or provider.
     * @param bytes complete bytes selected through an authenticated pinned storage root
     * @param runtime compatibility argument, may be null and is never consulted
     * @return detached complete terminal result
     */
    public ClosureProcessResult decode(byte[] bytes, ProcessorRuntimeAccess runtime) {
        return decode(bytes);
    }

    private void writeSnapshot(DataOutputStream out, AffectedClosureSnapshot snapshot, SnapshotStorageCall call) throws IOException {
        writeBytes(out, call.encode(snapshot));
    }
    private AffectedClosureSnapshot readSnapshot(DataInputStream in, SnapshotStorageCall call) throws IOException {
        return call.decode(readBytes(in));
    }

    private static final class DecodedResult {
        private final ClosureProcessResult result;
        private final AffectedClosureSnapshot output;
        private final byte[] outputBytes;
        private DecodedResult(ClosureProcessResult result, AffectedClosureSnapshot output, byte[] outputBytes) {
            this.result = result; this.output = output; this.outputBytes = outputBytes;
        }
        private byte[] outputBytesFor(ClosureProcessResult selectedResult, AffectedClosureSnapshot selectedOutput) {
            if (selectedResult != result || selectedOutput != output)
                throw invalid("Verified output encoding belongs to another decoded result");
            return outputBytes;
        }
    }

    private static AffectedClosureSnapshot outputSnapshot(ClosureProcessResult result) {
        AffectedClosureSnapshot verified = result.storageVerifiedOutput();
        if (verified != null) return verified;
        List<ManagedDocumentSnapshot> documents = new ArrayList<ManagedDocumentSnapshot>(); List<DocumentId> roots = new ArrayList<DocumentId>();
        for (ResultingDocument d : result.resultingDocuments()) {
            documents.add(new ManagedDocumentSnapshot(d.documentId(), d.afterBlueId(), d.document(), d.initialized(), d.terminated(),
                    d.publicRoot(), d.epoch(), d.componentGeneration()));
            if (d.publicRoot()) roots.add(d.documentId());
        }
        return new AffectedClosureSnapshot(result.outputClosureIdentity(), result.graphGeneration(), documents, result.occurrenceBindings(),
                result.occurrenceBindingSetIdentity(), result.resultingComponents(), roots, result.storageOutputWitnesses());
    }
    private static ComponentFinalizationResult finalizeOutput(AffectedClosureSnapshot input, AffectedClosureSnapshot output) {
        Map<DocumentId, Long> generations = new LinkedHashMap<DocumentId, Long>(); Map<DocumentId, Node> bodies = new LinkedHashMap<DocumentId, Node>();
        for (ManagedDocumentSnapshot d : input.managedDocuments()) generations.put(d.documentId(), d.componentGeneration());
        for (ManagedDocumentSnapshot d : output.managedDocuments()) bodies.put(d.documentId(), d.document());
        return new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(input.graph(), generations,
                bodies, output.occurrences(), output.rootedWitnesses()));
    }
    private static void writeCompanion(ClosureResultStorageValues.Writer w, ClosureCommitCompanion c) throws IOException {
        w.out.writeBoolean(c != null); if (c == null) return;
        w.w(c.companionIdentity()); w.w(c.invocationIdentity()); w.w(c.inputClosureIdentity()); w.w(c.outputClosureIdentity());
        w.w(c.expectedInputGraphGeneration()); w.w(c.expectedInputDocuments()); w.w(c.expectedInputComponents());
        w.w(c.inputOccurrenceBindingSetIdentity()); w.w(c.outputGraphGeneration()); w.w(c.resultingDocuments()); w.w(c.resultingComponents());
        w.w(c.occurrenceBindingSetIdentity()); w.w(c.graphChangesIdentity()); w.w(c.checkpointWritesIdentity()); w.w(c.subscriptionDeltasIdentity());
        w.w(c.publicEventsIdentity()); w.w(c.gasTraceIdentity()); w.w(c.bindsManagedTransitionReceipts()); w.w(c.managedTransitionReceiptsIdentity());
        w.w(c.storageEnvironment());
    }
    private static ClosureCommitCompanion readCompanion(ClosureResultStorageValues.Reader r) throws IOException {
        if (!readBoolean(r.in)) return null;
        String id = r.r(), invocation = r.r(), input = r.r(), output = r.r(); long inputGeneration = r.number();
        List<ClosureCommitCompanion.InputDocument> inputs = r.r(); List<ClosureCommitCompanion.InputComponent> components = r.r();
        String inputRows = r.r(); long outputGeneration = r.number(); List<ClosureCommitCompanion.DocumentDelta> documents = r.r();
        List<ClosureCommitCompanion.ResultComponent> results = r.r(); String rows = r.r(), changes = r.r(), checkpoints = r.r(), subscriptions = r.r();
        String events = r.r(), trace = r.r(); boolean complete = r.flag(); String receipts = r.r(); ClosureEnvironment environment = r.r();
        if (complete) return new ClosureCommitCompanion(id, invocation, input, output, inputGeneration, inputs, components, inputRows,
                outputGeneration, documents, results, rows, changes, checkpoints, subscriptions, events, trace, receipts, environment);
        ClosureCommitCompanion restored = new ClosureCommitCompanion(id, invocation, input, output, inputGeneration, inputs, components, inputRows,
                outputGeneration, documents, results, rows, changes, checkpoints, subscriptions, events, trace, environment);
        if (!Objects.equals(receipts, restored.managedTransitionReceiptsIdentity())) throw invalid("Legacy companion receipt surface differs");
        return restored;
    }

    private void writeOwnership(ClosureResultStorageValues.Writer w, RootedPublicationProjection projection,
            SnapshotStorageCall call) throws IOException {
        RootedOwnershipTracker.Snapshot s = projection.storageOwnership(); RootedProcessingContext c = s.binding.context;
        w.w(c.entryOwners()); w.w(c.ownerDescriptor()); w.w(c.storageEntryClosureIdentity()); w.w(c.identity());
        w.w(s.binding.deliveryIdentity); w.w(s.binding.entryInvocationIdentity); w.w(s.binding.birthParents);
        w.w(s.owners); writeSnapshot(w.out, s.inputSnapshot, call); w.out.writeInt(s.boundaries.size());
        for (AffectedClosureSnapshot boundary : s.boundaries) writeSnapshot(w.out, boundary, call);
        writeSnapshotMap(w, s.checkpointPredecessors, call); writeSnapshotMap(w, s.checkpointSuccessors, call);
        w.w(projection.invocationIdentity()); w.w(projection.companionIdentity());
    }
    private RootedOwnershipTracker.Snapshot readOwnership(ClosureResultStorageValues.Reader r,
            AffectedClosureSnapshot input, String executedInvocationIdentity, SnapshotStorageCall call) throws IOException {
        List<DocumentId> entryOwners = r.r(); Map<String, Object> descriptor = r.r(); String entryClosure = r.r(), contextId = r.r();
        ClosureValueSupport.requireSha256Identity(entryClosure, "entryClosureIdentity");
        RootedProcessingContext context = new RootedProcessingContext(entryOwners, descriptor, entryClosure);
        if (!context.identity().equals(contextId)) throw invalid("Stored rooted context identity differs");
        List<DocumentId> descriptorOwners = new ArrayList<DocumentId>();
        for (Object member : (List<?>) context.ownerDescriptor().get("members"))
            descriptorOwners.add(new DocumentId((String) ((Map<?, ?>) member).get("documentId")));
        if (!entryOwners.equals(descriptorOwners)) throw invalid("Stored entry owners differ from their closed descriptor");
        String delivery = r.r(), entryInvocation = r.r(); Map<DocumentId, DocumentId> births = r.r();
        ClosureValueSupport.requireSha256Identity(entryInvocation, "entryInvocationIdentity");
        RootedInvocationBinding binding = new RootedInvocationBinding(context, delivery, entryInvocation, births);
        List<DocumentId> owners = r.r(); AffectedClosureSnapshot exactInput = readSnapshot(r.in, call);
        if (!Arrays.equals(call.encode(input), call.encode(exactInput))) throw invalid("Rooted ownership input differs from result input");
        // Authenticated read/birth expansion keeps the ORIGINAL entry binding,
        // while the tracker retains the expanded EXECUTED input snapshot.
        // Only the unchanged entry can equate those two snapshot identities.
        if (entryInvocation.equals(executedInvocationIdentity)) context.requireEntrySnapshot(exactInput);
        List<AffectedClosureSnapshot> boundaries = new ArrayList<AffectedClosureSnapshot>();
        for (int n = count(r.in, false); n > 0; n--) boundaries.add(readSnapshot(r.in, call));
        Map<DocumentId, AffectedClosureSnapshot> predecessors = readSnapshotMap(r, call), successors = readSnapshotMap(r, call);
        if (!predecessors.keySet().equals(successors.keySet())) throw invalid("Incomplete stored checkpoint boundary pairs");
        for (DocumentId document : predecessors.keySet()) {
            if (!entryOwners.contains(document)) throw invalid("Checkpoint proof is not owned by an entry owner");
            boolean present = false; AffectedClosureSnapshot previous = input;
            for (AffectedClosureSnapshot boundary : boundaries) {
                if (Arrays.equals(call.encode(previous), call.encode(predecessors.get(document)))
                        && Arrays.equals(call.encode(boundary), call.encode(successors.get(document)))) present = true;
                previous = boundary;
            }
            if (!present) throw invalid("Stored checkpoint pair is absent from the actual boundary sequence");
        }
        String rootedInvocation = r.r(), rootedCompanion = r.r();
        // The complete result reconstructs and checks the companion wrapper below; never use these strings as authority.
        if (!context.invocationIdentity(entryInvocation, delivery).equals(rootedInvocation)) throw invalid("Stored rooted invocation differs");
        r.rootedCompanion = rootedCompanion;
        if (owners.size() != new LinkedHashSet<DocumentId>(owners).size()) throw invalid("Duplicate stored owner");
        validateOwners(binding, boundaries, owners);
        return new RootedOwnershipTracker.Snapshot(binding, exactInput, new LinkedHashSet<DocumentId>(owners), boundaries, predecessors, successors);
    }
    private void writeSnapshotMap(ClosureResultStorageValues.Writer w, Map<DocumentId, AffectedClosureSnapshot> map,
            SnapshotStorageCall call) throws IOException {
        List<DocumentId> keys = new ArrayList<DocumentId>(map.keySet()); Collections.sort(keys); w.out.writeInt(keys.size());
        for (DocumentId key : keys) { w.w(key); writeSnapshot(w.out, map.get(key), call); }
    }
    private Map<DocumentId, AffectedClosureSnapshot> readSnapshotMap(ClosureResultStorageValues.Reader r,
            SnapshotStorageCall call) throws IOException {
        Map<DocumentId, AffectedClosureSnapshot> result = new LinkedHashMap<DocumentId, AffectedClosureSnapshot>();
        for (int n = count(r.in, false); n > 0; n--) {
            DocumentId key = r.r(); if (key == null || result.put(key, readSnapshot(r.in, call)) != null) throw invalid("Duplicate/missing stored checkpoint owner");
        }
        return result;
    }
    private static void validateOwners(RootedInvocationBinding binding, List<AffectedClosureSnapshot> boundaries, List<DocumentId> claimed) {
        Set<DocumentId> owners = new LinkedHashSet<DocumentId>(binding.context.entryOwners());
        for (AffectedClosureSnapshot boundary : boundaries) {
            boolean changed;
            do {
                changed = false;
                for (List<DocumentId> component : new SccPartitioner().partition(boundary.graph()))
                    if (!Collections.disjoint(owners, component)) changed |= owners.addAll(component);
                for (Map.Entry<DocumentId, DocumentId> birth : binding.birthParents.entrySet())
                    if (owners.contains(birth.getValue()) && boundary.contains(birth.getKey()) && boundary.managedDocument(birth.getKey()).initialized())
                        changed |= owners.add(birth.getKey());
            } while (changed);
        }
        List<DocumentId> expected = new ArrayList<DocumentId>(owners); Collections.sort(expected);
        if (!expected.equals(claimed)) throw invalid("Stored owner set differs from complete typed ownership evidence");
    }
}
