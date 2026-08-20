package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.CheckpointDomainValue;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import blue.language.processor.closure.TentativeFinalization;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Exact result-level C-CLO-34 execution through the public Contracts facade. */
final class Cclo34FullResultConformanceTest {

    @Test
    void shouldMatchEveryReleasedCclo34ResultAndImplementationField() {
        // This focused result gate is also used while exact fixture identities
        // are rebound ahead of the atomic package-manifest publication.  The
        // inventory gate independently verifies the final digest and byte
        // length; constructing the package-local route here lets this test
        // verify the rebound production result without weakening either gate.
        ClosureFixtureInventory.Entry entry =
                new ClosureFixtureInventory.Entry(
                        ClosureFixtureInventory.C_CLO_34,
                        "closure/c-clo-34-separate-document-steps.yaml",
                        "process-closure",
                        Collections.singletonList("C-CLO-34"),
                        "bind-only-result-gate",
                        0L);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry)
                .admit();

        // The runtime receives only authored controls.  In particular, it is
        // never given the fixture's expected subtree.
        JsonNode executionFixture = ClosureFixtureInventory.readFixture(entry);
        ObjectNode runtimeEnvelope = JsonNodeFactory.instance.objectNode();
        runtimeEnvelope.set(
                "runtime",
                requiredObject(executionFixture, "runtime").deepCopy());

        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(runtimeEnvelope);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor(), capture)) {
            attempt = contracts.processClosure(input);
        }

        // Expected output becomes visible only after independent execution.
        JsonNode expected = requiredObject(
                ClosureFixtureInventory.readFixture(entry), "expected");
        assertCompleteResult(expected, attempt, capture.evidence);
    }

    static void assertCompleteResult(
            JsonNode expected,
            ClosureAttemptResult completedAttempt,
            ClosureImplementationEvidence implementation) {
        assertNotNull(implementation, "implementation evidence");
        assertAll(
                "full production closure result",
                () -> assertNormalizedStateBeforeIdentities(
                        expected, completedAttempt.processResult()),
                () -> assertAttempt(expected, completedAttempt),
                () -> assertImplementationEvidence(
                        expected, implementation));
    }

    /**
     * Reports all state-level deltas together.  This deliberately runs before
     * derived identity assertions so an output-closure mismatch can be
     * attributed to bodies, graph state, or identity construction precisely.
     */
    private static void assertNormalizedStateBeforeIdentities(
            JsonNode expected,
            ClosureProcessResult actual) {
        assertNotNull(actual, "processResult");
        StateDeltas deltas = new StateDeltas();
        deltas.equal("graphGeneration",
                Long.valueOf(requiredLong(expected, "graphGeneration")),
                Long.valueOf(actual.graphGeneration()));

        JsonNode documents = requiredArray(expected, "resultingDocuments");
        deltas.equal("resultingDocuments.size",
                Integer.valueOf(documents.size()),
                Integer.valueOf(actual.resultingDocuments().size()));
        int documentCount = Math.min(
                documents.size(), actual.resultingDocuments().size());
        for (int index = 0; index < documentCount; index++) {
            JsonNode item = documents.get(index);
            ResultingDocument value = actual.resultingDocuments().get(index);
            String path = "resultingDocuments[" + index + "]";
            deltas.equal(path + ".documentId",
                    requiredText(item, "documentId"),
                    value.documentId().value());
            deltas.equal(path + ".beforeBlueId",
                    requiredText(item, "beforeBlueId"),
                    value.beforeBlueId());
            deltas.equal(path + ".afterBlueId",
                    requiredText(item, "afterBlueId"), value.afterBlueId());
            deltas.equal(path + ".document",
                    required(item, "document"), wire(value.document()));
            deltas.equal(path + ".initialized",
                    Boolean.valueOf(requiredBoolean(item, "initialized")),
                    Boolean.valueOf(value.initialized()));
            deltas.equal(path + ".terminated",
                    Boolean.valueOf(requiredBoolean(item, "terminated")),
                    Boolean.valueOf(value.terminated()));
            deltas.equal(path + ".publicRoot",
                    Boolean.valueOf(requiredBoolean(item, "publicRoot")),
                    Boolean.valueOf(value.publicRoot()));
            deltas.equal(path + ".epoch",
                    Long.valueOf(requiredLong(item, "epoch")),
                    Long.valueOf(value.epoch()));
            deltas.equal(path + ".componentGeneration",
                    Long.valueOf(requiredLong(item, "componentGeneration")),
                    Long.valueOf(value.componentGeneration()));
            deltas.equal(path + ".componentIdentity",
                    requiredText(item, "componentIdentity"),
                    value.componentIdentity());
            deltas.equal(path + ".componentStateIdentity",
                    requiredText(item, "componentStateIdentity"),
                    value.componentStateIdentity());
            deltas.equal(path + ".memberIndex",
                    nullableLong(item, "memberIndex"), value.memberIndex());
        }

        JsonNode components = requiredArray(expected, "resultingComponents");
        deltas.equal("resultingComponents.size",
                Integer.valueOf(components.size()),
                Integer.valueOf(actual.resultingComponents().size()));
        int componentCount = Math.min(
                components.size(), actual.resultingComponents().size());
        for (int index = 0; index < componentCount; index++) {
            JsonNode item = components.get(index);
            ComponentSnapshot value = actual.resultingComponents().get(index);
            String path = "resultingComponents[" + index + "]";
            deltas.equal(path + ".componentIdentity",
                    requiredText(item, "componentIdentity"),
                    value.componentIdentity());
            deltas.equal(path + ".componentStateIdentity",
                    requiredText(item, "componentStateIdentity"),
                    value.componentStateIdentity());
            deltas.equal(path + ".componentGeneration",
                    Long.valueOf(requiredLong(item, "componentGeneration")),
                    Long.valueOf(value.componentGeneration()));
            deltas.equal(path + ".kind", requiredText(item, "kind"),
                    value.kind().name());
            deltas.equal(path + ".orderedMemberDocumentIds",
                    textValues(requiredArray(
                            item, "orderedMemberDocumentIds")),
                    documentIdValues(value.orderedMemberDocumentIds()));
            deltas.equal(path + ".orderedMemberBlueIds",
                    textValues(requiredArray(item, "orderedMemberBlueIds")),
                    value.orderedMemberBlueIds());
            deltas.equal(path + ".masterBlueId",
                    nullableText(item, "masterBlueId"), value.masterBlueId());
            deltas.equal(path + ".cyclicProofIdentity",
                    nullableText(item, "cyclicProofIdentity"),
                    value.cyclicProofIdentity());
            JsonNode proof = item.get("completeCyclicProof");
            List<Node> placeholders = value.completeCyclicProof() == null
                    ? java.util.Collections.<Node>emptyList()
                    : value.completeCyclicProof().declaredPlaceholderSet();
            JsonNode expectedPlaceholders = proof == null || proof.isNull()
                    ? JsonNodeFactory.instance.arrayNode()
                    : requiredArray(proof, "declaredPlaceholderSet");
            deltas.equal(path + ".completeCyclicProof.placeholderCount",
                    Integer.valueOf(expectedPlaceholders.size()),
                    Integer.valueOf(placeholders.size()));
            int placeholderCount = Math.min(
                    expectedPlaceholders.size(), placeholders.size());
            for (int placeholder = 0;
                    placeholder < placeholderCount;
                    placeholder++) {
                deltas.equal(path + ".completeCyclicProof"
                                + ".declaredPlaceholderSet[" + placeholder + "]",
                        expectedPlaceholders.get(placeholder),
                        wire(placeholders.get(placeholder)));
            }
        }

        JsonNode occurrences = requiredArray(
                expected, "occurrenceBindings");
        deltas.equal("occurrenceBindings.size",
                Integer.valueOf(occurrences.size()),
                Integer.valueOf(actual.occurrenceBindings().size()));
        int occurrenceCount = Math.min(
                occurrences.size(), actual.occurrenceBindings().size());
        for (int index = 0; index < occurrenceCount; index++) {
            JsonNode item = occurrences.get(index);
            ManagedOccurrenceBinding value =
                    actual.occurrenceBindings().get(index);
            String path = "occurrenceBindings[" + index + "]";
            deltas.equal(path + ".occurrenceIdentity",
                    requiredText(item, "occurrenceIdentity"),
                    value.occurrenceIdentity());
            deltas.equal(path + ".bindingIdentity",
                    requiredText(item, "bindingIdentity"),
                    value.bindingIdentity());
            deltas.equal(path + ".sourceDocumentId",
                    requiredText(item, "sourceDocumentId"),
                    value.sourceDocumentId().value());
            deltas.equal(path + ".sourcePath",
                    requiredText(item, "sourcePath"), value.sourcePath());
            deltas.equal(path + ".activationGeneration",
                    Long.valueOf(requiredLong(item, "activationGeneration")),
                    Long.valueOf(value.activationGeneration()));
            deltas.equal(path + ".targetDocumentId",
                    requiredText(item, "targetDocumentId"),
                    value.targetDocumentId().value());
            deltas.equal(path + ".bindingPolicyIdentity",
                    requiredText(item, "bindingPolicyIdentity"),
                    value.bindingPolicyIdentity());
            deltas.equal(path + ".expectedTargetBlueId",
                    requiredText(item, "expectedTargetBlueId"),
                    value.expectedTargetBlueId());
            deltas.equal(path + ".active",
                    Boolean.valueOf(requiredBoolean(item, "active")),
                    Boolean.valueOf(value.active()));
            deltas.equal(path + ".pendingHistoricalEpoch",
                    nullableLong(item, "pendingHistoricalEpoch"),
                    value.pendingHistoricalEpoch());
        }

        JsonNode changes = requiredArray(expected, "graphChanges");
        deltas.equal("graphChanges.size", Integer.valueOf(changes.size()),
                Integer.valueOf(actual.graphChanges().size()));
        int changeCount = Math.min(
                changes.size(), actual.graphChanges().size());
        for (int index = 0; index < changeCount; index++) {
            JsonNode item = changes.get(index);
            GraphChange value = actual.graphChanges().get(index);
            String path = "graphChanges[" + index + "]";
            deltas.equal(path + ".graphChangeOrdinal",
                    Long.valueOf(requiredLong(item, "graphChangeOrdinal")),
                    Long.valueOf(value.graphChangeOrdinal()));
            deltas.equal(path + ".changeKind",
                    requiredText(item, "changeKind"),
                    value.changeKind().name());
            deltas.equal(path + ".sourceDocumentId",
                    requiredText(item, "sourceDocumentId"),
                    value.sourceDocumentId().value());
            deltas.equal(path + ".sourcePath",
                    requiredText(item, "sourcePath"), value.sourcePath());
            deltas.equal(path + ".beforeActivationGeneration",
                    nullableLong(item, "beforeActivationGeneration"),
                    value.beforeActivationGeneration());
            deltas.equal(path + ".beforeOccurrenceIdentity",
                    nullableText(item, "beforeOccurrenceIdentity"),
                    value.beforeOccurrenceIdentity());
            deltas.equal(path + ".beforeBindingIdentity",
                    nullableText(item, "beforeBindingIdentity"),
                    value.beforeBindingIdentity());
            deltas.equal(path + ".beforeTargetDocumentId",
                    nullableText(item, "beforeTargetDocumentId"),
                    documentIdValue(value.beforeTargetDocumentId()));
            deltas.equal(path + ".beforeTargetBlueId",
                    nullableText(item, "beforeTargetBlueId"),
                    value.beforeTargetBlueId());
            deltas.equal(path + ".afterActivationGeneration",
                    nullableLong(item, "afterActivationGeneration"),
                    value.afterActivationGeneration());
            deltas.equal(path + ".afterOccurrenceIdentity",
                    nullableText(item, "afterOccurrenceIdentity"),
                    value.afterOccurrenceIdentity());
            deltas.equal(path + ".afterBindingIdentity",
                    nullableText(item, "afterBindingIdentity"),
                    value.afterBindingIdentity());
            deltas.equal(path + ".afterTargetDocumentId",
                    nullableText(item, "afterTargetDocumentId"),
                    documentIdValue(value.afterTargetDocumentId()));
            deltas.equal(path + ".afterTargetBlueId",
                    nullableText(item, "afterTargetBlueId"),
                    value.afterTargetBlueId());
        }

        JsonNode subscriptions = requiredArray(
                expected, "subscriptionDeltas");
        deltas.equal("subscriptionDeltas.size",
                Integer.valueOf(subscriptions.size()),
                Integer.valueOf(actual.subscriptionDeltas().size()));
        int subscriptionCount = Math.min(
                subscriptions.size(), actual.subscriptionDeltas().size());
        for (int index = 0; index < subscriptionCount; index++) {
            JsonNode item = subscriptions.get(index);
            SubscriptionDelta value = actual.subscriptionDeltas().get(index);
            String path = "subscriptionDeltas[" + index + "]";
            deltas.equal(path + ".subscriptionDeltaOrdinal",
                    Long.valueOf(requiredLong(
                            item, "subscriptionDeltaOrdinal")),
                    Long.valueOf(value.subscriptionDeltaOrdinal()));
            deltas.equal(path + ".operation",
                    requiredText(item, "operation"),
                    value.operation().name());
            deltas.equal(path + ".targetManagedScopeIdentity",
                    requiredText(item, "targetManagedScopeIdentity"),
                    value.targetManagedScopeIdentity());
            deltas.equal(path + ".channelOccurrenceIdentity",
                    requiredText(item, "channelOccurrenceIdentity"),
                    value.channelOccurrenceIdentity());
            deltas.equal(path + ".beforeSubscriptionIdentity",
                    nullableText(item, "beforeSubscriptionIdentity"),
                    value.beforeSubscriptionIdentity());
            deltas.equal(path + ".afterSubscriptionIdentity",
                    nullableText(item, "afterSubscriptionIdentity"),
                    value.afterSubscriptionIdentity());
            deltas.equal(path + ".beforeDocumentBlueId",
                    nullableText(item, "beforeDocumentBlueId"),
                    value.beforeDocumentBlueId());
            deltas.equal(path + ".afterDocumentBlueId",
                    nullableText(item, "afterDocumentBlueId"),
                    value.afterDocumentBlueId());
            deltas.equal(path + ".beforeGraphGeneration",
                    nullableLong(item, "beforeGraphGeneration"),
                    value.beforeGraphGeneration());
            deltas.equal(path + ".afterGraphGeneration",
                    nullableLong(item, "afterGraphGeneration"),
                    value.afterGraphGeneration());
            deltas.equal(path + ".beforeComponentGeneration",
                    nullableLong(item, "beforeComponentGeneration"),
                    value.beforeComponentGeneration());
            deltas.equal(path + ".afterComponentGeneration",
                    nullableLong(item, "afterComponentGeneration"),
                    value.afterComponentGeneration());
            collectSubscriptionStateDeltas(deltas,
                    path + ".beforeSubscription",
                    item.get("beforeSubscription"),
                    value.beforeSubscription());
            collectSubscriptionStateDeltas(deltas,
                    path + ".afterSubscription",
                    item.get("afterSubscription"),
                    value.afterSubscription());
        }

        if (!deltas.values.isEmpty()) {
            fail("normalized closure state deltas:\n"
                    + String.join("\n", deltas.values));
        }
    }

    private static void collectSubscriptionStateDeltas(
            StateDeltas deltas,
            String path,
            JsonNode expected,
            SubscriptionState actual) {
        if (expected == null || expected.isNull() || actual == null) {
            deltas.equal(path + ".presence",
                    Boolean.valueOf(expected != null && !expected.isNull()),
                    Boolean.valueOf(actual != null));
            return;
        }
        deltas.equal(path + ".subscriptionIdentity",
                requiredText(expected, "subscriptionIdentity"),
                actual.subscriptionIdentity());
        deltas.equal(path + ".documentBlueId",
                requiredText(expected, "documentBlueId"),
                actual.documentBlueId());
        deltas.equal(path + ".graphGeneration",
                Long.valueOf(requiredLong(expected, "graphGeneration")),
                Long.valueOf(actual.graphGeneration()));
        deltas.equal(path + ".componentGeneration",
                Long.valueOf(requiredLong(
                        expected, "componentGeneration")),
                Long.valueOf(actual.componentGeneration()));
        JsonNode expectedOccurrence = requiredObject(
                expected, "channelOccurrence");
        ChannelOccurrence occurrence = actual.channelOccurrence();
        String occurrencePath = path + ".channelOccurrence";
        deltas.equal(occurrencePath + ".channelOccurrenceIdentity",
                requiredText(
                        expectedOccurrence, "channelOccurrenceIdentity"),
                occurrence.channelOccurrenceIdentity());
        deltas.equal(occurrencePath + ".managedDocumentId",
                requiredText(expectedOccurrence, "managedDocumentId"),
                occurrence.managedDocumentId().value());
        deltas.equal(occurrencePath + ".scopePath",
                requiredText(expectedOccurrence, "scopePath"),
                occurrence.scopePath());
        deltas.equal(occurrencePath + ".scopeActivationGeneration",
                Long.valueOf(requiredLong(
                        expectedOccurrence, "scopeActivationGeneration")),
                Long.valueOf(occurrence.scopeActivationGeneration()));
        deltas.equal(occurrencePath + ".rawChannelKey",
                requiredText(expectedOccurrence, "rawChannelKey"),
                occurrence.rawChannelKey());
        deltas.equal(occurrencePath
                        + ".effectiveRuntimeContributionBlueId",
                requiredText(expectedOccurrence,
                        "effectiveRuntimeContributionBlueId"),
                occurrence.effectiveRuntimeContributionBlueId());
        deltas.equal(occurrencePath + ".subscriptionHeaderBlueId",
                requiredText(expectedOccurrence, "subscriptionHeaderBlueId"),
                occurrence.subscriptionHeaderBlueId());
    }

    private static void assertAttempt(
            JsonNode expected,
            ClosureAttemptResult attempt) {
        assertNotNull(attempt, "closure attempt");
        assertEquals(
                requiredText(expected, "attemptOutcome"),
                attempt.isComplete() ? "Complete" : "NeedsResources",
                "attemptOutcome");
        assertTrue(attempt.isComplete(), "fixture must complete");
        assertEquals(0, attempt.requiredExactBlueIds().size(),
                "requiredExactBlueIds");
        assertNotNull(attempt.totalGas(), "attempt totalGas");

        ClosureProcessResult actual = attempt.processResult();
        assertNotNull(actual, "processResult");
        assertEquals(requiredText(expected, "status"),
                actual.status().wireValue(), "status");
        assertEquals("success".equals(requiredText(expected, "status")),
                actual.commits(), "commits");
        assertEquals(requiredBoolean(expected, "atomic"),
                actual.atomic(), "atomic");
        assertEquals(requiredText(expected, "invocationIdentity"),
                actual.invocationIdentity(), "invocationIdentity");
        assertEquals(requiredText(expected, "inputClosureIdentity"),
                actual.inputClosureIdentity(), "inputClosureIdentity");
        assertEquals(requiredText(expected, "outputClosureIdentity"),
                actual.outputClosureIdentity(), "outputClosureIdentity");
        assertEquals(requiredLong(expected, "graphGeneration"),
                actual.graphGeneration(), "graphGeneration");

        assertResultingDocuments(
                requiredArray(expected, "resultingDocuments"),
                actual.resultingDocuments());
        assertComponents(
                requiredArray(expected, "resultingComponents"),
                actual.resultingComponents());
        assertOccurrences(
                requiredArray(expected, "occurrenceBindings"),
                actual.occurrenceBindings());
        assertEquals(requiredText(expected, "occurrenceBindingSetIdentity"),
                actual.occurrenceBindingSetIdentity(),
                "occurrenceBindingSetIdentity");
        assertGraphChanges(requiredArray(expected, "graphChanges"),
                actual.graphChanges());
        assertEquals(requiredText(expected, "graphChangesIdentity"),
                actual.graphChangesIdentity(), "graphChangesIdentity");
        assertSubscriptionDeltas(
                requiredArray(expected, "subscriptionDeltas"),
                actual.subscriptionDeltas());
        assertEquals(requiredText(expected, "subscriptionDeltasIdentity"),
                actual.subscriptionDeltasIdentity(),
                "subscriptionDeltasIdentity");
        assertCheckpointWrites(
                requiredArray(expected, "checkpointWrites"),
                actual.checkpointWrites());
        assertEquals(requiredText(expected, "checkpointWritesIdentity"),
                actual.checkpointWritesIdentity(),
                "checkpointWritesIdentity");
        assertPublicEvents(requiredArray(expected, "publicEvents"),
                actual.publicEvents());
        assertEquals(requiredText(expected, "publicEventsIdentity"),
                actual.publicEventsIdentity(), "publicEventsIdentity");
        assertEquals(requiredBoolean(expected, "rollbackToInput"),
                actual.rollbackToInput(), "rollbackToInput");
        assertSame(actual.platformCommitCompanion(),
                actual.commitCompanion(), "commit companion alias");
        assertAll(
                "failure, gas, and companion evidence",
                () -> assertRejectedCharge(expected.get("rejectedCharge"),
                        actual.rejectedCharge()),
                () -> assertRejectedWorkOccurrence(
                        expected.get("rejectedWorkOccurrence"),
                        actual.rejectedWorkOccurrence()),
                () -> assertDiagnostic(
                        expected.get("diagnostic"), actual.diagnostic()),
                () -> assertEquals(requiredLong(expected, "totalGas"),
                        actual.totalGas(), "totalGas"),
                () -> assertEquals(actual.totalGas(),
                        attempt.totalGas().longValue(),
                        "attempt/result totalGas"),
                () -> assertGasTrace(requiredArray(expected, "gasTrace"),
                        actual.gasTrace()),
                () -> assertOptionalCommitCompanion(
                        expected.get("platformCommitCompanion"), actual),
                () -> assertGasAndCompanionIdentities(
                        expected, actual));
    }

    private static void assertImplementationEvidence(
            JsonNode expected,
            ClosureImplementationEvidence actual) {
        assertEquals(requiredText(expected, "invocationIdentity"),
                actual.invocationIdentity(),
                "implementation invocationIdentity");
        assertWorkTrace(requiredArray(expected, "workTrace"),
                actual.workTrace());
        assertDocumentSteps(requiredArray(expected, "documentStepTrace"),
                actual.documentStepTrace());
        assertTentativeFinalizations(
                requiredArray(expected, "tentativeFinalizations"),
                actual.tentativeFinalizations());
    }

    private static void assertDiagnostic(
            JsonNode expected,
            blue.language.processor.ProcessorDiagnostic actual) {
        if (expected == null || expected.isNull()) {
            assertNull(actual, "diagnostic");
            return;
        }
        assertNotNull(actual, "diagnostic");
        assertEquals(expected.asText(), actual.category().name(),
                "diagnostic.category");
    }

    private static void assertRejectedWorkOccurrence(
            JsonNode expected,
            ClosureWorkOccurrence actual) {
        if (expected == null || expected.isNull()) {
            assertNull(actual, "rejectedWorkOccurrence");
            return;
        }
        assertNotNull(actual, "rejectedWorkOccurrence");
        assertWorkTrace(
                JsonNodeFactory.instance.arrayNode().add(expected),
                Collections.singletonList(actual));
    }

    private static void assertRejectedCharge(
            JsonNode expected,
            RejectedCharge actual) {
        if (expected == null || expected.isNull()) {
            assertNull(actual, "rejectedCharge");
            return;
        }
        assertNotNull(actual, "rejectedCharge");
        assertEquals(requiredText(expected, "namespace"),
                actual.namespace().wireValue(), "rejectedCharge.namespace");
        assertEquals(requiredText(expected, "counter"), actual.counter(),
                "rejectedCharge.counter");
        assertEquals(requiredLong(expected, "quantity"), actual.quantity(),
                "rejectedCharge.quantity");
        assertEquals(requiredLong(expected, "weight"), actual.weight(),
                "rejectedCharge.weight");
        assertEquals(requiredLong(expected, "subtotal"), actual.subtotal(),
                "rejectedCharge.subtotal");
        JsonNode cap = requiredObject(expected, "applicableCap");
        assertEquals(requiredText(cap, "kind"),
                actual.applicableCap().kind().name(),
                "rejectedCharge.applicableCap.kind");
        assertEquals(nullableText(cap, "documentId"),
                documentIdValue(actual.applicableCap().documentId()),
                "rejectedCharge.applicableCap.documentId");
        assertEquals(requiredLong(expected, "remainingBeforeCharge"),
                actual.remainingBeforeCharge(),
                "rejectedCharge.remainingBeforeCharge");
        JsonNode owner = requiredObject(expected, "owner");
        assertEquals(requiredText(owner, "kind"), actual.owner().kind().name(),
                "rejectedCharge.owner.kind");
        assertEquals(nullableText(owner, "workOccurrenceIdentity"),
                actual.owner().workOccurrenceIdentity(),
                "rejectedCharge.owner.workOccurrenceIdentity");
        assertEquals(nullableLong(owner, "finalizationOrdinal"),
                actual.owner().finalizationOrdinal(),
                "rejectedCharge.owner.finalizationOrdinal");
        assertEquals(nullableText(owner, "componentIdentity"),
                actual.owner().componentIdentity(),
                "rejectedCharge.owner.componentIdentity");
        assertEquals(nullableLong(owner, "componentGeneration"),
                actual.owner().componentGeneration(),
                "rejectedCharge.owner.componentGeneration");
        assertEquals(requiredText(expected, "rejectedChargeIdentity"),
                actual.rejectedChargeIdentity(),
                "rejectedCharge.rejectedChargeIdentity");
    }

    private static void assertWorkTrace(
            JsonNode expected,
            List<ClosureWorkOccurrence> actual) {
        int commonSize = Math.min(expected.size(), actual.size());
        for (int index = 0; index < commonSize; index++) {
            JsonNode item = expected.get(index);
            ClosureWorkOccurrence value = actual.get(index);
            String path = "workTrace[" + index + "]";
            assertEquals(requiredLong(item, "ordinal"), value.ordinal(),
                    path + ".ordinal");
            assertEquals(requiredText(item, "kind"), value.kind().name(),
                    path + ".kind");
            assertDocumentId(item, "targetDocumentId",
                    value.targetDocumentId(), path);
            assertEquals(requiredText(item, "channelKey"),
                    value.channelKey(), path + ".channelKey");
            assertEquals(requiredText(item, "workIdentity"),
                    value.workIdentity(), path + ".workIdentity");
            assertWorkEventBlueId(item, value, path);
            assertEquals(nullableLong(item, "occurrenceOrdinal"),
                    value.occurrenceOrdinal(), path + ".occurrenceOrdinal");
            assertEquals(requiredText(item, "targetManagedScopeIdentity"),
                    value.targetManagedScopeIdentity(),
                    path + ".targetManagedScopeIdentity");
            assertEquals(nullableText(item, "sourceOccurrenceIdentity"),
                    value.sourceOccurrenceIdentity(),
                    path + ".sourceOccurrenceIdentity");
        }
        String firstExtra = actual.size() > expected.size()
                ? "; first actual extra ordinal="
                        + actual.get(expected.size()).ordinal()
                        + ", kind=" + actual.get(expected.size()).kind()
                        + ", workIdentity="
                        + actual.get(expected.size()).workIdentity()
                : "";
        assertEquals(expected.size(), actual.size(),
                "workTrace size" + firstExtra);
    }

    private static void assertDocumentSteps(
            JsonNode expected,
            List<DocumentStepEvidence> actual) {
        assertSize("documentStepTrace", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            DocumentStepEvidence value = actual.get(index);
            String path = "documentStepTrace[" + index + "]";
            assertEquals(requiredLong(item, "stepOrdinal"),
                    value.stepOrdinal(), path + ".stepOrdinal");
            assertEquals(requiredLong(item, "workOrdinal"),
                    value.workOrdinal(), path + ".workOrdinal");
            assertDocumentId(item, "targetDocumentId",
                    value.targetDocumentId(), path);
            assertDocumentId(item, "executionRootDocumentId",
                    value.executionRootDocumentId(), path);
            assertEquals(requiredText(item, "scopePath"),
                    value.scopePath(), path + ".scopePath");
            assertEquals(requiredText(item, "executionMode"),
                    value.executionMode(), path + ".executionMode");
            JsonNode ambient = requiredArray(
                    item, "ambientContainingDocumentIds");
            assertEquals(textValues(ambient),
                    documentIdValues(value.ambientContainingDocumentIds()),
                    path + ".ambientContainingDocumentIds");
        }
    }

    private static void assertTentativeFinalizations(
            JsonNode expected,
            List<TentativeFinalization> actual) {
        assertEquals(expected.size(), actual.size(),
                "tentativeFinalizations size; expected="
                        + expectedFinalizationSummary(expected)
                        + " actual=" + actualFinalizationSummary(actual));
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            TentativeFinalization value = actual.get(index);
            String path = "tentativeFinalizations[" + index + "]";
            assertEquals(requiredLong(item, "ordinal"), value.ordinal(),
                    path + ".ordinal");
            JsonNode boundary = requiredObject(item, "boundary");
            assertEquals(requiredText(boundary, "kind"),
                    value.boundary().kind().name(), path + ".boundary.kind");
            assertEquals(nullableLong(boundary, "afterWorkOrdinal"),
                    value.boundary().afterWorkOrdinal(),
                    path + ".boundary.afterWorkOrdinal");
            assertEquals(requiredText(item, "masterBlueId"),
                    value.masterBlueId(), path + ".masterBlueId");
            assertEquals(requiredLong(item, "canonicalBytes"),
                    value.canonicalBytes(), path + ".canonicalBytes");
            assertEquals(textMap(requiredObject(item, "memberBlueIds")),
                    documentTextMap(value.memberBlueIds()),
                    path + ".memberBlueIds");
        }
    }

    private static String expectedFinalizationSummary(JsonNode values) {
        List<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            JsonNode boundary = requiredObject(value, "boundary");
            List<String> members = new ArrayList<String>();
            requiredObject(value, "memberBlueIds").fieldNames()
                    .forEachRemaining(members::add);
            result.add(requiredText(boundary, "kind") + "@"
                    + nullableLong(boundary, "afterWorkOrdinal") + ":"
                    + members
                    + ":" + requiredText(value, "masterBlueId"));
        }
        return result.toString();
    }

    private static String actualFinalizationSummary(
            List<TentativeFinalization> values) {
        List<String> result = new ArrayList<String>();
        for (TentativeFinalization value : values) {
            List<String> members = new ArrayList<String>();
            for (DocumentId documentId : value.memberBlueIds().keySet()) {
                members.add(documentId.value());
            }
            result.add(value.boundary().kind().name() + "@"
                    + value.boundary().afterWorkOrdinal() + ":" + members
                    + ":" + value.masterBlueId());
        }
        return result.toString();
    }

    private static void assertResultingDocuments(
            JsonNode expected,
            List<ResultingDocument> actual) {
        assertSize("resultingDocuments", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ResultingDocument value = actual.get(index);
            String path = "resultingDocuments[" + index + "]";
            assertDocumentId(item, "documentId", value.documentId(), path);
            assertEquals(requiredText(item, "beforeBlueId"),
                    value.beforeBlueId(), path + ".beforeBlueId");
            assertEquals(requiredText(item, "afterBlueId"),
                    value.afterBlueId(), path + ".afterBlueId");
            assertNode(required(item, "document"), value.document(),
                    path + ".document");
            assertEquals(requiredBoolean(item, "initialized"),
                    value.initialized(), path + ".initialized");
            assertEquals(requiredBoolean(item, "terminated"),
                    value.terminated(), path + ".terminated");
            assertEquals(requiredBoolean(item, "publicRoot"),
                    value.publicRoot(), path + ".publicRoot");
            assertEquals(requiredLong(item, "epoch"), value.epoch(),
                    path + ".epoch");
            assertEquals(requiredLong(item, "componentGeneration"),
                    value.componentGeneration(),
                    path + ".componentGeneration");
            assertEquals(requiredText(item, "componentIdentity"),
                    value.componentIdentity(), path + ".componentIdentity");
            assertEquals(requiredText(item, "componentStateIdentity"),
                    value.componentStateIdentity(),
                    path + ".componentStateIdentity");
            assertEquals(nullableLong(item, "memberIndex"),
                    value.memberIndex(), path + ".memberIndex");
        }
    }

    private static void assertComponents(
            JsonNode expected,
            List<ComponentSnapshot> actual) {
        assertSize("resultingComponents", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ComponentSnapshot value = actual.get(index);
            String path = "resultingComponents[" + index + "]";
            assertEquals(requiredText(item, "componentIdentity"),
                    value.componentIdentity(), path + ".componentIdentity");
            assertEquals(requiredLong(item, "componentGeneration"),
                    value.componentGeneration(),
                    path + ".componentGeneration");
            assertEquals(requiredText(item, "kind"), value.kind().name(),
                    path + ".kind");
            assertEquals(textValues(requiredArray(
                            item, "orderedMemberDocumentIds")),
                    documentIdValues(value.orderedMemberDocumentIds()),
                    path + ".orderedMemberDocumentIds");
            assertEquals(textValues(requiredArray(
                            item, "orderedMemberBlueIds")),
                    value.orderedMemberBlueIds(),
                    path + ".orderedMemberBlueIds");
            assertEquals(nullableText(item, "masterBlueId"),
                    value.masterBlueId(), path + ".masterBlueId");
            assertEquals(nullableText(item, "cyclicProofIdentity"),
                    value.cyclicProofIdentity(),
                    path + ".cyclicProofIdentity");
            assertEquals(requiredText(item, "componentStateIdentity"),
                    value.componentStateIdentity(),
                    path + ".componentStateIdentity");
            assertCyclicProof(item.get("completeCyclicProof"), value, path);
        }
    }

    private static void assertCyclicProof(
            JsonNode expected,
            ComponentSnapshot component,
            String path) {
        CyclicSetProof proof = component.completeCyclicProof();
        if (expected == null || expected.isNull()) {
            assertNull(proof, path + ".completeCyclicProof");
            return;
        }
        assertNotNull(proof, path + ".completeCyclicProof");
        assertEquals(requiredText(expected, "componentIdentity"),
                component.componentIdentity(),
                path + ".completeCyclicProof.componentIdentity");
        assertEquals(requiredText(expected, "masterBlueId"),
                component.masterBlueId(),
                path + ".completeCyclicProof.masterBlueId");
        JsonNode members = requiredArray(expected, "memberStates");
        assertEquals(component.orderedMemberDocumentIds().size(),
                members.size(), path + ".completeCyclicProof.memberStates");
        for (int index = 0; index < members.size(); index++) {
            JsonNode member = members.get(index);
            assertEquals(requiredText(member, "documentId"),
                    component.orderedMemberDocumentIds().get(index).value(),
                    path + ".completeCyclicProof.memberStates[" + index
                            + "].documentId");
            assertEquals(requiredText(member, "blueId"),
                    component.orderedMemberBlueIds().get(index),
                    path + ".completeCyclicProof.memberStates[" + index
                            + "].blueId");
        }
        JsonNode placeholders = requiredArray(
                expected, "declaredPlaceholderSet");
        List<Node> actualPlaceholders = proof.declaredPlaceholderSet();
        assertSize(path + ".completeCyclicProof.declaredPlaceholderSet",
                placeholders, actualPlaceholders);
        for (int index = 0; index < placeholders.size(); index++) {
            assertNode(placeholders.get(index), actualPlaceholders.get(index),
                    path + ".completeCyclicProof.declaredPlaceholderSet["
                            + index + "]");
        }
    }

    private static void assertOccurrences(
            JsonNode expected,
            List<ManagedOccurrenceBinding> actual) {
        assertSize("occurrenceBindings", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ManagedOccurrenceBinding value = actual.get(index);
            String path = "occurrenceBindings[" + index + "]";
            assertEquals(requiredText(item, "occurrenceIdentity"),
                    value.occurrenceIdentity(), path + ".occurrenceIdentity");
            assertEquals(requiredText(item, "bindingIdentity"),
                    value.bindingIdentity(), path + ".bindingIdentity");
            assertDocumentId(item, "sourceDocumentId",
                    value.sourceDocumentId(), path);
            assertEquals(requiredText(item, "sourcePath"),
                    value.sourcePath(), path + ".sourcePath");
            assertEquals(requiredLong(item, "activationGeneration"),
                    value.activationGeneration(),
                    path + ".activationGeneration");
            assertDocumentId(item, "targetDocumentId",
                    value.targetDocumentId(), path);
            assertEquals(requiredText(item, "bindingPolicyIdentity"),
                    value.bindingPolicyIdentity(),
                    path + ".bindingPolicyIdentity");
            assertEquals(requiredText(item, "expectedTargetBlueId"),
                    value.expectedTargetBlueId(),
                    path + ".expectedTargetBlueId");
            assertEquals(requiredBoolean(item, "active"), value.active(),
                    path + ".active");
            assertEquals(nullableLong(item, "pendingHistoricalEpoch"),
                    value.pendingHistoricalEpoch(),
                    path + ".pendingHistoricalEpoch");
        }
    }

    private static void assertGraphChanges(
            JsonNode expected,
            List<GraphChange> actual) {
        assertSize("graphChanges", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            GraphChange value = actual.get(index);
            String path = "graphChanges[" + index + "]";
            assertEquals(requiredLong(item, "graphChangeOrdinal"),
                    value.graphChangeOrdinal(),
                    path + ".graphChangeOrdinal");
            assertEquals(requiredText(item, "changeKind"),
                    value.changeKind().name(), path + ".changeKind");
            assertDocumentId(item, "sourceDocumentId",
                    value.sourceDocumentId(), path);
            assertEquals(requiredText(item, "sourcePath"),
                    value.sourcePath(), path + ".sourcePath");
            assertEquals(nullableLong(item, "beforeActivationGeneration"),
                    value.beforeActivationGeneration(),
                    path + ".beforeActivationGeneration");
            assertEquals(nullableText(item, "beforeOccurrenceIdentity"),
                    value.beforeOccurrenceIdentity(),
                    path + ".beforeOccurrenceIdentity");
            assertEquals(nullableText(item, "beforeBindingIdentity"),
                    value.beforeBindingIdentity(),
                    path + ".beforeBindingIdentity");
            assertNullableDocumentId(item, "beforeTargetDocumentId",
                    value.beforeTargetDocumentId(), path);
            assertEquals(nullableText(item, "beforeTargetBlueId"),
                    value.beforeTargetBlueId(),
                    path + ".beforeTargetBlueId");
            assertEquals(nullableLong(item, "afterActivationGeneration"),
                    value.afterActivationGeneration(),
                    path + ".afterActivationGeneration");
            assertEquals(nullableText(item, "afterOccurrenceIdentity"),
                    value.afterOccurrenceIdentity(),
                    path + ".afterOccurrenceIdentity");
            assertEquals(nullableText(item, "afterBindingIdentity"),
                    value.afterBindingIdentity(),
                    path + ".afterBindingIdentity");
            assertNullableDocumentId(item, "afterTargetDocumentId",
                    value.afterTargetDocumentId(), path);
            assertEquals(nullableText(item, "afterTargetBlueId"),
                    value.afterTargetBlueId(), path + ".afterTargetBlueId");
        }
    }

    private static void assertSubscriptionDeltas(
            JsonNode expected,
            List<SubscriptionDelta> actual) {
        assertSize("subscriptionDeltas", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            SubscriptionDelta value = actual.get(index);
            String path = "subscriptionDeltas[" + index + "]";
            assertEquals(requiredLong(item, "subscriptionDeltaOrdinal"),
                    value.subscriptionDeltaOrdinal(),
                    path + ".subscriptionDeltaOrdinal");
            assertEquals(requiredText(item, "operation"),
                    value.operation().name(), path + ".operation");
            assertEquals(requiredText(item, "targetManagedScopeIdentity"),
                    value.targetManagedScopeIdentity(),
                    path + ".targetManagedScopeIdentity");
            assertEquals(requiredText(item, "channelOccurrenceIdentity"),
                    value.channelOccurrenceIdentity(),
                    path + ".channelOccurrenceIdentity; actual inputs="
                            + channelOccurrenceInputs(value));
            assertEquals(nullableText(item, "beforeSubscriptionIdentity"),
                    value.beforeSubscriptionIdentity(),
                    path + ".beforeSubscriptionIdentity");
            assertEquals(nullableText(item, "afterSubscriptionIdentity"),
                    value.afterSubscriptionIdentity(),
                    path + ".afterSubscriptionIdentity");
            assertEquals(nullableText(item, "beforeDocumentBlueId"),
                    value.beforeDocumentBlueId(),
                    path + ".beforeDocumentBlueId");
            assertEquals(nullableText(item, "afterDocumentBlueId"),
                    value.afterDocumentBlueId(),
                    path + ".afterDocumentBlueId");
            assertEquals(nullableLong(item, "beforeGraphGeneration"),
                    value.beforeGraphGeneration(),
                    path + ".beforeGraphGeneration");
            assertEquals(nullableLong(item, "afterGraphGeneration"),
                    value.afterGraphGeneration(),
                    path + ".afterGraphGeneration");
            assertEquals(nullableLong(item, "beforeComponentGeneration"),
                    value.beforeComponentGeneration(),
                    path + ".beforeComponentGeneration");
            assertEquals(nullableLong(item, "afterComponentGeneration"),
                    value.afterComponentGeneration(),
                    path + ".afterComponentGeneration");
            assertSubscriptionState(item.get("beforeSubscription"),
                    value.beforeSubscription(), path + ".beforeSubscription");
            assertSubscriptionState(item.get("afterSubscription"),
                    value.afterSubscription(), path + ".afterSubscription");
        }
    }

    private static String channelOccurrenceInputs(SubscriptionDelta value) {
        SubscriptionState state = value.afterSubscription() == null
                ? value.beforeSubscription()
                : value.afterSubscription();
        ChannelOccurrence occurrence = state.channelOccurrence();
        return "{" + occurrence.managedDocumentId().value()
                + "," + occurrence.scopePath()
                + "," + occurrence.scopeActivationGeneration()
                + "," + occurrence.rawChannelKey()
                + "," + occurrence.effectiveRuntimeContributionBlueId()
                + "," + occurrence.subscriptionHeaderBlueId() + "}";
    }

    private static void assertSubscriptionState(
            JsonNode expected,
            SubscriptionState actual,
            String path) {
        if (expected == null || expected.isNull()) {
            assertNull(actual, path);
            return;
        }
        assertNotNull(actual, path);
        assertEquals(requiredText(expected, "subscriptionIdentity"),
                actual.subscriptionIdentity(), path + ".subscriptionIdentity");
        assertChannelOccurrence(
                requiredObject(expected, "channelOccurrence"),
                actual.channelOccurrence(), path + ".channelOccurrence");
        assertEquals(requiredText(expected, "documentBlueId"),
                actual.documentBlueId(), path + ".documentBlueId");
        assertEquals(requiredLong(expected, "graphGeneration"),
                actual.graphGeneration(), path + ".graphGeneration");
        assertEquals(requiredLong(expected, "componentGeneration"),
                actual.componentGeneration(), path + ".componentGeneration");
    }

    private static void assertChannelOccurrence(
            JsonNode expected,
            ChannelOccurrence actual,
            String path) {
        assertEquals(requiredText(expected, "channelOccurrenceIdentity"),
                actual.channelOccurrenceIdentity(),
                path + ".channelOccurrenceIdentity");
        assertDocumentId(expected, "managedDocumentId",
                actual.managedDocumentId(), path);
        assertEquals(requiredText(expected, "scopePath"),
                actual.scopePath(), path + ".scopePath");
        assertEquals(requiredLong(expected, "scopeActivationGeneration"),
                actual.scopeActivationGeneration(),
                path + ".scopeActivationGeneration");
        assertEquals(requiredText(expected, "rawChannelKey"),
                actual.rawChannelKey(), path + ".rawChannelKey");
        assertEquals(requiredText(
                        expected, "effectiveRuntimeContributionBlueId"),
                actual.effectiveRuntimeContributionBlueId(),
                path + ".effectiveRuntimeContributionBlueId");
        assertEquals(requiredText(expected, "subscriptionHeaderBlueId"),
                actual.subscriptionHeaderBlueId(),
                path + ".subscriptionHeaderBlueId");
    }

    private static void assertCheckpointWrites(
            JsonNode expected,
            List<CheckpointWrite> actual) {
        assertSize("checkpointWrites", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            CheckpointWrite value = actual.get(index);
            String path = "checkpointWrites[" + index + "]";
            assertEquals(requiredLong(item, "checkpointWriteOrdinal"),
                    value.checkpointWriteOrdinal(),
                    path + ".checkpointWriteOrdinal");
            assertEquals(requiredText(item, "targetManagedScopeIdentity"),
                    value.targetManagedScopeIdentity(),
                    path + ".targetManagedScopeIdentity");
            assertEquals(requiredText(item, "rawChannelKey"),
                    value.rawChannelKey(), path + ".rawChannelKey");
            assertEquals(requiredBoolean(item, "beforePresent"),
                    value.beforePresent(), path + ".beforePresent");
            assertEquals(nullableText(item, "beforeDomainBlueId"),
                    value.beforeDomainBlueId(), path + ".beforeDomainBlueId");
            assertCheckpointDomain(item.get("beforeDomainValue"),
                    value.beforeDomainValue(), value.beforeDomainBlueId(),
                    path + ".beforeDomainValue");
            assertEquals(nullableText(item, "beforeSubjectBlueId"),
                    value.beforeSubjectBlueId(),
                    path + ".beforeSubjectBlueId");
            assertEquals(requiredBoolean(item, "afterPresent"),
                    value.afterPresent(), path + ".afterPresent");
            assertEquals(nullableText(item, "afterDomainBlueId"),
                    value.afterDomainBlueId(), path + ".afterDomainBlueId");
            assertCheckpointDomain(item.get("afterDomainValue"),
                    value.afterDomainValue(), value.afterDomainBlueId(),
                    path + ".afterDomainValue");
            assertEquals(nullableText(item, "afterSubjectBlueId"),
                    value.afterSubjectBlueId(),
                    path + ".afterSubjectBlueId");
        }
    }

    private static void assertCheckpointDomain(
            JsonNode expected,
            CheckpointDomainValue actual,
            String expectedBlueId,
            String path) {
        if (expected == null || expected.isNull()) {
            assertNull(actual, path);
            return;
        }
        assertNotNull(actual, path);
        assertEquals(requiredText(expected, "contractsVersion"),
                actual.contractsVersion(), path + ".contractsVersion");
        assertEquals(requiredText(expected, "effectiveTypeBlueId"),
                actual.effectiveTypeBlueId(), path + ".effectiveTypeBlueId");
        assertEquals(textValues(requiredArray(
                        expected, "sourceContributionNodeBlueIds")),
                actual.sourceContributionNodeBlueIds(),
                path + ".sourceContributionNodeBlueIds");
        JsonNode dependencies = expected.get(
                "deterministicDependencyNodeBlueIds");
        List<String> expectedDependencies = dependencies == null
                ? java.util.Collections.<String>emptyList()
                : textValues(requiredArray(
                        expected, "deterministicDependencyNodeBlueIds"));
        assertEquals(expectedDependencies,
                actual.deterministicDependencyNodeBlueIds(),
                path + ".deterministicDependencyNodeBlueIds");
        assertEquals(requiredText(expected, "runtimeDiscriminator"),
                actual.runtimeDiscriminator(),
                path + ".runtimeDiscriminator");
        assertEquals(expectedBlueId, actual.blueId(), path + ".blueId");
    }

    private static void assertPublicEvents(
            JsonNode expected,
            List<PublicEventOccurrence> actual) {
        assertSize("publicEvents", expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            PublicEventOccurrence value = actual.get(index);
            String path = "publicEvents[" + index + "]";
            assertEquals(requiredLong(item, "publicEventOrdinal"),
                    value.publicEventOrdinal(),
                    path + ".publicEventOrdinal");
            assertEquals(requiredLong(item, "eventOccurrenceOrdinal"),
                    value.eventOccurrenceOrdinal(),
                    path + ".eventOccurrenceOrdinal");
            assertDocumentId(item, "publicRootDocumentId",
                    value.publicRootDocumentId(), path);
            assertEquals(requiredText(item, "eventOccurrenceIdentity"),
                    value.eventOccurrenceIdentity(),
                    path + ".eventOccurrenceIdentity");
            assertEquals(requiredText(item, "eventBlueId"),
                    value.eventBlueId(), path + ".eventBlueId");
            assertNode(required(item, "event"), value.event(),
                    path + ".event");
        }
    }

    private static void assertGasTrace(
            JsonNode expected,
            List<GasTraceEntry> actual) {
        assertEquals(expected.size(), actual.size(),
                "gasTrace size; " + gasAggregateDelta(expected, actual)
                        + "; " + gasFirstDelta(expected, actual));
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            GasTraceEntry value = actual.get(index);
            String path = "gasTrace[" + index + "]";
            assertEquals(requiredLong(item, "sequence"), value.sequence(),
                    path + ".sequence");
            assertEquals(requiredText(item, "namespace"),
                    value.namespace().wireValue(), path + ".namespace");
            assertEquals(requiredText(item, "counter"), value.counter(),
                    path + ".counter");
            assertEquals(requiredLong(item, "quantity"), value.quantity(),
                    path + ".quantity");
            assertEquals(requiredLong(item, "weight"), value.weight(),
                    path + ".weight");
            assertEquals(requiredLong(item, "subtotal"), value.subtotal(),
                    path + ".subtotal");
            assertNullableDocumentId(item, "documentId",
                    value.documentId(), path);
            assertEquals(nullableText(item, "scopePath"),
                    value.scopePath(), path + ".scopePath");
            assertEquals(nullableLong(item, "activationGeneration"),
                    value.activationGeneration(),
                    path + ".activationGeneration");
            assertEquals(nullableLong(item, "componentGeneration"),
                    value.componentGeneration(),
                    path + ".componentGeneration");
            assertEquals(nullableText(item, "contractKey"),
                    value.contractKey(), path + ".contractKey");
            assertEquals(nullableText(item, "logicalPath"),
                    value.logicalPath(), path + ".logicalPath");
            assertEquals(nullableText(item, "workOccurrenceId"),
                    value.workOccurrenceId(), path + ".workOccurrenceId");
            // Contracts 1.0 makes reason optional diagnostic text and
            // deliberately excludes it from gasTraceIdentity.  The staged
            // package validates its canonical reference wording separately;
            // semantic production conformance compares no reason field.
        }
    }

    private static void assertWorkEventBlueId(
            JsonNode expected,
            ClosureWorkOccurrence actual,
            String path) {
        switch (actual.kind()) {
            case EXTERNAL_DELIVERY:
            case TRIGGERED_EVENT:
            case EMBEDDED_EVENT:
                assertEquals(requiredText(expected, "eventBlueId"),
                        actual.eventBlueId(), path + ".eventBlueId");
                return;
            case INITIALIZATION:
            case DOCUMENT_UPDATE:
            case LIFECYCLE:
            case CONTAINING_REFERENCE_UPDATE:
                assertNull(expected.get("eventBlueId"),
                        path + ".eventBlueId fixture shape");
                assertNull(actual.eventBlueId(), path + ".eventBlueId");
                return;
            default:
                throw new AssertionError(
                        "Unhandled work kind: " + actual.kind());
        }
    }

    private static String gasAggregateDelta(
            JsonNode expected,
            List<GasTraceEntry> actual) {
        Map<String, long[]> expectedTotals =
                new java.util.TreeMap<String, long[]>();
        for (JsonNode item : expected) {
            addGasAggregate(
                    expectedTotals,
                    requiredText(item, "namespace") + "/"
                            + requiredText(item, "counter"),
                    requiredLong(item, "quantity"),
                    requiredLong(item, "subtotal"));
        }
        Map<String, long[]> actualTotals =
                new java.util.TreeMap<String, long[]>();
        for (GasTraceEntry item : actual) {
            addGasAggregate(
                    actualTotals,
                    item.namespace().wireValue() + "/" + item.counter(),
                    item.quantity(),
                    item.subtotal());
        }
        List<String> deltas = new ArrayList<String>();
        java.util.Set<String> keys = new java.util.TreeSet<String>();
        keys.addAll(expectedTotals.keySet());
        keys.addAll(actualTotals.keySet());
        for (String key : keys) {
            long[] expectedValue = expectedTotals.get(key);
            long[] actualValue = actualTotals.get(key);
            if (expectedValue == null
                    || actualValue == null
                    || expectedValue[0] != actualValue[0]
                    || expectedValue[1] != actualValue[1]
                    || expectedValue[2] != actualValue[2]) {
                deltas.add(key + " expected=" + gasAggregate(expectedValue)
                        + " actual=" + gasAggregate(actualValue));
            }
        }
        return "counter deltas=" + deltas;
    }

    private static String gasFirstDelta(
            JsonNode expected,
            List<GasTraceEntry> actual) {
        int shared = Math.min(expected.size(), actual.size());
        int first = 0;
        while (first < shared
                && gasSemanticEquals(expected.get(first), actual.get(first))) {
            first++;
        }
        int end = Math.min(shared, first + 4);
        List<String> rows = new ArrayList<String>();
        for (int index = first; index < end; index++) {
            rows.add(index + " expected="
                    + gasSemanticSignature(expected.get(index))
                    + " actual=" + gasSemanticSignature(actual.get(index)));
        }
        if (rows.isEmpty()) {
            rows.add("common prefix=" + shared
                    + ", expected size=" + expected.size()
                    + ", actual size=" + actual.size());
        }
        return "first semantic delta=" + rows;
    }

    private static boolean gasSemanticEquals(
            JsonNode expected,
            GasTraceEntry actual) {
        return requiredLong(expected, "sequence") == actual.sequence()
                && requiredText(expected, "namespace").equals(
                        actual.namespace().wireValue())
                && requiredText(expected, "counter").equals(actual.counter())
                && requiredLong(expected, "quantity") == actual.quantity()
                && requiredLong(expected, "weight") == actual.weight()
                && requiredLong(expected, "subtotal") == actual.subtotal()
                && java.util.Objects.equals(
                        nullableText(expected, "documentId"),
                        documentIdValue(actual.documentId()))
                && java.util.Objects.equals(
                        nullableText(expected, "scopePath"),
                        actual.scopePath())
                && java.util.Objects.equals(
                        nullableLong(expected, "activationGeneration"),
                        actual.activationGeneration())
                && java.util.Objects.equals(
                        nullableLong(expected, "componentGeneration"),
                        actual.componentGeneration())
                && java.util.Objects.equals(
                        nullableText(expected, "contractKey"),
                        actual.contractKey())
                && java.util.Objects.equals(
                        nullableText(expected, "logicalPath"),
                        actual.logicalPath())
                && java.util.Objects.equals(
                        nullableText(expected, "workOccurrenceId"),
                        actual.workOccurrenceId());
    }

    private static String gasSemanticSignature(JsonNode value) {
        return requiredText(value, "namespace") + "/"
                + requiredText(value, "counter") + " x"
                + requiredLong(value, "quantity") + " @"
                + nullableText(value, "documentId") + ":"
                + nullableLong(value, "componentGeneration") + ":"
                + nullableText(value, "contractKey") + ":"
                + nullableText(value, "logicalPath");
    }

    private static String gasSemanticSignature(GasTraceEntry value) {
        return value.namespace().wireValue() + "/" + value.counter()
                + " x" + value.quantity() + " @"
                + documentIdValue(value.documentId()) + ":"
                + value.componentGeneration() + ":"
                + value.contractKey() + ":" + value.logicalPath();
    }

    private static void addGasAggregate(
            Map<String, long[]> values,
            String key,
            long quantity,
            long subtotal) {
        long[] aggregate = values.get(key);
        if (aggregate == null) {
            aggregate = new long[3];
            values.put(key, aggregate);
        }
        aggregate[0]++;
        aggregate[1] += quantity;
        aggregate[2] += subtotal;
    }

    private static String gasAggregate(long[] value) {
        return value == null
                ? "absent"
                : "{rows=" + value[0]
                        + ", quantity=" + value[1]
                        + ", subtotal=" + value[2] + "}";
    }

    private static void assertCommitCompanion(
            JsonNode expected,
            ClosureCommitCompanion actual,
            ClosureProcessResult result) {
        assertNotNull(actual, "platformCommitCompanion");
        assertEquals(requiredText(expected, "invocationIdentity"),
                actual.invocationIdentity(),
                "platformCommitCompanion.invocationIdentity");
        assertEquals(requiredText(expected, "inputClosureIdentity"),
                actual.inputClosureIdentity(),
                "platformCommitCompanion.inputClosureIdentity");
        assertEquals(requiredText(expected, "outputClosureIdentity"),
                actual.outputClosureIdentity(),
                "platformCommitCompanion.outputClosureIdentity");
        assertEquals(requiredLong(expected, "expectedInputGraphGeneration"),
                actual.expectedInputGraphGeneration(),
                "platformCommitCompanion.expectedInputGraphGeneration");
        assertCompanionInputDocuments(
                requiredArray(expected, "expectedInputDocuments"),
                actual.expectedInputDocuments());
        assertCompanionInputComponents(
                requiredArray(expected, "expectedInputComponents"),
                actual.expectedInputComponents());
        assertEquals(requiredText(
                        expected, "inputOccurrenceBindingSetIdentity"),
                actual.inputOccurrenceBindingSetIdentity(),
                "platformCommitCompanion.inputOccurrenceBindingSetIdentity");
        assertEquals(requiredLong(expected, "outputGraphGeneration"),
                actual.outputGraphGeneration(),
                "platformCommitCompanion.outputGraphGeneration");
        assertCompanionDocumentDeltas(
                requiredArray(expected, "resultingDocuments"),
                actual.resultingDocuments());
        assertCompanionResultComponents(
                requiredArray(expected, "resultingComponents"),
                actual.resultingComponents());
        assertEquals(requiredText(expected, "occurrenceBindingSetIdentity"),
                actual.occurrenceBindingSetIdentity(),
                "platformCommitCompanion.occurrenceBindingSetIdentity");
        assertEquals(actual.occurrenceBindingSetIdentity(),
                actual.outputOccurrenceBindingSetIdentity(),
                "platformCommitCompanion output identity alias");
        assertCompanionText(expected, "graphChangesIdentity",
                actual.graphChangesIdentity());
        assertCompanionText(expected, "checkpointWritesIdentity",
                actual.checkpointWritesIdentity());
        assertCompanionText(expected, "subscriptionDeltasIdentity",
                actual.subscriptionDeltasIdentity());
        assertCompanionText(expected, "publicEventsIdentity",
                actual.publicEventsIdentity());
        assertCompanionText(expected, "blueLanguageSpecificationIdentity",
                actual.blueLanguageSpecificationIdentity());
        assertCompanionText(expected, "contractsSpecificationIdentity",
                actual.contractsSpecificationIdentity());
        assertCompanionText(
                expected, "managedDocumentIdentityPolicyIdentity",
                actual.managedDocumentIdentityPolicyIdentity());
        assertCompanionText(expected, "managedBindingPolicyIdentity",
                actual.managedBindingPolicyIdentity());
        assertCompanionText(expected, "exactNodeProviderDomainIdentity",
                actual.exactNodeProviderDomainIdentity());
        assertCompanionText(expected, "externalOrderPolicyIdentity",
                actual.externalOrderPolicyIdentity());
        assertCompanionText(expected, "runtimeRegistryIdentity",
                actual.runtimeRegistryIdentity());
        assertCompanionText(expected, "gasManifestIdentity",
                actual.gasManifestIdentity());
        assertCompanionText(expected, "portableLimitPolicyIdentity",
                actual.portableLimitPolicyIdentity());
        assertCompanionText(expected, "cyclicFinalizerIdentity",
                actual.cyclicFinalizerIdentity());
        assertCompanionText(expected, "cyclicProofVerifierIdentity",
                actual.cyclicProofVerifierIdentity());

        assertEquals(result.invocationIdentity(), actual.invocationIdentity(),
                "result/companion invocationIdentity");
        assertEquals(result.inputClosureIdentity(),
                actual.inputClosureIdentity(),
                "result/companion inputClosureIdentity");
        assertEquals(result.outputClosureIdentity(),
                actual.outputClosureIdentity(),
                "result/companion outputClosureIdentity");
        assertEquals(result.graphGeneration(), actual.outputGraphGeneration(),
                "result/companion graphGeneration");
        assertEquals(result.gasTraceIdentity(), actual.gasTraceIdentity(),
                "result/companion gasTraceIdentity");
    }

    private static void assertOptionalCommitCompanion(
            JsonNode expected,
            ClosureProcessResult result) {
        if (expected == null || expected.isNull()) {
            assertNull(result.platformCommitCompanion(),
                    "platformCommitCompanion");
            return;
        }
        assertCommitCompanion(
                expected, result.platformCommitCompanion(), result);
    }

    private static void assertGasAndCompanionIdentities(
            JsonNode expected,
            ClosureProcessResult actual) {
        String fixtureGasIdentity = requiredText(
                expected, "gasTraceIdentity");
        ClosureCommitCompanion companion = actual.platformCommitCompanion();
        assertEquals(fixtureGasIdentity, actual.gasTraceIdentity(),
                "gasTraceIdentity");
        JsonNode expectedCompanion = expected.get("platformCommitCompanion");
        if (expectedCompanion == null || expectedCompanion.isNull()) {
            assertNull(companion, "platformCommitCompanion");
        } else {
            assertNotNull(companion, "platformCommitCompanion");
            assertEquals(requiredText(
                            expectedCompanion, "companionIdentity"),
                    companion.companionIdentity(),
                    "platformCommitCompanion.companionIdentity");
        }
    }

    private static void assertCompanionInputDocuments(
            JsonNode expected,
            List<ClosureCommitCompanion.InputDocument> actual) {
        assertSize("platformCommitCompanion.expectedInputDocuments",
                expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ClosureCommitCompanion.InputDocument value = actual.get(index);
            String path = "platformCommitCompanion.expectedInputDocuments["
                    + index + "]";
            assertDocumentId(item, "documentId", value.documentId(), path);
            assertEquals(requiredText(item, "blueId"), value.blueId(),
                    path + ".blueId");
        }
    }

    private static void assertCompanionInputComponents(
            JsonNode expected,
            List<ClosureCommitCompanion.InputComponent> actual) {
        assertSize("platformCommitCompanion.expectedInputComponents",
                expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ClosureCommitCompanion.InputComponent value = actual.get(index);
            String path = "platformCommitCompanion.expectedInputComponents["
                    + index + "]";
            assertEquals(requiredText(item, "componentIdentity"),
                    value.componentIdentity(), path + ".componentIdentity");
            assertEquals(requiredText(item, "componentStateIdentity"),
                    value.componentStateIdentity(),
                    path + ".componentStateIdentity");
            assertEquals(requiredLong(item, "componentGeneration"),
                    value.componentGeneration(),
                    path + ".componentGeneration");
            assertEquals(nullableText(item, "masterBlueId"),
                    value.masterBlueId(), path + ".masterBlueId");
        }
    }

    private static void assertCompanionDocumentDeltas(
            JsonNode expected,
            List<ClosureCommitCompanion.DocumentDelta> actual) {
        assertSize("platformCommitCompanion.resultingDocuments",
                expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ClosureCommitCompanion.DocumentDelta value = actual.get(index);
            String path = "platformCommitCompanion.resultingDocuments["
                    + index + "]";
            assertDocumentId(item, "documentId", value.documentId(), path);
            assertEquals(requiredText(item, "beforeBlueId"),
                    value.beforeBlueId(), path + ".beforeBlueId");
            assertEquals(requiredText(item, "afterBlueId"),
                    value.afterBlueId(), path + ".afterBlueId");
        }
    }

    private static void assertCompanionResultComponents(
            JsonNode expected,
            List<ClosureCommitCompanion.ResultComponent> actual) {
        assertSize("platformCommitCompanion.resultingComponents",
                expected, actual);
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ClosureCommitCompanion.ResultComponent value = actual.get(index);
            String path = "platformCommitCompanion.resultingComponents["
                    + index + "]";
            assertEquals(requiredText(item, "componentIdentity"),
                    value.componentIdentity(), path + ".componentIdentity");
            assertEquals(requiredText(item, "componentStateIdentity"),
                    value.componentStateIdentity(),
                    path + ".componentStateIdentity");
            assertEquals(nullableText(item, "cyclicProofIdentity"),
                    value.cyclicProofIdentity(),
                    path + ".cyclicProofIdentity");
        }
    }

    private static void assertCompanionText(
            JsonNode expected,
            String field,
            String actual) {
        assertEquals(requiredText(expected, field), actual,
                "platformCommitCompanion." + field);
    }

    private static void assertNode(
            JsonNode expected,
            Node actual,
            String path) {
        assertEquals(expected, wire(actual), path);
    }

    private static JsonNode wire(Node actual) {
        return UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(actual, NodeWireForm.Strategy.SIMPLE));
    }

    private static String documentIdValue(DocumentId value) {
        return value == null ? null : value.value();
    }

    private static void assertDocumentId(
            JsonNode object,
            String field,
            DocumentId actual,
            String path) {
        assertNotNull(actual, path + "." + field);
        assertEquals(requiredText(object, field), actual.value(),
                path + "." + field);
    }

    private static void assertNullableDocumentId(
            JsonNode object,
            String field,
            DocumentId actual,
            String path) {
        assertEquals(nullableText(object, field),
                actual == null ? null : actual.value(), path + "." + field);
    }

    private static <T> void assertSize(
            String path,
            JsonNode expected,
            List<T> actual) {
        assertTrue(expected.isArray(), path + " expected array");
        assertEquals(expected.size(), actual.size(), path + " size");
    }

    private static JsonNode required(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw new AssertionError("Missing required fixture field: " + field);
        }
        return value;
    }

    private static JsonNode requiredObject(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isObject()) {
            throw new AssertionError("Fixture field is not an object: " + field);
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isArray()) {
            throw new AssertionError("Fixture field is not an array: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isTextual()) {
            throw new AssertionError("Fixture field is not text: " + field);
        }
        return value.textValue();
    }

    private static String nullableText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AssertionError("Fixture field is not text: " + field);
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isIntegralNumber()) {
            throw new AssertionError(
                    "Fixture field is not an integer: " + field);
        }
        return value.longValue();
    }

    private static Long nullableLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new AssertionError(
                    "Fixture field is not an integer: " + field);
        }
        return Long.valueOf(value.longValue());
    }

    private static boolean requiredBoolean(JsonNode object, String field) {
        JsonNode value = required(object, field);
        if (!value.isBoolean()) {
            throw new AssertionError(
                    "Fixture field is not boolean: " + field);
        }
        return value.booleanValue();
    }

    private static List<String> textValues(JsonNode array) {
        ArrayList<String> values = new ArrayList<String>();
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                throw new AssertionError("Fixture array item is not text");
            }
            values.add(item.textValue());
        }
        return values;
    }

    private static List<String> documentIdValues(List<DocumentId> values) {
        ArrayList<String> result = new ArrayList<String>();
        for (DocumentId value : values) {
            result.add(value.value());
        }
        return result;
    }

    private static Map<String, String> textMap(JsonNode object) {
        java.util.LinkedHashMap<String, String> result =
                new java.util.LinkedHashMap<String, String>();
        java.util.Iterator<Map.Entry<String, JsonNode>> fields =
                object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw new AssertionError(
                        "Fixture map value is not text: " + field.getKey());
            }
            result.put(field.getKey(), field.getValue().textValue());
        }
        return result;
    }

    private static Map<String, String> documentTextMap(
            Map<DocumentId, String> values) {
        java.util.LinkedHashMap<String, String> result =
                new java.util.LinkedHashMap<String, String>();
        for (Map.Entry<DocumentId, String> entry : values.entrySet()) {
            result.put(entry.getKey().value(), entry.getValue());
        }
        return result;
    }

    private static final class Capture
            implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) {
            evidence = value;
        }
    }

    private static final class StateDeltas {
        private final List<String> values = new ArrayList<String>();

        private void equal(String path, Object expected, Object actual) {
            if (!java.util.Objects.equals(expected, actual)) {
                values.add(path + ": expected <" + expected
                        + "> but was <" + actual + ">");
            }
        }
    }
}
