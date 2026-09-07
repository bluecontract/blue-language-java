package blue.language.conformance.contracts;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.CheckpointDomainValue;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.ExactNodeDemand;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import blue.language.processor.closure.ManagedRootEventOccurrence;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import blue.language.processor.closure.TentativeFinalization;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.JSON;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.documentIds;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.nullable;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.nullableDocument;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.nullableNode;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.textArray;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.wire;
import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.require;

/** Exact executable fixture projection; no identity or semantics live here. */
final class FullLifecycleFixtureJson {

    static ObjectNode sortedFields(JsonNode source) {
        ObjectNode sorted = JSON.objectNode();
        List<String> keys = new ArrayList<String>();
        source.fieldNames().forEachRemaining(keys::add);
        Collections.sort(keys);
        for (String key : keys) {
            sorted.set(key, source.get(key));
        }
        return sorted;
    }

    static ObjectNode input(
            ClosureInvocationInput invocation,
            List<String> inputOrder) {
        AffectedClosureSnapshot snapshot = invocation.snapshot();
        ObjectNode result = JSON.objectNode();
        result.put("graphGeneration", snapshot.graphGeneration());
        result.set("cause", cause((AdmissionCause) invocation.cause()));
        result.putNull("admissionCandidate");
        result.putNull("admissionCandidateIdentity");
        ObjectNode documents = result.putObject("documents");
        Set<DocumentId> emitted = new LinkedHashSet<DocumentId>();
        for (String idValue : inputOrder) {
            DocumentId id = new DocumentId(idValue);
            ManagedDocumentSnapshot value = snapshot.managedDocument(id);
            require(value != null,
                    "inputOrder contains unknown document " + idValue);
            documents.set(idValue, document(value));
            emitted.add(id);
        }
        require(emitted.size() == snapshot.managedDocuments().size(),
                "inputOrder must cover every managed document exactly once");
        result.set("occurrences", occurrences(snapshot.occurrences()));
        result.set("components", components(snapshot.components()));
        result.putArray("directDeliveries");
        result.set("publicRootDocumentIds",
                documentIds(snapshot.publicRootDocumentIds()));
        result.put("directDeliverySnapshotIdentity",
                invocation.directDeliverySnapshotIdentity());
        result.put("occurrenceBindingSetIdentity",
                snapshot.occurrenceBindingSetIdentity());
        result.put("closureIdentity", snapshot.closureIdentity());
        result.put("invocationIdentity", invocation.invocationIdentity());
        result.set("gasPolicy", gasPolicy(invocation.executionPolicy()));
        result.set("environment", environment(invocation.environment()));
        return result;
    }

