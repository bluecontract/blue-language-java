package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.*;
import java.util.*;
import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Closed typed fields used only by authenticated execution/result storage. Scalar DTO mappings
 * selectively adapted from the earlier experimental Coordination ClosureEvidenceCodec;
 * no old result, invocation, snapshot, Node, authority or wire framing is reused.
 */
final class ClosureResultStorageValues {
    static final class Writer {
        final DataOutputStream out;
        final ExactNodeStorageCodec nodes;
        final ExactEventIdentityEvidenceStorageCodec events;
        final ClosureExecutionEvidenceStorageCodec execution;
        final ExecutionStorageCall executionCall;
        int depth;
        Writer(DataOutputStream out, ExactNodeStorageCodec nodes, ExactEventIdentityEvidenceStorageCodec events) {
            this(out, nodes, events, null);
        }
        Writer(DataOutputStream out, ExactNodeStorageCodec nodes, ExactEventIdentityEvidenceStorageCodec events,
                ClosureExecutionEvidenceStorageCodec execution) {
            this(out, nodes, events, execution, null);
        }
        Writer(DataOutputStream out, ExactNodeStorageCodec nodes, ExactEventIdentityEvidenceStorageCodec events,
                ClosureExecutionEvidenceStorageCodec execution, ExecutionStorageCall executionCall) {
            this.out = out; this.nodes = nodes; this.events = events; this.execution = execution;
            this.executionCall = executionCall;
        }
        void fields(String tag, Object... values) throws IOException {
            writeText(out, tag); for (Object value : values) w(value);
        }
        void w(Object value) throws IOException {
            nodes.depth(++depth);
            try { write(value); } finally { depth--; }
        }
        void write(Object value) throws IOException {
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
                writeText(out, "Scalar"); nodes.writeValue(out, value, depth); return;
            }
            if (value instanceof List<?>) {
                writeText(out, "List"); List<?> list = (List<?>) value; out.writeInt(list.size());
                for (Object item : list) w(item); return;
            }
            if (value instanceof Map<?, ?>) {
                writeText(out, "Map"); Map<?, ?> map = (Map<?, ?>) value; out.writeInt(map.size());
                for (Map.Entry<?, ?> item : map.entrySet()) { w(item.getKey()); w(item.getValue()); } return;
            }
            if (value instanceof ComponentKind) { fields("ComponentKind", ((ComponentKind) value).name()); return; }
            if (value instanceof GraphChange.Kind) { fields("GraphChange.Kind", ((GraphChange.Kind) value).name()); return; }
            if (value instanceof SubscriptionDelta.Operation) { fields("SubscriptionDelta.Operation", ((SubscriptionDelta.Operation) value).name()); return; }
            if (value instanceof GasTraceEntry.Namespace) { fields("GasTraceEntry.Namespace", ((GasTraceEntry.Namespace) value).name()); return; }
            if (value instanceof DocumentTransitionEvidence.Operation) { fields("DocumentTransitionEvidence.Operation", ((DocumentTransitionEvidence.Operation) value).name()); return; }
            if (value instanceof ProcessorErrorCategory) { fields("ProcessorErrorCategory", ((ProcessorErrorCategory) value).name()); return; }
            if (value instanceof ProcessorStatus) { fields("ProcessorStatus", ((ProcessorStatus) value).name()); return; }
            if (value instanceof WorkKind) { fields("WorkKind", ((WorkKind) value).name()); return; }
            if (value instanceof ClosureWorkOccurrence) { ClosureWorkOccurrence v = (ClosureWorkOccurrence) value;
                fields("ClosureWorkOccurrence", v.ordinal(), v.kind(), v.targetDocumentId(), v.channelKey(),
                        v.eventBlueId(), v.occurrenceOrdinal(), v.targetManagedScopeIdentity(),
                        v.sourceOccurrenceIdentity(), v.workIdentity()); return;
            }
            if (value instanceof RejectedCharge) { RejectedCharge v = (RejectedCharge) value;
                fields("RejectedCharge", v.rejectedChargeIdentity(), v.namespace(), v.counter(), v.quantity(),
                        v.weight(), v.subtotal(), v.applicableCap(), v.remainingBeforeCharge(), v.owner()); return;
            }
            if (value instanceof RejectedCharge.ApplicableCap) { RejectedCharge.ApplicableCap v = (RejectedCharge.ApplicableCap) value;
                if (v.kind() == RejectedCharge.ApplicableCap.Kind.SHARED) fields("SharedCap");
                else fields("LocalCap", v.documentId());
                return;
            }
            if (value instanceof RejectedCharge.Owner) { RejectedCharge.Owner v = (RejectedCharge.Owner) value;
                switch (v.kind()) {
                    case INVOCATION: fields("InvocationOwner"); break;
                    case WORK: fields("WorkOwner", v.workOccurrenceIdentity()); break;
                    case FINALIZATION: fields("FinalizationOwner", v.finalizationOrdinal(), v.componentIdentity(), v.componentGeneration()); break;
                }
                return;
            }
            if (value instanceof DocumentId) {
                DocumentId v = (DocumentId) value;
                fields("DocumentId", v.value()); return;
            }
            if (value instanceof ManagedScopeKey) {
                ManagedScopeKey v = (ManagedScopeKey) value;
                fields("ManagedScopeKey", v.documentId(), v.address()); return;
            }
            if (value instanceof ScopeAddress) {
                ScopeAddress v = (ScopeAddress) value;
                fields("ScopeAddress", v.path(), v.activationGeneration()); return;
            }
            if (value instanceof ResultingDocument) {
                ResultingDocument v = (ResultingDocument) value;
                fields("ResultingDocument", v.documentId(), v.beforeBlueId(), v.afterBlueId(), v.document(), v.initialized(), v.terminated(), v.publicRoot(), v.epoch(), v.componentGeneration(), v.componentIdentity(), v.componentStateIdentity(), v.memberIndex()); return;
            }
            if (value instanceof ComponentSnapshot) {
                ComponentSnapshot v = (ComponentSnapshot) value;
                fields("ComponentSnapshot", v.componentIdentity(), v.componentStateIdentity(), v.componentGeneration(), v.kind(), v.orderedMemberDocumentIds(), v.orderedMemberBlueIds(), v.masterBlueId(), v.completeCyclicProof(), v.cyclicProofIdentity()); return;
            }
            if (value instanceof ManagedOccurrenceBinding) {
                ManagedOccurrenceBinding v = (ManagedOccurrenceBinding) value;
                fields("ManagedOccurrenceBinding", v.occurrenceIdentity(), v.bindingIdentity(), v.bindingPolicyIdentity(), v.sourceDocumentId(), v.sourceAddress(), v.targetDocumentId(), v.expectedTargetBlueId(), v.active(), v.pendingHistoricalEpoch(), v.pendingRepresentationCursor()); return;
            }
            if (value instanceof ManagedRepresentationCursor) {
                ManagedRepresentationCursor v = (ManagedRepresentationCursor) value;
                fields("ManagedRepresentationCursor", v.anchorReceiptIdentity(), v.positionIdentity(), v.targetPositionIdentity(), v.nextRevisionReceiptIdentity()); return;
            }
            if (value instanceof GraphChange) {
                GraphChange v = (GraphChange) value;
                fields("GraphChange", v.graphChangeOrdinal(), v.changeKind(), v.sourceDocumentId(), v.sourcePath(), v.before(), v.after()); return;
            }
            if (value instanceof GraphChange.Side) {
                GraphChange.Side v = (GraphChange.Side) value;
                fields("GraphChange.Side", v.activationGeneration(), v.occurrenceIdentity(), v.bindingIdentity(), v.targetDocumentId(), v.targetBlueId()); return;
            }
            if (value instanceof SubscriptionDelta) {
                SubscriptionDelta v = (SubscriptionDelta) value;
                fields("SubscriptionDelta", v.subscriptionDeltaOrdinal(), v.operation(), v.targetManagedScopeIdentity(), v.channelOccurrenceIdentity(), v.beforeSubscription(), v.afterSubscription()); return;
            }
            if (value instanceof SubscriptionState) {
                SubscriptionState v = (SubscriptionState) value;
                fields("SubscriptionState", v.subscriptionIdentity(), v.channelOccurrence(), v.documentBlueId(), v.graphGeneration(), v.componentGeneration()); return;
            }
            if (value instanceof ChannelOccurrence) {
                ChannelOccurrence v = (ChannelOccurrence) value;
                fields("ChannelOccurrence", v.channelOccurrenceIdentity(), v.managedDocumentId(), v.scopePath(), v.scopeActivationGeneration(), v.rawChannelKey(), v.effectiveRuntimeContributionBlueId(), v.subscriptionHeaderBlueId()); return;
            }
            if (value instanceof CheckpointDomainValue) {
                CheckpointDomainValue v = (CheckpointDomainValue) value;
                fields("CheckpointDomainValue", v.effectiveTypeBlueId(), v.sourceContributionNodeBlueIds(), v.deterministicDependencyNodeBlueIds(), v.runtimeDiscriminator()); return;
            }
            if (value instanceof CheckpointWrite.State) {
                CheckpointWrite.State v = (CheckpointWrite.State) value;
                fields("CheckpointWrite.State", v.domainBlueId(), v.domainValue(), v.subjectBlueId()); return;
            }
            if (value instanceof GasTraceEntry) {
                GasTraceEntry v = (GasTraceEntry) value;
                fields("GasTraceEntry", v.sequence(), v.namespace(), v.counter(), v.quantity(), v.weight(), v.subtotal(), v.documentId(), v.scopePath(), v.activationGeneration(), v.componentGeneration(), v.contractKey(), v.logicalPath(), v.workOccurrenceId(), v.reason()); return;
            }
            if (value instanceof ManagedDocumentTransitionReceipt) {
                ManagedDocumentTransitionReceipt v = (ManagedDocumentTransitionReceipt) value;
                fields("ManagedDocumentTransitionReceipt", v.transitionReceiptIdentity(), v.sourceInvocationIdentity(), v.transitionOrdinal(), v.transitionOccurrenceIdentity(), v.documentId(), v.originalCauseIdentity(), v.beforeBlueId(), v.afterBlueId(), v.emittedRootEvents(), v.emittedRootEventsIdentity(), v.admittedGas()); return;
            }
            if (value instanceof DocumentTransitionEvidence) {
                DocumentTransitionEvidence v = (DocumentTransitionEvidence) value;
                fields("DocumentTransitionEvidence", v.documentId(), v.workOccurrenceIdentity(), v.beforeDocumentBlueId(), v.afterDocumentBlueId(), v.beforeEffectiveTypeBlueId().orElse(null), v.afterEffectiveTypeBlueId().orElse(null), v.authoredContractPatches(), v.generatedGeneralizationWrites()); return;
            }
            if (value instanceof DocumentTransitionEvidence.AuthoredContractPatch) {
                DocumentTransitionEvidence.AuthoredContractPatch v = (DocumentTransitionEvidence.AuthoredContractPatch) value;
                fields("DocumentTransitionEvidence.AuthoredContractPatch", v.operation(), v.path(), v.authoredValueBlueId().orElse(null), v.beforeValueBlueId().orElse(null), v.afterValueBlueId().orElse(null)); return;
            }
            if (value instanceof DocumentTransitionEvidence.GeneratedGeneralizationWrite) {
                DocumentTransitionEvidence.GeneratedGeneralizationWrite v = (DocumentTransitionEvidence.GeneratedGeneralizationWrite) value;
                fields("DocumentTransitionEvidence.GeneratedGeneralizationWrite", v.path(), v.valueBlueId(), v.requiringPatchIndex()); return;
            }
            if (value instanceof ClosureEnvironment) {
                ClosureEnvironment v = (ClosureEnvironment) value;
                fields("ClosureEnvironment", v.blueLanguageSpecificationIdentity(), v.contractsSpecificationIdentity(), v.runtimeRegistryIdentity(), v.gasManifestIdentity(), v.managedDocumentIdentityPolicy(), v.managedBindingPolicy(), v.exactNodeProviderDomain(), v.externalOrderPolicy(), v.portableLimitPolicy(), v.cyclicFinalizerIdentity(), v.cyclicProofVerifierIdentity()); return;
            }
            if (value instanceof ClosureEnvironment.LabeledIdentityEvidence) {
                ClosureEnvironment.LabeledIdentityEvidence v = (ClosureEnvironment.LabeledIdentityEvidence) value;
                fields("ClosureEnvironment.LabeledIdentityEvidence", v.identity(), v.label()); return;
            }
            if (value instanceof ClosureEnvironment.PortableLimitPolicyEvidence) {
                ClosureEnvironment.PortableLimitPolicyEvidence v = (ClosureEnvironment.PortableLimitPolicyEvidence) value;
                fields("ClosureEnvironment.PortableLimitPolicyEvidence", v.identity(), v.label(), v.limits()); return;
            }
            if (value instanceof ManagedOccurrenceEvidenceResolution) {
                ManagedOccurrenceEvidenceResolution v = (ManagedOccurrenceEvidenceResolution) value;
                fields("ManagedOccurrenceEvidenceResolution", v.resolutionIdentity(), v.demand(), v.targetDocumentId(), v.pendingHistoricalEpoch()); return;
            }
            if (value instanceof ManagedOccurrenceEvidenceDemand) {
                ManagedOccurrenceEvidenceDemand v = (ManagedOccurrenceEvidenceDemand) value;
                if (execution != null) {
                    fields("StoredManagedOccurrenceEvidenceDemand", v.demandIdentity(), v.logicalCauseIdentity(),
                            v.inputClosureIdentity(), v.inputGraphGeneration(), v.sourceDocumentId(), v.sourcePath(),
                            v.processEmbeddedDeclarationIdentity(), v.suppliedValueBlueId(), v.demandOrdinal(),
                            v.suppliedExactValue().orElse(null), v.storageEmittedInvocationIdentity()); return;
                }
                fields("ManagedOccurrenceEvidenceDemand", v.demandIdentity(), v.logicalCauseIdentity(), v.inputClosureIdentity(), v.inputGraphGeneration(), v.sourceDocumentId(), v.sourcePath(), v.processEmbeddedDeclarationIdentity(), v.suppliedValueBlueId(), v.demandOrdinal(), v.suppliedExactValue().orElse(null)); return;
            }
            if (value instanceof CyclicSetProof) {
                CyclicSetProof v = (CyclicSetProof) value;
                fields("CyclicSetProof", v.declaredPlaceholderSet()); return;
            }
            if (value instanceof ClosureCommitCompanion.InputDocument) {
                ClosureCommitCompanion.InputDocument v = (ClosureCommitCompanion.InputDocument) value;
                fields("ClosureCommitCompanion.InputDocument", v.documentId(), v.blueId()); return;
            }
            if (value instanceof ClosureCommitCompanion.InputComponent) {
                ClosureCommitCompanion.InputComponent v = (ClosureCommitCompanion.InputComponent) value;
                fields("ClosureCommitCompanion.InputComponent", v.componentIdentity(), v.componentStateIdentity(), v.componentGeneration(), v.masterBlueId()); return;
            }
            if (value instanceof ClosureCommitCompanion.DocumentDelta) {
                ClosureCommitCompanion.DocumentDelta v = (ClosureCommitCompanion.DocumentDelta) value;
                fields("ClosureCommitCompanion.DocumentDelta", v.documentId(), v.beforeBlueId(), v.afterBlueId()); return;
            }
            if (value instanceof ClosureCommitCompanion.ResultComponent) {
                ClosureCommitCompanion.ResultComponent v = (ClosureCommitCompanion.ResultComponent) value;
                fields("ClosureCommitCompanion.ResultComponent", v.componentIdentity(), v.componentStateIdentity(), v.cyclicProofIdentity()); return;
            }
            if (value instanceof AdmissionCandidate.BadCyclicProof) {
                AdmissionCandidate.BadCyclicProof v = (AdmissionCandidate.BadCyclicProof) value;
                fields("AdmissionCandidate.BadCyclicProof", v.candidateCyclicProof()); return;
            }
            if (value instanceof AdmissionCandidate.AmbiguousPreliminaryMembers) {
                AdmissionCandidate.AmbiguousPreliminaryMembers v = (AdmissionCandidate.AmbiguousPreliminaryMembers) value;
                fields("AdmissionCandidate.AmbiguousPreliminaryMembers", v.candidateCyclicMembers()); return;
            }
            if (value instanceof AdmissionCandidate.InvalidOccurrenceBinding) {
                AdmissionCandidate.InvalidOccurrenceBinding v = (AdmissionCandidate.InvalidOccurrenceBinding) value;
                fields("AdmissionCandidate.InvalidOccurrenceBinding", v.candidateOccurrenceBindings()); return;
            }
            if (value instanceof AdmissionCandidate.CandidateCyclicProof) {
                AdmissionCandidate.CandidateCyclicProof v = (AdmissionCandidate.CandidateCyclicProof) value;
                fields("AdmissionCandidate.CandidateCyclicProof", v.componentIdentity(), v.masterBlueId(), v.memberStates(), v.declaredPlaceholderSet()); return;
            }
            if (value instanceof AdmissionCandidate.CandidateMemberState) {
                AdmissionCandidate.CandidateMemberState v = (AdmissionCandidate.CandidateMemberState) value;
                fields("AdmissionCandidate.CandidateMemberState", v.documentId(), v.blueId()); return;
            }
            if (value instanceof AdmissionCandidate.CandidateCyclicMember) {
                AdmissionCandidate.CandidateCyclicMember v = (AdmissionCandidate.CandidateCyclicMember) value;
                fields("AdmissionCandidate.CandidateCyclicMember", v.documentId(), v.document()); return;
            }
            if (value instanceof AdmissionCandidate.CandidateOccurrenceBinding) {
                AdmissionCandidate.CandidateOccurrenceBinding v = (AdmissionCandidate.CandidateOccurrenceBinding) value;
                fields("AdmissionCandidate.CandidateOccurrenceBinding", v.occurrenceIdentity(), v.bindingIdentity(), v.bindingPolicyIdentity(), v.sourceDocumentId(), v.sourcePath(), v.activationGeneration(), v.targetDocumentId(), v.expectedTargetBlueId(), v.active()); return;
            }
            if (value instanceof Node) { writeText(out, "Node"); nodes.writeNode(out, (Node) value, depth); return; }
            if (value instanceof PublicEventOccurrence) {
                PublicEventOccurrence v = (PublicEventOccurrence) value;
                fields("PublicEventOccurrence", v.publicEventOrdinal(), v.eventOccurrenceOrdinal(), v.publicRootDocumentId(), v.eventOccurrenceIdentity());
                event(v.exactEventIdentityEvidence()); return;
            }
            if (value instanceof ManagedRootEventOccurrence) {
                ManagedRootEventOccurrence v = (ManagedRootEventOccurrence) value;
                fields("ManagedRootEventOccurrence", v.ordinal(), v.occurrenceOrdinal(), v.sourceDocumentId(), v.eventOccurrenceIdentity());
                event(v.exactEventIdentityEvidence()); w(v.publicAtSource()); return;
            }
            if (value instanceof CheckpointWrite) {
                CheckpointWrite v = (CheckpointWrite) value;
                fields("CheckpointWrite", v.checkpointWriteOrdinal(), v.targetManagedScopeKey(), v.targetManagedScopeIdentity(), v.rawChannelKey(),
                    v.beforePresent() ? new CheckpointWrite.State(v.beforeDomainBlueId(), v.beforeDomainValue(), v.beforeSubjectBlueId()) : null,
                    v.afterPresent() ? new CheckpointWrite.State(v.afterDomainBlueId(), v.afterDomainValue(), v.afterSubjectBlueId()) : null); return;
            }
            if (value instanceof ProcessorDiagnostic) {
                ProcessorDiagnostic v = (ProcessorDiagnostic) value; fields("ProcessorDiagnostic", v.category(), v.message(), v.details()); return;
            }
            if (execution != null && execution.writeValue(this, value)) return;
            throw invalid("Unsupported complete result storage field: " + value.getClass().getName());
        }
        void event(ExactEventIdentityEvidence evidence) throws IOException {
            writeBytes(out, events.encode(evidence));
        }
    }

    static final class Reader {
        final DataInputStream in; final ExactNodeStorageCodec nodes; final ExactEventIdentityEvidenceStorageCodec events;
        final ClosureExecutionEvidenceStorageCodec execution;
        final ExecutionStorageCall executionCall;
        String rootedCompanion;
        int depth;
        Reader(DataInputStream in, ExactNodeStorageCodec nodes, ExactEventIdentityEvidenceStorageCodec events) {
            this(in, nodes, events, null);
        }
        Reader(DataInputStream in, ExactNodeStorageCodec nodes, ExactEventIdentityEvidenceStorageCodec events,
                ClosureExecutionEvidenceStorageCodec execution) {
            this(in, nodes, events, execution, null);
        }
        Reader(DataInputStream in, ExactNodeStorageCodec nodes, ExactEventIdentityEvidenceStorageCodec events,
                ClosureExecutionEvidenceStorageCodec execution, ExecutionStorageCall executionCall) {
            this.in = in; this.nodes = nodes; this.events = events; this.execution = execution;
            this.executionCall = executionCall;
        }
        long number() throws IOException { return this.<Long>r(); }
        int integer() throws IOException { return this.<Integer>r(); }
        boolean flag() throws IOException { return this.<Boolean>r(); }
        @SuppressWarnings("unchecked") <T> T r() throws IOException {
            nodes.depth(++depth);
            try { return (T) read(); } finally { depth--; }
        }
        Object read() throws IOException {
            String tag = requiredText(in);
            switch (tag) {
                case "Scalar": return nodes.readValue(in, depth);
                case "List": {
                    List<Object> result = new ArrayList<Object>();
                    for (int n = count(in, false); n > 0; n--) result.add(r()); return result;
                }
                case "Map": {
                    Map<Object, Object> result = new LinkedHashMap<Object, Object>();
                    for (int n = count(in, false); n > 0; n--) {
                        Object key = r(); if (result.containsKey(key)) throw invalid("Duplicate stored map key"); result.put(key, r());
                    } return result;
                }
                case "ComponentKind": return ComponentKind.valueOf(this.<String>r());
                case "GraphChange.Kind": return GraphChange.Kind.valueOf(this.<String>r());
                case "SubscriptionDelta.Operation": return SubscriptionDelta.Operation.valueOf(this.<String>r());
                case "GasTraceEntry.Namespace": return GasTraceEntry.Namespace.valueOf(this.<String>r());
                case "DocumentTransitionEvidence.Operation": return DocumentTransitionEvidence.Operation.valueOf(this.<String>r());
                case "ProcessorErrorCategory": return ProcessorErrorCategory.valueOf(this.<String>r());
                case "ProcessorStatus": return ProcessorStatus.valueOf(this.<String>r());
                case "WorkKind": return WorkKind.valueOf(this.<String>r());
                case "ClosureWorkOccurrence": return new ClosureWorkOccurrence(number(), r(), r(), r(), r(), r(), r(), r(), r());
                case "RejectedCharge": return new RejectedCharge(r(), r(), r(), number(), number(), number(), r(), number(), r());
                case "DocumentId": return new DocumentId(r());
                case "ManagedScopeKey": return new ManagedScopeKey(r(), r());
                case "ScopeAddress": return scope(r(), number());
                case "ResultingDocument": return new ResultingDocument(r(), r(), r(), (Node) r(), flag(), flag(), flag(), number(), number(), r(), r(), r());
                case "ComponentSnapshot": return new ComponentSnapshot(r(), r(), number(), r(), r(), r(), r(), r(), r());
                case "ManagedOccurrenceBinding": return new ManagedOccurrenceBinding(r(), r(), r(), r(), r(), r(), r(), flag(), r()).withRepresentationCursor(r());
                case "ManagedRepresentationCursor": return new ManagedRepresentationCursor(r(), r(), r(), r());
                case "GraphChange": return new GraphChange(number(), r(), r(), r(), r(), r());
                case "GraphChange.Side": return new GraphChange.Side(number(), r(), r(), r(), r());
                case "SubscriptionDelta": return new SubscriptionDelta(number(), r(), r(), r(), r(), r());
                case "SubscriptionState": return new SubscriptionState(r(), r(), r(), number(), number());
                case "ChannelOccurrence": return new ChannelOccurrence(r(), r(), r(), number(), r(), r(), r());
                case "CheckpointDomainValue": return new CheckpointDomainValue(r(), r(), r(), r());
                case "CheckpointWrite.State": return new CheckpointWrite.State(r(), r(), r());
                case "GasTraceEntry": return new GasTraceEntry(number(), r(), r(), number(), number(), number(), r(), r(), r(), r(), r(), r(), r(), r());
                case "ManagedDocumentTransitionReceipt": return new ManagedDocumentTransitionReceipt(r(), r(), number(), r(), r(), r(), r(), r(), r(), r(), number());
                case "DocumentTransitionEvidence": return new DocumentTransitionEvidence(r(), r(), r(), r(), r(), r(), r(), r());
                case "DocumentTransitionEvidence.AuthoredContractPatch": return new DocumentTransitionEvidence.AuthoredContractPatch(r(), r(), r(), r(), r());
                case "DocumentTransitionEvidence.GeneratedGeneralizationWrite": return new DocumentTransitionEvidence.GeneratedGeneralizationWrite(r(), r(), integer());
                case "ClosureEnvironment": return new ClosureEnvironment(r(), r(), r(), r(), r(), r(), r(), r(), r(), r(), r());
                case "ClosureEnvironment.LabeledIdentityEvidence": return new ClosureEnvironment.LabeledIdentityEvidence(r(), r());
                case "ClosureEnvironment.PortableLimitPolicyEvidence": return new ClosureEnvironment.PortableLimitPolicyEvidence(r(), r(), r());
                case "ManagedOccurrenceEvidenceResolution": return new ManagedOccurrenceEvidenceResolution(r(), r(), r(), number());
                case "ManagedOccurrenceEvidenceDemand": return unissuedDemand();
                case "CyclicSetProof": return CyclicSetProof.fromDeclaredPlaceholderSet(r());
                case "ClosureCommitCompanion.InputDocument": return new ClosureCommitCompanion.InputDocument(r(), r());
                case "ClosureCommitCompanion.InputComponent": return new ClosureCommitCompanion.InputComponent(r(), r(), number(), r());
                case "ClosureCommitCompanion.DocumentDelta": return new ClosureCommitCompanion.DocumentDelta(r(), r(), r());
                case "ClosureCommitCompanion.ResultComponent": return new ClosureCommitCompanion.ResultComponent(r(), r(), r());
                case "AdmissionCandidate.BadCyclicProof": return AdmissionCandidate.badCyclicProof(r());
                case "AdmissionCandidate.AmbiguousPreliminaryMembers": return AdmissionCandidate.ambiguousPreliminaryMembers(r());
                case "AdmissionCandidate.InvalidOccurrenceBinding": return AdmissionCandidate.invalidOccurrenceBinding(r());
                case "AdmissionCandidate.CandidateCyclicProof": return new AdmissionCandidate.CandidateCyclicProof(r(), r(), r(), r());
                case "AdmissionCandidate.CandidateMemberState": return new AdmissionCandidate.CandidateMemberState(r(), r());
                case "AdmissionCandidate.CandidateCyclicMember": return new AdmissionCandidate.CandidateCyclicMember(r(), r());
                case "AdmissionCandidate.CandidateOccurrenceBinding": return new AdmissionCandidate.CandidateOccurrenceBinding(r(), r(), r(), r(), r(), number(), r(), r(), flag());
                case "SharedCap": return RejectedCharge.ApplicableCap.shared();
                case "LocalCap": return RejectedCharge.ApplicableCap.local(r());
                case "InvocationOwner": return RejectedCharge.Owner.invocation();
                case "WorkOwner": return RejectedCharge.Owner.work(r());
                case "FinalizationOwner": return RejectedCharge.Owner.finalization(number(), r(), number());
                case "Node": return nodes.readNode(in, depth);
                case "PublicEventOccurrence": return new PublicEventOccurrence(number(), number(), r(), r(), event());
                case "ManagedRootEventOccurrence": return new ManagedRootEventOccurrence(number(), number(), r(), r(), event(), flag());
                case "CheckpointWrite": return new CheckpointWrite(number(), r(), r(), r(), r(), r());
                case "ProcessorDiagnostic": {
                    ProcessorDiagnostic.Builder builder = ProcessorDiagnostic.builder(r()).message(r());
                    Map<String, String> details = r(); details.forEach(builder::detail); return builder.build();
                }
                default:
                    if (execution != null) return execution.readValue(this, tag);
                    throw invalid("Unknown complete result storage field");
            }
        }
        ScopeAddress scope(String path, long generation) {
            if ("/".equals(path)) {
                if (generation != 0) throw invalid("Nonzero Root generation");
                return ScopeAddress.root();
            }
            return ScopeAddress.embedded(path, generation);
        }
        ManagedOccurrenceEvidenceDemand unissuedDemand() throws IOException {
            String identity = r(), cause = r(), closure = r(); long generation = number();
            DocumentId source = r(); String path = r(), declaration = r(), supplied = r(); long ordinal = number();
            Node inline = r();
            // Complete-result /2 never carried an emitted capability marker.
            // Preserve optional inline absence without upgrading this public value.
            return inline == null
                    ? new ManagedOccurrenceEvidenceDemand(identity, cause, closure, generation, source, path, declaration, supplied, ordinal)
                    : new ManagedOccurrenceEvidenceDemand(identity, cause, closure, generation, source, path, declaration, supplied, ordinal, inline);
        }
        ExactEventIdentityEvidence event() throws IOException {
            return events.decode(readBytes(in));
        }
    }
}