    static ObjectNode expected(
            ClosureAttemptResult attempt,
            ClosureImplementationEvidence implementation) {
        ObjectNode result = JSON.objectNode();
        if (!attempt.isComplete()) {
            require(implementation == null,
                    "suspended attempt exposed implementation evidence");
            result.put("attemptOutcome", "NeedsResources");
            result.set("requiredBlueIds",
                    textArray(attempt.requiredExactBlueIds()));
            result.set("resourceDemands",
                    resourceDemands(attempt.resourceDemands()));
            return result;
        }
        ClosureProcessResult value = attempt.processResult();
        require(value != null, "complete attempt omitted processResult");
        require(implementation != null,
                "complete attempt omitted implementation evidence");
        result.put("attemptOutcome", "Complete");
        result.put("status", value.status().wireValue());
        result.put("invocationIdentity", value.invocationIdentity());
        result.put("inputClosureIdentity", value.inputClosureIdentity());
        result.put("outputClosureIdentity", value.outputClosureIdentity());
        if (value.diagnostic() != null) {
            result.put("diagnostic", value.diagnostic().category().name());
        }
        result.put("atomic", value.atomic());
        result.set("workTrace", work(implementation.workTrace()));
        result.set("documentStepTrace",
                documentSteps(implementation.documentStepTrace()));
        if (value.rejectedWorkOccurrence() != null) {
            result.set("rejectedWorkOccurrence",
                    work(value.rejectedWorkOccurrence()));
        }
        if (value.rejectedCharge() != null) {
            result.set("rejectedCharge",
                    rejectedCharge(value.rejectedCharge()));
        }
        result.set("tentativeFinalizations",
                finalizations(implementation.tentativeFinalizations()));
        result.put("graphGeneration", value.graphGeneration());
        result.set("resultingDocuments",
                resultingDocuments(value.resultingDocuments()));
        result.set("resultingComponents",
                components(value.resultingComponents()));
        result.set("occurrenceBindings",
                occurrences(value.occurrenceBindings()));
        result.put("occurrenceBindingSetIdentity",
                value.occurrenceBindingSetIdentity());
        result.set("graphChanges", graphChanges(value.graphChanges()));
        result.put("graphChangesIdentity", value.graphChangesIdentity());
        result.set("subscriptionDeltas",
                subscriptionDeltas(value.subscriptionDeltas()));
        result.put("subscriptionDeltasIdentity",
                value.subscriptionDeltasIdentity());
        result.set("checkpointWrites",
                checkpointWrites(value.checkpointWrites()));
        result.put("checkpointWritesIdentity",
                value.checkpointWritesIdentity());
        result.set("publicEvents", publicEvents(value.publicEvents()));
        result.put("publicEventsIdentity", value.publicEventsIdentity());
        result.set("managedTransitionReceipts",
                managedTransitionReceipts(
                        value.managedTransitionReceipts()));
        result.put("managedTransitionReceiptsIdentity",
                value.managedTransitionReceiptsIdentity());
        result.put("rollbackToInput", value.rollbackToInput());
        result.put("totalGas", value.totalGas());
        result.set("gasTrace", gasTrace(value.gasTrace()));
        result.put("gasTraceIdentity", value.gasTraceIdentity());
        if (value.platformCommitCompanion() != null) {
            result.set("platformCommitCompanion",
                    companion(value.platformCommitCompanion()));
        }
        return result;
    }

    private static ArrayNode resourceDemands(
            List<ClosureResourceDemand> values) {
        ArrayNode result = JSON.arrayNode();
        for (ClosureResourceDemand value : values) {
            ObjectNode item = result.addObject();
            item.put("kind", value.kind().name());
            item.put("demandIdentity", value.demandIdentity());
            item.put("sourceDocumentId", value.sourceDocumentId().value());
            item.put("sourcePath", value.sourcePath());
            item.put("suppliedValueBlueId", value.suppliedValueBlueId());
            if (value instanceof ExactNodeDemand) {
                ExactNodeDemand exact = (ExactNodeDemand) value;
                item.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                        exact.blueId());
                item.put("logicalPath", exact.logicalPath());
            } else if (value instanceof ManagedOccurrenceEvidenceDemand) {
                ManagedOccurrenceEvidenceDemand occurrence =
                        (ManagedOccurrenceEvidenceDemand) value;
                item.put("logicalCauseIdentity",
                        occurrence.logicalCauseIdentity());
                item.put("inputClosureIdentity",
                        occurrence.inputClosureIdentity());
                item.put("inputGraphGeneration",
                        occurrence.inputGraphGeneration());
                item.put("processEmbeddedDeclarationIdentity",
                        occurrence.processEmbeddedDeclarationIdentity());
                item.put("demandOrdinal", occurrence.demandOrdinal());
            } else {
                throw new IllegalArgumentException(
                        "unsupported closure resource demand "
                                + value.getClass().getName());
            }
        }
        return result;
    }

    private static ObjectNode cause(AdmissionCause value) {
        ObjectNode result = JSON.objectNode();
        result.put("kind", value.kind().wireValue());
        result.put("causeIdentity", value.causeIdentity());
        result.put("admissionKind", value.admissionKind().name());
        result.put("label", value.label());
        nullable(result, "triggeringEventBlueId",
                value.triggeringEventBlueId());
        nullable(result, "parentTransitionIdentity",
                value.parentTransitionIdentity());
        result.put("policyIdentity", value.policyIdentity());
        return result;
    }

    private static ObjectNode document(ManagedDocumentSnapshot value) {
        ObjectNode result = JSON.objectNode();
        result.put("documentId", value.documentId().value());
        result.put(BlueLanguageConstants.OBJECT_BLUE_ID, value.blueId());
        result.set("document", wire(value.document()));
        result.put("initialized", value.initialized());
        result.put("terminated", value.terminated());
        result.put("publicRoot", value.publicRoot());
        result.put("epoch", value.epoch());
        result.put("componentGeneration", value.componentGeneration());
        return result;
    }

    private static ObjectNode occurrence(ManagedOccurrenceBinding value) {
        ObjectNode result = JSON.objectNode();
        result.put("occurrenceIdentity", value.occurrenceIdentity());
        result.put("bindingIdentity", value.bindingIdentity());
        result.put("bindingPolicyIdentity", value.bindingPolicyIdentity());
        result.put("sourceDocumentId", value.sourceDocumentId().value());
        result.put("sourcePath", value.sourcePath());
        result.put("activationGeneration", value.activationGeneration());
        result.put("targetDocumentId", value.targetDocumentId().value());
        result.put("expectedTargetBlueId", value.expectedTargetBlueId());
        result.put("active", value.active());
        nullable(result, "pendingHistoricalEpoch",
                value.pendingHistoricalEpoch());
        return result;
    }

    private static ArrayNode occurrences(
            List<ManagedOccurrenceBinding> values) {
        ArrayNode result = JSON.arrayNode();
        for (ManagedOccurrenceBinding value : values) {
            result.add(occurrence(value));
        }
        return result;
    }

    private static ObjectNode component(ComponentSnapshot value) {
        ObjectNode result = JSON.objectNode();
        result.put("componentIdentity", value.componentIdentity());
        result.put("componentStateIdentity",
                value.componentStateIdentity());
        result.put("componentGeneration", value.componentGeneration());
        result.put("kind", value.kind().name());
        result.set("orderedMemberDocumentIds",
                documentIds(value.orderedMemberDocumentIds()));
        result.set("orderedMemberBlueIds",
                textArray(value.orderedMemberBlueIds()));
        if (value.completeCyclicProof() != null) {
            result.put("masterBlueId", value.masterBlueId());
            result.set("completeCyclicProof", cyclicProof(value));
            result.put("cyclicProofIdentity",
                    value.cyclicProofIdentity());
        }
        return result;
    }

    private static ObjectNode cyclicProof(ComponentSnapshot value) {
        ObjectNode result = JSON.objectNode();
        result.put("componentIdentity", value.componentIdentity());
        result.put("masterBlueId", value.masterBlueId());
        ArrayNode states = result.putArray("memberStates");
        for (int index = 0;
                index < value.orderedMemberDocumentIds().size(); index++) {
            ObjectNode state = states.addObject();
            state.put("documentId", value.orderedMemberDocumentIds()
                    .get(index).value());
            state.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                    value.orderedMemberBlueIds().get(index));
        }
        ArrayNode placeholders = result.putArray(
                "declaredPlaceholderSet");
        for (Node placeholder
                : value.completeCyclicProof().declaredPlaceholderSet()) {
            placeholders.add(wire(placeholder));
        }
        return result;
    }

    private static ArrayNode components(List<ComponentSnapshot> values) {
        ArrayNode result = JSON.arrayNode();
        for (ComponentSnapshot value : values) {
            result.add(component(value));
        }
        return result;
    }

    private static ObjectNode gasPolicy(ExecutionPolicy value) {
        ObjectNode result = JSON.objectNode();
        result.put("policyIdentity", value.identity());
        result.put("sharedLimit", value.sharedLimit());
        ObjectNode local = result.putObject("localLimits");
        for (Map.Entry<DocumentId, Long> entry
                : value.localLimits().entrySet()) {
            local.put(entry.getKey().value(), entry.getValue().longValue());
        }
        result.put("label", value.label());
        return result;
    }

    private static ObjectNode environment(ClosureEnvironment value) {
        ObjectNode result = JSON.objectNode();
        result.put("blueLanguageSpecificationIdentity",
                value.blueLanguageSpecificationIdentity());
        result.put("contractsSpecificationIdentity",
                value.contractsSpecificationIdentity());
        result.put("runtimeRegistryIdentity",
                value.runtimeRegistryIdentity());
        result.put("gasManifestIdentity", value.gasManifestIdentity());
        result.set("managedDocumentIdentityPolicy", labeled(
                value.managedDocumentIdentityPolicy()));
        result.set("managedBindingPolicy", labeled(
                value.managedBindingPolicy()));
        result.set("exactNodeProviderDomain", labeled(
                value.exactNodeProviderDomain()));
        result.set("externalOrderPolicy", labeled(
                value.externalOrderPolicy()));
        ObjectNode portable = result.putObject("portableLimitPolicy");
        portable.put("identity", value.portableLimitPolicy().identity());
        portable.put("label", value.portableLimitPolicy().label());
        ArrayNode limits = portable.putArray("limits");
        for (Map.Entry<String, Long> entry
                : value.portableLimitPolicy().limits().entrySet()) {
            ObjectNode limit = limits.addObject();
            limit.put("name", entry.getKey());
            limit.put(BlueLanguageConstants.OBJECT_VALUE,
                    entry.getValue().longValue());
        }
        result.put("cyclicFinalizerIdentity",
                value.cyclicFinalizerIdentity());
        result.put("cyclicProofVerifierIdentity",
                value.cyclicProofVerifierIdentity());
        return result;
    }

    private static ObjectNode labeled(
            ClosureEnvironment.LabeledIdentityEvidence value) {
        ObjectNode result = JSON.objectNode();
        result.put("identity", value.identity());
        result.put("label", value.label());
        return result;
    }

    private static ArrayNode work(List<ClosureWorkOccurrence> values) {
        ArrayNode result = JSON.arrayNode();
        for (ClosureWorkOccurrence value : values) {
            result.add(work(value));
        }
        return result;
    }

    private static ObjectNode work(ClosureWorkOccurrence value) {
        ObjectNode result = JSON.objectNode();
        result.put("ordinal", value.ordinal());
        result.put("kind", value.kind().name());
        result.put("targetDocumentId", value.targetDocumentId().value());
        result.put("channelKey", value.channelKey());
        if (value.eventBlueId() != null) {
            result.put("eventBlueId", value.eventBlueId());
        }
        if (value.occurrenceOrdinal() != null) {
            result.put("occurrenceOrdinal",
                    value.occurrenceOrdinal().longValue());
        }
        result.put("targetManagedScopeIdentity",
                value.targetManagedScopeIdentity());
        result.put("sourceOccurrenceIdentity",
                value.sourceOccurrenceIdentity());
        result.put("workIdentity", value.workIdentity());
        return result;
    }

    private static ArrayNode documentSteps(
            List<DocumentStepEvidence> values) {
        ArrayNode result = JSON.arrayNode();
        for (DocumentStepEvidence value : values) {
            ObjectNode item = result.addObject();
            item.put("stepOrdinal", value.stepOrdinal());
            item.put("workOrdinal", value.workOrdinal());
            item.put("targetDocumentId",
                    value.targetDocumentId().value());
            item.put("executionRootDocumentId",
                    value.executionRootDocumentId().value());
            item.put("scopePath", value.scopePath());
            item.put("executionMode", value.executionMode());
            item.set("ambientContainingDocumentIds",
                    documentIds(value.ambientContainingDocumentIds()));
        }
        return result;
    }

    private static ArrayNode finalizations(
            List<TentativeFinalization> values) {
        ArrayNode result = JSON.arrayNode();
        for (TentativeFinalization value : values) {
            ObjectNode item = result.addObject();
            item.put("ordinal", value.ordinal());
            ObjectNode boundary = item.putObject("boundary");
            boundary.put("kind", value.boundary().kind().name());
            if (value.boundary().afterWorkOrdinal() != null) {
                boundary.put("afterWorkOrdinal",
                        value.boundary().afterWorkOrdinal().longValue());
            }
            item.put("masterBlueId", value.masterBlueId());
            item.put("canonicalBytes", value.canonicalBytes());
            ObjectNode members = item.putObject("memberBlueIds");
            for (Map.Entry<DocumentId, String> entry
                    : value.memberBlueIds().entrySet()) {
                members.put(entry.getKey().value(), entry.getValue());
            }
        }
        return result;
    }

    private static ArrayNode resultingDocuments(
            List<ResultingDocument> values) {
        ArrayNode result = JSON.arrayNode();
        for (ResultingDocument value : values) {
            ObjectNode item = result.addObject();
            item.put("documentId", value.documentId().value());
            item.put("beforeBlueId", value.beforeBlueId());
            item.put("afterBlueId", value.afterBlueId());
            item.set("document", wire(value.document()));
            item.put("initialized", value.initialized());
            item.put("terminated", value.terminated());
            item.put("publicRoot", value.publicRoot());
            item.put("epoch", value.epoch());
            item.put("componentGeneration", value.componentGeneration());
            item.put("componentIdentity", value.componentIdentity());
            item.put("componentStateIdentity",
                    value.componentStateIdentity());
            nullable(item, "memberIndex", value.memberIndex());
        }
        return result;
    }

    private static ArrayNode graphChanges(List<GraphChange> values) {
        ArrayNode result = JSON.arrayNode();
        for (GraphChange value : values) {
            ObjectNode item = result.addObject();
            item.put("graphChangeOrdinal", value.graphChangeOrdinal());
            item.put("changeKind", value.changeKind().name());
            item.put("sourceDocumentId",
                    value.sourceDocumentId().value());
            item.put("sourcePath", value.sourcePath());
            nullable(item, "beforeActivationGeneration",
                    value.beforeActivationGeneration());
            nullable(item, "beforeOccurrenceIdentity",
                    value.beforeOccurrenceIdentity());
            nullable(item, "beforeBindingIdentity",
                    value.beforeBindingIdentity());
            nullableDocument(item, "beforeTargetDocumentId",
                    value.beforeTargetDocumentId());
            nullable(item, "beforeTargetBlueId",
                    value.beforeTargetBlueId());
            nullable(item, "afterActivationGeneration",
                    value.afterActivationGeneration());
            nullable(item, "afterOccurrenceIdentity",
                    value.afterOccurrenceIdentity());
            nullable(item, "afterBindingIdentity",
                    value.afterBindingIdentity());
            nullableDocument(item, "afterTargetDocumentId",
                    value.afterTargetDocumentId());
            nullable(item, "afterTargetBlueId",
                    value.afterTargetBlueId());
        }
        return result;
    }

    private static ArrayNode subscriptionDeltas(
            List<SubscriptionDelta> values) {
        ArrayNode result = JSON.arrayNode();
        for (SubscriptionDelta value : values) {
            ObjectNode item = result.addObject();
            item.put("subscriptionDeltaOrdinal",
                    value.subscriptionDeltaOrdinal());
            item.put("operation", value.operation().name());
            item.put("targetManagedScopeIdentity",
                    value.targetManagedScopeIdentity());
            item.put("channelOccurrenceIdentity",
                    value.channelOccurrenceIdentity());
            nullable(item, "beforeSubscriptionIdentity",
                    value.beforeSubscriptionIdentity());
            nullable(item, "afterSubscriptionIdentity",
                    value.afterSubscriptionIdentity());
            nullable(item, "beforeDocumentBlueId",
                    value.beforeDocumentBlueId());
            nullable(item, "afterDocumentBlueId",
                    value.afterDocumentBlueId());
            nullable(item, "beforeGraphGeneration",
                    value.beforeGraphGeneration());
            nullable(item, "afterGraphGeneration",
                    value.afterGraphGeneration());
            nullable(item, "beforeComponentGeneration",
                    value.beforeComponentGeneration());
            nullable(item, "afterComponentGeneration",
                    value.afterComponentGeneration());
            nullableNode(item, "beforeSubscription",
                    value.beforeSubscription() == null
                            ? null
                            : subscription(value.beforeSubscription()));
            nullableNode(item, "afterSubscription",
                    value.afterSubscription() == null
                            ? null
                            : subscription(value.afterSubscription()));
        }
        return result;
    }

    private static ObjectNode subscription(SubscriptionState value) {
        ObjectNode result = JSON.objectNode();
        result.put("subscriptionIdentity", value.subscriptionIdentity());
        result.set("channelOccurrence",
                channelOccurrence(value.channelOccurrence()));
        result.put("documentBlueId", value.documentBlueId());
        result.put("graphGeneration", value.graphGeneration());
        result.put("componentGeneration", value.componentGeneration());
        return result;
    }

    private static ObjectNode channelOccurrence(ChannelOccurrence value) {
        ObjectNode result = JSON.objectNode();
        result.put("channelOccurrenceIdentity",
                value.channelOccurrenceIdentity());
        result.put("managedDocumentId",
                value.managedDocumentId().value());
        result.put("scopePath", value.scopePath());
        result.put("scopeActivationGeneration",
                value.scopeActivationGeneration());
        result.put("rawChannelKey", value.rawChannelKey());
        result.put("effectiveRuntimeContributionBlueId",
                value.effectiveRuntimeContributionBlueId());
        result.put("subscriptionHeaderBlueId",
                value.subscriptionHeaderBlueId());
        return result;
    }

    private static ArrayNode checkpointWrites(
            List<CheckpointWrite> values) {
        ArrayNode result = JSON.arrayNode();
        for (CheckpointWrite value : values) {
            ObjectNode item = result.addObject();
            item.put("checkpointWriteOrdinal",
                    value.checkpointWriteOrdinal());
            item.put("targetManagedScopeIdentity",
                    value.targetManagedScopeIdentity());
            item.put("rawChannelKey", value.rawChannelKey());
            item.put("beforePresent", value.beforePresent());
            nullable(item, "beforeDomainBlueId",
                    value.beforeDomainBlueId());
            nullableNode(item, "beforeDomainValue",
                    value.beforeDomainValue() == null
                            ? null : checkpointDomain(
                            value.beforeDomainValue()));
            nullable(item, "beforeSubjectBlueId",
                    value.beforeSubjectBlueId());
            item.put("afterPresent", value.afterPresent());
            nullable(item, "afterDomainBlueId",
                    value.afterDomainBlueId());
            nullableNode(item, "afterDomainValue",
                    value.afterDomainValue() == null
                            ? null : checkpointDomain(
                            value.afterDomainValue()));
            nullable(item, "afterSubjectBlueId",
                    value.afterSubjectBlueId());
        }
        return result;
    }

    private static ObjectNode checkpointDomain(
            CheckpointDomainValue value) {
        ObjectNode result = JSON.objectNode();
        result.put("contractsVersion", value.contractsVersion());
        result.put("effectiveTypeBlueId",
                value.effectiveTypeBlueId());
        result.set("sourceContributionNodeBlueIds",
                textArray(value.sourceContributionNodeBlueIds()));
        if (!value.deterministicDependencyNodeBlueIds().isEmpty()) {
            result.set("deterministicDependencyNodeBlueIds",
                    textArray(value.deterministicDependencyNodeBlueIds()));
        }
        if (value.runtimeDiscriminator() != null) {
            result.put("runtimeDiscriminator",
                    value.runtimeDiscriminator());
        }
        return result;
    }

    private static ArrayNode publicEvents(
            List<PublicEventOccurrence> values) {
        ArrayNode result = JSON.arrayNode();
        for (PublicEventOccurrence value : values) {
            ObjectNode item = result.addObject();
            item.put("publicEventOrdinal", value.publicEventOrdinal());
            item.put("eventOccurrenceOrdinal",
                    value.eventOccurrenceOrdinal());
            item.put("publicRootDocumentId",
                    value.publicRootDocumentId().value());
            item.put("eventOccurrenceIdentity",
                    value.eventOccurrenceIdentity());
            item.put("eventBlueId", value.eventBlueId());
            item.set("event", wire(value.event()));
        }
        return result;
    }

    private static ArrayNode managedTransitionReceipts(
            List<ManagedDocumentTransitionReceipt> values) {
        ArrayNode result = JSON.arrayNode();
        for (ManagedDocumentTransitionReceipt value : values) {
            ObjectNode item = result.addObject();
            item.put("transitionReceiptIdentity",
                    value.transitionReceiptIdentity());
            item.put("sourceInvocationIdentity",
                    value.sourceInvocationIdentity());
            item.put("transitionOrdinal", value.transitionOrdinal());
            item.put("transitionOccurrenceIdentity",
                    value.transitionOccurrenceIdentity());
            item.put("documentId", value.documentId().value());
            item.put("originalCauseIdentity",
                    value.originalCauseIdentity());
            item.put("beforeBlueId", value.beforeBlueId());
            item.put("afterBlueId", value.afterBlueId());
            ArrayNode events = item.putArray("emittedRootEvents");
            for (ManagedRootEventOccurrence event
                    : value.emittedRootEvents()) {
                ObjectNode encoded = events.addObject();
                encoded.put("ordinal", event.ordinal());
                encoded.put("occurrenceOrdinal",
                        event.occurrenceOrdinal());
                encoded.put("sourceDocumentId",
                        event.sourceDocumentId().value());
                encoded.put("occurrenceIdentity",
                        event.occurrenceIdentity());
                encoded.put("eventBlueId", event.eventBlueId());
                encoded.set("exactEvent", wire(event.exactEvent()));
                encoded.put("publicAtSource",
                        event.publicAtSource());
            }
            item.put("emittedRootEventsIdentity",
                    value.emittedRootEventsIdentity());
            item.put("admittedGas", value.admittedGas());
        }
        return result;
    }

    private static ArrayNode gasTrace(List<GasTraceEntry> values) {
        ArrayNode result = JSON.arrayNode();
        for (GasTraceEntry value : values) {
            ObjectNode item = result.addObject();
            item.put("sequence", value.sequence());
            item.put("namespace", value.namespace().wireValue());
            item.put("counter", value.counter());
            item.put("quantity", value.quantity());
            item.put("weight", value.weight());
            item.put("subtotal", value.subtotal());
            if (value.documentId() != null) {
                item.put("documentId", value.documentId().value());
            }
            if (value.scopePath() != null) {
                item.put("scopePath", value.scopePath());
            }
            if (value.activationGeneration() != null) {
                item.put("activationGeneration",
                        value.activationGeneration().longValue());
            }
            if (value.componentGeneration() != null) {
                item.put("componentGeneration",
                        value.componentGeneration().longValue());
            }
            if (value.contractKey() != null) {
                item.put("contractKey", value.contractKey());
            }
            if (value.logicalPath() != null) {
                item.put("logicalPath", value.logicalPath());
            }
            if (value.workOccurrenceId() != null) {
                item.put("workOccurrenceId",
                        value.workOccurrenceId());
            }
            if (value.reason() != null) {
                item.put("reason", value.reason());
            }
        }
        return result;
    }

    private static ObjectNode rejectedCharge(RejectedCharge value) {
        ObjectNode result = JSON.objectNode();
        result.put("rejectedChargeIdentity",
                value.rejectedChargeIdentity());
        result.put("namespace", value.namespace().wireValue());
        result.put("counter", value.counter());
        result.put("quantity", value.quantity());
        result.put("weight", value.weight());
        result.put("subtotal", value.subtotal());
        ObjectNode cap = result.putObject("applicableCap");
        cap.put("kind", value.applicableCap().kind().name());
        if (value.applicableCap().documentId() != null) {
            cap.put("documentId",
                    value.applicableCap().documentId().value());
        }
        result.put("remainingBeforeCharge",
                value.remainingBeforeCharge());
        ObjectNode owner = result.putObject("owner");
        owner.put("kind", value.owner().kind().name());
        if (value.owner().workOccurrenceIdentity() != null) {
            owner.put("workOccurrenceIdentity",
                    value.owner().workOccurrenceIdentity());
        }
        if (value.owner().finalizationOrdinal() != null) {
            owner.put("finalizationOrdinal",
                    value.owner().finalizationOrdinal().longValue());
            owner.put("componentIdentity",
                    value.owner().componentIdentity());
            owner.put("componentGeneration",
                    value.owner().componentGeneration().longValue());
        }
        return result;
    }

    private static ObjectNode companion(ClosureCommitCompanion value) {
        ObjectNode result = JSON.objectNode();
        result.put("companionIdentity", value.companionIdentity());
        result.put("invocationIdentity", value.invocationIdentity());
        result.put("inputClosureIdentity", value.inputClosureIdentity());
        result.put("outputClosureIdentity", value.outputClosureIdentity());
        result.put("expectedInputGraphGeneration",
                value.expectedInputGraphGeneration());
        ArrayNode inputDocuments = result.putArray(
                "expectedInputDocuments");
        for (ClosureCommitCompanion.InputDocument item
                : value.expectedInputDocuments()) {
            ObjectNode encoded = inputDocuments.addObject();
            encoded.put("documentId", item.documentId().value());
            encoded.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                    item.blueId());
        }
        ArrayNode inputComponents = result.putArray(
                "expectedInputComponents");
        for (ClosureCommitCompanion.InputComponent item
                : value.expectedInputComponents()) {
            ObjectNode encoded = inputComponents.addObject();
            encoded.put("componentIdentity", item.componentIdentity());
            encoded.put("componentStateIdentity",
                    item.componentStateIdentity());
            encoded.put("componentGeneration",
                    item.componentGeneration());
            nullable(encoded, "masterBlueId", item.masterBlueId());
        }
        result.put("inputOccurrenceBindingSetIdentity",
                value.inputOccurrenceBindingSetIdentity());
        result.put("outputGraphGeneration",
                value.outputGraphGeneration());
        ArrayNode documentDeltas = result.putArray("resultingDocuments");
        for (ClosureCommitCompanion.DocumentDelta item
                : value.resultingDocuments()) {
            ObjectNode encoded = documentDeltas.addObject();
            encoded.put("documentId", item.documentId().value());
            encoded.put("beforeBlueId", item.beforeBlueId());
            encoded.put("afterBlueId", item.afterBlueId());
        }
        ArrayNode resultComponents = result.putArray(
                "resultingComponents");
        for (ClosureCommitCompanion.ResultComponent item
                : value.resultingComponents()) {
            ObjectNode encoded = resultComponents.addObject();
            encoded.put("componentIdentity", item.componentIdentity());
            encoded.put("componentStateIdentity",
                    item.componentStateIdentity());
            nullable(encoded, "cyclicProofIdentity",
                    item.cyclicProofIdentity());
        }
        result.put("occurrenceBindingSetIdentity",
                value.occurrenceBindingSetIdentity());
        result.put("graphChangesIdentity", value.graphChangesIdentity());
        result.put("checkpointWritesIdentity",
                value.checkpointWritesIdentity());
        result.put("subscriptionDeltasIdentity",
                value.subscriptionDeltasIdentity());
        result.put("publicEventsIdentity", value.publicEventsIdentity());
        result.put("gasTraceIdentity", value.gasTraceIdentity());
        result.put("managedTransitionReceiptsIdentity",
                value.managedTransitionReceiptsIdentity());
        result.put("blueLanguageSpecificationIdentity",
                value.blueLanguageSpecificationIdentity());
        result.put("contractsSpecificationIdentity",
                value.contractsSpecificationIdentity());
        result.put("managedDocumentIdentityPolicyIdentity",
                value.managedDocumentIdentityPolicyIdentity());
        result.put("managedBindingPolicyIdentity",
                value.managedBindingPolicyIdentity());
        result.put("exactNodeProviderDomainIdentity",
                value.exactNodeProviderDomainIdentity());
        result.put("externalOrderPolicyIdentity",
                value.externalOrderPolicyIdentity());
        result.put("runtimeRegistryIdentity",
                value.runtimeRegistryIdentity());
        result.put("gasManifestIdentity", value.gasManifestIdentity());
        result.put("portableLimitPolicyIdentity",
                value.portableLimitPolicyIdentity());
        result.put("cyclicFinalizerIdentity",
                value.cyclicFinalizerIdentity());
        result.put("cyclicProofVerifierIdentity",
                value.cyclicProofVerifierIdentity());
        return result;
    }
}
