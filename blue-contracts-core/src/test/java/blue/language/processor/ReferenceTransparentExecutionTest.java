package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused conformance for demand-driven ordinary pure-reference access. */
final class ReferenceTransparentExecutionTest {

    private static final Node REFERENCE_READER_TYPE =
            new Node().name("Reference-transparent reader Handler");
    private static final String REFERENCE_READER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    REFERENCE_READER_TYPE);
    private static final Node REFERENCE_PAIR_READER_TYPE =
            new Node().name("Reference-transparent pair reader Handler");
    private static final String REFERENCE_PAIR_READER_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    REFERENCE_PAIR_READER_TYPE);
    private static final Node REFERENCE_CHANNEL_TYPE =
            new Node().name("Reference-transparent external Channel");
    private static final String REFERENCE_CHANNEL_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    REFERENCE_CHANNEL_TYPE);

    @Test
    void descendantReadOpensOnlyTheRequiredExactAncestor() {
        Node counter = counter(7);
        Node unused = new Node().properties(
                "payload", new Node().value("must stay cold"));
        CountingProvider provider = new CountingProvider()
                .found(counter)
                .found(unused);
        String counterBlueId = blueId(counter);
        String unusedBlueId = blueId(unused);
        Node root = rootReference(counterBlueId, unusedBlueId);

        try (Fixture fixture = new Fixture(provider, root)) {
            assertEquals(BigInteger.valueOf(7),
                    fixture.runtime.resolvedFrozenAt(
                            "/counterValue/value").getValue());
            assertEquals(1, provider.reads(counterBlueId));
            assertEquals(0, provider.reads(unusedBlueId));

            assertEquals(BigInteger.valueOf(7),
                    fixture.runtime.resolvedNodeAt(
                            "/counterValue/value").getValue());

            // The exact immutable ancestor is reused within the invocation.
            assertEquals(BigInteger.valueOf(7),
                    fixture.runtime.resolvedFrozenAt(
                            "/counterValue/value").getValue());
            assertEquals(1, provider.reads(counterBlueId));
            assertEquals(0, provider.reads(unusedBlueId));
        }
    }

    @Test
    void unusedOrdinaryReferenceRemainsCollapsedAndNeverLoads() {
        Node counter = counter(7);
        CountingProvider provider = new CountingProvider().found(counter);
        String counterBlueId = blueId(counter);
        Node root = rootReference(counterBlueId, null);

        try (Fixture fixture = new Fixture(provider, root)) {
            assertEquals("root", fixture.runtime.resolvedFrozenAt(
                    "/name").getValue());
            assertEquals(0, provider.reads(counterBlueId));
            assertTrue(fixture.runtime.canonicalFrozenAt(
                    "/counterValue").isReferenceOnly());
        }
    }

    @Test
    void unavailableReadAndPatchRemainTypedRetryableAndAtomic() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider()
                .unavailable(counterBlueId, "exact store offline");
        Node root = rootReference(counterBlueId, null);
        Object before = NodeWireForm.get(root);

        try (Fixture fixture = new Fixture(provider, root)) {
            ExecutionEvidenceUnavailableException read = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> fixture.runtime.resolvedFrozenAt(
                            "/counterValue/value"));
            assertEquals(Collections.singletonList(counterBlueId),
                    read.requiredExactBlueIds());

            ExecutionEvidenceUnavailableException patch = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> fixture.runtime.applyPatch(
                            "/",
                            JsonPatch.replace(
                                    "/counterValue/value",
                                    new Node().value(
                                            BigInteger.valueOf(9)))));
            assertEquals(Collections.singletonList(counterBlueId),
                    patch.requiredExactBlueIds());
            assertEquals(before,
                    NodeWireForm.get(
                            fixture.runtime.canonicalRootWithoutResolution()
                                    .toNode()));
            assertEquals(0L, fixture.runtime.totalGas());
        }
    }

    @Test
    void invalidExactEvidenceFailsAtomicallyAndIsNotRetryable() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider()
                .invalid(counterBlueId, "proof mismatch");
        Node root = rootReference(counterBlueId, null);
        Object before = NodeWireForm.get(root);

        try (Fixture fixture = new Fixture(provider, root)) {
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> fixture.runtime.resolvedFrozenAt(
                            "/counterValue/value"));
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> fixture.runtime.applyPatch(
                            "/",
                            JsonPatch.replace(
                                    "/counterValue/value",
                                    new Node().value(
                                            BigInteger.valueOf(9)))));
            assertEquals(before,
                    NodeWireForm.get(
                            fixture.runtime.canonicalRootWithoutResolution()
                                    .toNode()));
            assertEquals(0L, fixture.runtime.totalGas());
        }
    }

    @Test
    void definitiveMissingContentRemainsTerminalAndAtomic() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider();
        Node root = rootReference(counterBlueId, null);
        Object before = NodeWireForm.get(root);

        try (Fixture fixture = new Fixture(provider, root)) {
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> fixture.runtime.resolvedFrozenAt(
                            "/counterValue/value"));
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> fixture.runtime.applyPatch(
                            "/", JsonPatch.replace(
                                    "/counterValue/value",
                                    new Node().value(
                                            BigInteger.valueOf(9)))));
            assertEquals(before,
                    NodeWireForm.get(
                            fixture.runtime.canonicalRootWithoutResolution()
                                    .toNode()));
            assertEquals(0L, fixture.runtime.totalGas());
        }
    }

    @Test
    void patchThroughReferenceMatchesInlineResultAndGas() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider().found(counter);
        Node referenceRoot = rootReference(counterBlueId, null);
        Node inlineRoot = rootInline(counter);

        try (Fixture referenced = new Fixture(provider, referenceRoot);
             Fixture inline = new Fixture(provider, inlineRoot)) {
            JsonPatch patch = JsonPatch.replace(
                    "/counterValue/value",
                    new Node().value(BigInteger.valueOf(9)));
            referenced.runtime.applyPatch("/", patch);
            inline.runtime.applyPatch("/", patch);

            assertEquals(
                    NodeWireForm.get(
                            inline.runtime.canonicalRootWithoutResolution()
                                    .toNode()),
                    NodeWireForm.get(
                            referenced.runtime
                                    .canonicalRootWithoutResolution()
                                    .toNode()));
            assertEquals(inline.runtime.totalGas(),
                    referenced.runtime.totalGas());
            assertEquals(
                    inline.runtime.snapshot().isResolutionComplete(),
                    referenced.runtime.snapshot().isResolutionComplete());
            assertEquals(
                    inline.runtime.canonicalRootWithoutResolution().blueId(),
                    referenced.runtime
                            .canonicalRootWithoutResolution().blueId());
            assertNotEquals(
                    DirectBlueIdCalculator.calculateBlueId(referenceRoot),
                    referenced.runtime
                            .canonicalRootWithoutResolution().blueId());
            assertEquals(BigInteger.valueOf(9),
                    referenced.runtime.resolvedFrozenAt(
                            "/counterValue/value").getValue());
        }
    }

    @Test
    void patchAncestorMaterializationRetainsUnrelatedWideFrozenSibling() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider().found(counter);
        Node root = rootReference(counterBlueId, null)
                .properties("wide", wideSibling());

        try (Fixture fixture = new Fixture(provider, root)) {
            FrozenNode canonical =
                    fixture.runtime.canonicalRootWithoutResolution();
            FrozenNode beforeWide = canonical.property("wide");
            FrozenNode expanded = new ReferenceTransparentPathAccess(
                    fixture.manager,
                    false,
                    Collections.<String, List<String>>emptyMap())
                    .materializePatchAncestors(
                            canonical,
                            Collections.singletonList(
                                    "/counterValue/value"));
            FrozenNode afterWide = expanded.property("wide");

            assertSame(beforeWide, afterWide,
                    "persistent ancestor expansion must structurally share "
                            + "an untouched wide sibling");
            assertTrue(!expanded.property("counterValue")
                    .isReferenceOnly());
            assertEquals(1, provider.reads(counterBlueId));
        }
    }

    @Test
    void workingDocumentReadAndPatchUseTheSameTransparentBoundary() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider().found(counter);

        try (Fixture fixture = new Fixture(
                provider, rootReference(counterBlueId, null));
             WorkingDocument working =
                     fixture.runtime.workingDocument("/")) {
            assertEquals(BigInteger.valueOf(7),
                    working.resolvedAt(
                            "/counterValue/value").getValue());
            working.applyPatch(JsonPatch.replace(
                    "/counterValue/value",
                    new Node().value(BigInteger.valueOf(11))));
            assertEquals(BigInteger.valueOf(11),
                    working.resolvedAt(
                            "/counterValue/value").getValue());
            assertEquals(BigInteger.valueOf(11),
                    working.canonicalAt(
                            "/counterValue").getValue());
        }
    }

    @Test
    void wholeReferenceReplacementAndRemovalDoNotDemandItsContent() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider()
                .unavailable(counterBlueId, "must stay cold");

        try (Fixture replaced = new Fixture(
                provider, rootReference(counterBlueId, null))) {
            replaced.runtime.applyPatch("/", JsonPatch.replace(
                    "/counterValue", counter(9)));
            assertEquals(BigInteger.valueOf(9),
                    replaced.runtime.resolvedFrozenAt(
                            "/counterValue/value").getValue());
            assertEquals(0, provider.reads(counterBlueId));
        }

        try (Fixture removed = new Fixture(
                provider, rootReference(counterBlueId, null))) {
            removed.runtime.applyPatch("/", JsonPatch.remove(
                    "/counterValue"));
            assertNull(removed.runtime.resolvedFrozenAt(
                    "/counterValue"));
            assertEquals(0, provider.reads(counterBlueId));
        }

        try (Fixture replacedThenPatched = new Fixture(
                provider, rootReference(counterBlueId, null))) {
            replacedThenPatched.runtime.applyPatches("/", java.util.Arrays.asList(
                    JsonPatch.replace("/counterValue", counter(9)),
                    JsonPatch.replace(
                            "/counterValue/value",
                            new Node().value(BigInteger.TEN))));
            assertEquals(BigInteger.TEN,
                    replacedThenPatched.runtime.resolvedFrozenAt(
                            "/counterValue/value").getValue());
            assertEquals(0, provider.reads(counterBlueId));
        }
    }

    @Test
    void invalidEarlierPatchDoesNotDemandLaterReference() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        CountingProvider provider = new CountingProvider()
                .unavailable(counterBlueId, "must stay cold");
        Node root = rootReference(counterBlueId, null);

        try (Fixture fixture = new Fixture(provider, root)) {
            Object before = NodeWireForm.get(
                    fixture.runtime.canonicalRootWithoutResolution()
                            .toNode());
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> fixture.runtime.applyPatches(
                            "/",
                            Arrays.asList(
                                    JsonPatch.replace(
                                            "/missing/value",
                                            counter(1)),
                                    JsonPatch.replace(
                                            "/counterValue/value",
                                            counter(9)))));
            assertFalse(failure instanceof
                    ExecutionEvidenceUnavailableException);
            assertEquals(0, provider.reads(counterBlueId));
            assertEquals(before, NodeWireForm.get(
                    fixture.runtime.canonicalRootWithoutResolution()
                            .toNode()));
            assertEquals(0L, fixture.runtime.totalGas());
        }
    }

    @Test
    void processAttemptTranslatesDemandedReferenceToTypedSuspension() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        Node event = new Node().properties(
                "subscriptionKey",
                new Node().value("reference-transparent"));
        Node root = executableRoot(counterBlueId);
        CountingProvider provider = new CountingProvider()
                .unavailable(counterBlueId, "counter store offline");

        try (ProcessingFixture fixture = processor(provider)) {
            DocumentProcessingResult initialized =
                    fixture.processor.initializeDocument(
                            fixture.snapshot(root));
            assertEquals(ProcessorStatus.SUCCESS,
                    initialized.status(),
                    initialized.diagnostic() != null
                            ? initialized.diagnostic().message()
                            : null);
            assertEquals(0, provider.reads(counterBlueId));
            ProcessAttemptResult suspended = fixture.processor.processAttempt(
                    initialized.document().clone(), event.clone());
            assertEquals(ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                    suspended.kind(), attemptSummary(suspended));
            assertTrue(suspended.requiredExactBlueIds()
                    .contains(counterBlueId));
            assertNull(suspended.processResult());
            assertNull(suspended.portableGas());
        }
    }

    @Test
    void handlerEvidenceMissSuspendsAtomicallyInsteadOfFailingRuntime() {
        Node counter = counter(7);
        String counterBlueId = blueId(counter);
        Node root = lifecycleExecutableRoot(
                new Node().blueId(counterBlueId));
        CountingProvider provider = new CountingProvider()
                .unavailable(counterBlueId, "counter store offline");
        Object before = NodeWireForm.get(root);

        try (ProcessingFixture fixture = processor(provider)) {
            ExecutionEvidenceUnavailableException suspended = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> fixture.processor.initializeDocument(root));
            assertEquals(Collections.singletonList(counterBlueId),
                    suspended.requiredExactBlueIds());
            assertEquals(before, NodeWireForm.get(root));
            assertEquals(1, provider.reads(counterBlueId));
        }
    }

    @Test
    void handlerPatchThroughReferenceMatchesInlineResultAndGas() {
        assertHandlerPatchParity(false);
    }

    @Test
    void snapshotNativeHandlerPatchThroughReferenceMatchesInlineResultAndGas() {
        assertHandlerPatchParity(true);
    }

    @Test
    void bufferedHandlerSequenceMaterializesTwoDistinctReferences() {
        Node first = counter(7);
        Node second = counter(8);
        CountingProvider referencedProvider = new CountingProvider()
                .found(first)
                .found(second);
        CountingProvider inlineProvider = new CountingProvider();

        try (ProcessingFixture referenced =
                     processor(referencedProvider);
             ProcessingFixture inline = processor(inlineProvider)) {
            DocumentProcessingResult referencedResult =
                    referenced.processor.initializeDocument(
                            lifecyclePairExecutableRoot(
                                    new Node().blueId(blueId(first)),
                                    new Node().blueId(blueId(second))));
            DocumentProcessingResult inlineResult =
                    inline.processor.initializeDocument(
                            lifecyclePairExecutableRoot(
                                    first.clone(), second.clone()));

            assertEquals(ProcessorStatus.SUCCESS,
                    referencedResult.status(),
                    referencedResult.diagnostic() != null
                            ? referencedResult.diagnostic().message()
                            : null);
            assertEquals(ProcessorStatus.SUCCESS,
                    inlineResult.status(),
                    inlineResult.diagnostic() != null
                            ? inlineResult.diagnostic().message()
                            : null);
            assertEquals(
                    NodeWireForm.get(inlineResult.document()),
                    NodeWireForm.get(referencedResult.document()));
            assertEquals(inlineResult.totalGas(),
                    referencedResult.totalGas());
            assertEquals(BigInteger.valueOf(9),
                    referencedResult.document()
                            .getNode("/counterValue/value")
                            .getValue());
            assertEquals(BigInteger.valueOf(11),
                    referencedResult.document()
                            .getNode("/otherValue/value")
                            .getValue());
            assertEquals(1,
                    referencedProvider.reads(blueId(first)));
            assertEquals(1,
                    referencedProvider.reads(blueId(second)));
        }
    }

    private void assertHandlerPatchParity(boolean snapshotNative) {
        Node counter = counter(7);
        CountingProvider referencedProvider =
                new CountingProvider().found(counter);
        CountingProvider inlineProvider = new CountingProvider();

        try (ProcessingFixture referenced =
                     processor(referencedProvider);
             ProcessingFixture inline = processor(inlineProvider)) {
            Node referencedRoot = lifecycleExecutableRoot(
                    new Node().blueId(blueId(counter)));
            Node inlineRoot = lifecycleExecutableRoot(counter.clone());
            DocumentProcessingResult referencedResult = snapshotNative
                    ? referenced.processor.initializeDocument(
                            referenced.snapshot(referencedRoot))
                    : referenced.processor.initializeDocument(
                            referencedRoot);
            DocumentProcessingResult inlineResult = snapshotNative
                    ? inline.processor.initializeDocument(
                            inline.snapshot(inlineRoot))
                    : inline.processor.initializeDocument(inlineRoot);

            assertEquals(ProcessorStatus.SUCCESS,
                    referencedResult.status(),
                    referencedResult.diagnostic() != null
                            ? referencedResult.diagnostic().message()
                            : null);
            assertEquals(ProcessorStatus.SUCCESS,
                    inlineResult.status(),
                    inlineResult.diagnostic() != null
                            ? inlineResult.diagnostic().message()
                            : null);
            assertEquals(
                    NodeWireForm.get(inlineResult.document()),
                    NodeWireForm.get(referencedResult.document()));
            assertEquals(inlineResult.totalGas(),
                    referencedResult.totalGas());
            assertEquals(BigInteger.valueOf(9),
                    referencedResult.document()
                            .getNode("/counterValue/value")
                            .getValue());
            assertTrue(referencedProvider.reads(
                    blueId(counter)) > 0);
        }
    }

    @Test
    void demandedAncestorDoesNotLoadItsUnrelatedNestedReference() {
        Node nested = new Node().properties(
                "payload", new Node().value("cold"));
        String nestedBlueId = blueId(nested);
        Node ancestor = new Node().properties(
                "count", new Node().value(BigInteger.valueOf(7)),
                "nested", new Node().blueId(nestedBlueId));
        String ancestorBlueId = blueId(ancestor);
        CountingProvider provider = new CountingProvider()
                .found(ancestor)
                .unavailable(nestedBlueId, "must stay cold");

        try (Fixture fixture = new Fixture(
                provider, rootReference(ancestorBlueId, null))) {
            assertEquals(BigInteger.valueOf(7),
                    fixture.runtime.resolvedFrozenAt(
                            "/counterValue/count").getValue());
            fixture.runtime.applyPatch(
                    "/",
                    JsonPatch.replace(
                            "/counterValue/count",
                            new Node().value(BigInteger.valueOf(9))));
            assertEquals(BigInteger.valueOf(9),
                    fixture.runtime.resolvedFrozenAt(
                            "/counterValue/count").getValue());
            assertEquals(1, provider.reads(ancestorBlueId));
            assertEquals(0, provider.reads(nestedBlueId));
        }
    }

    @Test
    void referencedListItemPatchMatchesInlineResultAndGas() {
        Node list = new Node().items(
                new Node().value("first"),
                new Node().value("second"));
        String listBlueId = blueId(list);
        CountingProvider provider = new CountingProvider().found(list);

        try (Fixture referenced = new Fixture(
                provider, rootReference(listBlueId, null));
             Fixture inline = new Fixture(
                     provider, rootInline(list))) {
            JsonPatch patch = JsonPatch.replace(
                    "/counterValue/0",
                    new Node().value("changed"));
            referenced.runtime.applyPatch("/", patch);
            inline.runtime.applyPatch("/", patch);

            assertEquals(
                    NodeWireForm.get(
                            inline.runtime.canonicalRootWithoutResolution()
                                    .toNode()),
                    NodeWireForm.get(
                            referenced.runtime
                                    .canonicalRootWithoutResolution()
                                    .toNode()));
            assertEquals(inline.runtime.totalGas(),
                    referenced.runtime.totalGas());
            assertEquals("changed",
                    referenced.runtime.resolvedFrozenAt(
                            "/counterValue/0").getValue());
        }
    }

    @Test
    void listIntrinsicTypePrecedesListItemTraversal() {
        Node declaredType = new Node().name("list-type");
        String typeBlueId = blueId(declaredType);
        Node list = new Node()
                .items(new Node().value("item"))
                .type(new Node().blueId(typeBlueId));
        String listBlueId = blueId(list);
        CountingProvider provider = new CountingProvider()
                .found(list)
                .found(declaredType);

        try (Fixture fixture = new Fixture(
                provider, rootReference(listBlueId, null))) {
            assertEquals("list-type",
                    fixture.runtime.resolvedFrozenAt(
                            "/counterValue/type/name").getValue());
            assertEquals("item",
                    fixture.runtime.resolvedFrozenAt(
                            "/counterValue/0").getValue());
            assertEquals(1, provider.reads(listBlueId));
            assertEquals(1, provider.reads(typeBlueId));
        }
    }

    @Test
    void directContractsReferenceRemainsAdmissionBlocking() {
        Node exactContracts = new Node().properties(
                "opaque", new Node().value("contract"));
        String contractsBlueId = blueId(exactContracts);
        CountingProvider provider = new CountingProvider()
                .unavailable(contractsBlueId,
                        "contracts store offline");
        Node root = new Node().name("direct contracts reference")
                .contracts(new Node().blueId(contractsBlueId));

        try (DocumentProcessor processor = DocumentProcessor.builder()
                .nodeProvider(provider)
                .build()) {
            ProcessAttemptResult attempt = processor.processAttempt(
                    root, new Node().value("event"));
            assertEquals(ProcessAttemptResult.Kind.NEEDS_RESOURCES,
                    attempt.kind());
            assertEquals(Collections.singletonList(contractsBlueId),
                    attempt.requiredExactBlueIds());
            assertNull(attempt.processResult());
        }
    }

    @Test
    void cyclicMemberReadUsesProofButMutationGuardRunsBeforeLoading() {
        CyclicFixture cyclic = new CyclicFixture();
        CountingCyclicProvider readable =
                new CountingCyclicProvider(cyclic.provider);
        Node root = rootReference(cyclic.memberBlueId, null);
        try (Fixture fixture = new Fixture(readable, root)) {
            assertEquals("member-a",
                    fixture.runtime.resolvedFrozenAt(
                            "/counterValue/label").getValue());
            assertEquals(1, readable.fetches.get());
            assertEquals(1, readable.proofQueries.get());
        }

        CountingCyclicProvider guarded =
                new CountingCyclicProvider(cyclic.provider);
        try (Fixture fixture = new Fixture(guarded, root)) {
            ProcessorFailureException failure = assertThrows(
                    ProcessorFailureException.class,
                    () -> fixture.runtime.applyPatch(
                            "/", JsonPatch.replace(
                                    "/counterValue/label",
                                    new Node().value("changed"))));
            assertEquals(
                    ProcessorErrorCategory.CyclicSetMutationUnsupported,
                    failure.errorCategory());
            assertEquals(0, guarded.fetches.get());
            assertEquals(0, guarded.proofQueries.get());
        }
    }

    private static Node rootReference(
            String counterBlueId,
            String unusedBlueId) {
        Node root = new Node()
                .properties(
                        "name", new Node().value("root"),
                        "counterValue", new Node().blueId(counterBlueId));
        if (unusedBlueId != null) {
            root.properties("unused",
                    new Node().blueId(unusedBlueId));
        }
        return root;
    }

    private static Node counter(long value) {
        return new Node().value(BigInteger.valueOf(value));
    }

    private static Node wideSibling() {
        Node wide = new Node();
        for (int index = 0; index < 128; index++) {
            wide.properties("k" + index,
                    new Node().value("v" + index));
        }
        return wide;
    }

    private static Node rootInline(Node counter) {
        return new Node().properties(
                "name", new Node().value("root"),
                "counterValue", counter.clone());
    }

    private static Node executableRoot(String counterBlueId) {
        return rootReference(counterBlueId, null)
                .properties("observed", counter(0))
                .contracts(new Node()
                        .properties(
                                "input",
                                new Node().type(new Node().blueId(
                                        REFERENCE_CHANNEL_BLUE_ID)))
                        .properties(
                                "readCounter",
                                new Node()
                                        .type(new Node().blueId(
                                                REFERENCE_READER_BLUE_ID))
                                        .properties(
                                                "channel",
                                                new Node().value(
                                                        "input"))));
    }

    private static Node lifecycleExecutableRoot(Node counterValue) {
        return new Node()
                .properties("name", new Node().value("root"))
                .properties("counterValue", counterValue)
                .contracts(new Node()
                        .properties(
                                "lifecycle",
                                new Node().type(new Node().blueId(
                                        RuntimeBlueIds
                                                .LIFECYCLE_EVENT_CHANNEL)))
                        .properties(
                                "patchCounter",
                                new Node()
                                        .type(new Node().blueId(
                                                REFERENCE_READER_BLUE_ID))
                                        .properties(
                                                "channel",
                                                new Node().value(
                                                        "lifecycle"))));
    }

    private static Node lifecyclePairExecutableRoot(
            Node counterValue,
            Node otherValue) {
        return new Node()
                .properties("name", new Node().value("pair root"))
                .properties("counterValue", counterValue)
                .properties("otherValue", otherValue)
                .contracts(new Node()
                        .properties(
                                "lifecycle",
                                new Node().type(new Node().blueId(
                                        RuntimeBlueIds
                                                .LIFECYCLE_EVENT_CHANNEL)))
                        .properties(
                                "patchPair",
                                new Node()
                                        .type(new Node().blueId(
                                                REFERENCE_PAIR_READER_BLUE_ID))
                                        .properties(
                                                "channel",
                                                new Node().value(
                                                        "lifecycle"))));
    }

    private static ProcessingFixture processor(NodeProvider provider) {
        return new ProcessingFixture(provider);
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static String attemptSummary(ProcessAttemptResult attempt) {
        if (attempt == null || attempt.processResult() == null) {
            return String.valueOf(attempt);
        }
        DocumentProcessingResult result = attempt.processResult();
        return result.status() + ": "
                + (result.diagnostic() != null
                ? result.diagnostic().category() + " "
                + result.diagnostic().message()
                : "no diagnostic");
    }

    public static final class ReferenceReader
            extends HandlerContract {
    }

    public static final class ReferencePairReader
            extends HandlerContract {
    }

    public static final class ReferenceChannel
            extends ChannelContract {
    }

    private static final class ReferenceReaderProcessor
            implements HandlerProcessor<ReferenceReader> {
        @Override
        public Class<ReferenceReader> contractType() {
            return ReferenceReader.class;
        }

        @Override
        public void execute(
                ReferenceReader contract,
                ProcessorExecutionContext context) {
            context.applyPatch(JsonPatch.replace(
                    "/counterValue/value",
                    new Node().value(BigInteger.valueOf(9))));
            context.documentAt("/counterValue/value");
        }
    }

    private static final class ReferencePairReaderProcessor
            implements HandlerProcessor<ReferencePairReader> {
        @Override
        public Class<ReferencePairReader> contractType() {
            return ReferencePairReader.class;
        }

        @Override
        public void execute(
                ReferencePairReader contract,
                ProcessorExecutionContext context) {
            context.applyPatch(JsonPatch.replace(
                    "/counterValue/value",
                    new Node().value(BigInteger.valueOf(9))));
            context.applyPatch(JsonPatch.replace(
                    "/otherValue/value",
                    new Node().value(BigInteger.valueOf(11))));
        }
    }

    private static final class ReferenceChannelProcessor
            implements ChannelProcessor<ReferenceChannel> {
        private final ExternalChannelSubscriptionFunctions<ReferenceChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<ReferenceChannel>() {
                    @Override
                    public List<String> channelKeys(
                            ReferenceChannel contract) {
                        return Collections.singletonList(
                                "reference-transparent");
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            ReferenceChannel contract) {
                        return null;
                    }
                };

        @Override
        public Class<ReferenceChannel> contractType() {
            return ReferenceChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<ReferenceChannel>
        externalSubscriptionFunctions() {
            return functions;
        }

        @Override
        public boolean matches(
                ReferenceChannel contract,
                ChannelEvaluationContext context) {
            return true;
        }
    }

    private static final class ProcessingFixture
            implements AutoCloseable {
        private final BlueLanguage language;
        private final LanguageProcessing.Scope scope;
        private final LanguageProcessingSnapshotManager manager;
        private final DocumentProcessor processor;

        private ProcessingFixture(NodeProvider businessProvider) {
            NodeProvider provider = new SequentialNodeProvider(
                    Objects.requireNonNull(
                            businessProvider, "businessProvider"),
                    new BasicNodeProvider(Arrays.asList(
                            REFERENCE_READER_TYPE,
                            REFERENCE_PAIR_READER_TYPE,
                            REFERENCE_CHANNEL_TYPE)),
                    BlueRuntimeTypeRegistry.getDefault().asProvider());
            language = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .build();
            scope = language.processing().openScope();
            manager = new LanguageProcessingSnapshotManager(scope);
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder.create()
                            .register(
                                    REFERENCE_READER_BLUE_ID,
                                    REFERENCE_READER_TYPE,
                                    new ReferenceReaderProcessor())
                            .register(
                                    REFERENCE_PAIR_READER_BLUE_ID,
                                    REFERENCE_PAIR_READER_TYPE,
                                    new ReferencePairReaderProcessor())
                            .register(
                                    REFERENCE_CHANNEL_BLUE_ID,
                                    REFERENCE_CHANNEL_TYPE,
                                    new ReferenceChannelProcessor())
                            .build();
            processor = DocumentProcessor.builder()
                    .runtimeRegistry(registry)
                    .nodeProvider(provider)
                    .snapshotStore(manager)
                    .build();
        }

        private ResolvedSnapshot snapshot(Node root) {
            return manager.fromDocumentTransientPreservingPaths(
                    root,
                    Collections.singleton("/counterValue"));
        }

        @Override
        public void close() {
            processor.close();
            scope.close();
            language.close();
        }
    }

    private static final class CyclicFixture {
        private final BasicNodeProvider provider;
        private final String memberBlueId;

        private CyclicFixture() {
            Node cyclicSet = new Node().items(
                    new Node().name("Reference Cyclic A")
                            .properties("label",
                                    new Node().value("member-a"))
                            .properties("next",
                                    new Node().blueId("this#1")),
                    new Node().name("Reference Cyclic B")
                            .properties("label",
                                    new Node().value("member-b"))
                            .properties("next",
                                    new Node().blueId("this#0")));
            provider = new BasicNodeProvider(
                    Collections.singletonList(cyclicSet));
            memberBlueId = provider.getBlueIdByName(
                    "Reference Cyclic A");
        }
    }

    private static final class CountingCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final BasicNodeProvider delegate;
        private final AtomicInteger fetches = new AtomicInteger();
        private final AtomicInteger proofQueries = new AtomicInteger();

        private CountingCyclicProvider(
                BasicNodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches.incrementAndGet();
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(
                String blueId) {
            proofQueries.incrementAndGet();
            return delegate.cyclicSetProofFor(blueId);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final BlueLanguage language;
        private final LanguageProcessing.Scope scope;
        private final LanguageProcessingSnapshotManager manager;
        private final DocumentProcessingRuntime runtime;

        private Fixture(NodeProvider provider, Node root) {
            language = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .build();
            scope = language.processing().openScope();
            manager = new LanguageProcessingSnapshotManager(scope);
            ResolvedSnapshot snapshot =
                    manager.fromDocumentTransient(root);
            runtime = new DocumentProcessingRuntime(
                    snapshot, null, manager);
        }

        @Override
        public void close() {
            scope.close();
            language.close();
        }
    }

    private static final class CountingProvider implements NodeProvider {
        private final Map<String, NodeProviderResult> values =
                new LinkedHashMap<String, NodeProviderResult>();
        private final Map<String, AtomicInteger> reads =
                new LinkedHashMap<String, AtomicInteger>();

        private CountingProvider found(Node value) {
            values.put(blueId(value), NodeProviderResult.found(
                    Collections.singletonList(value)));
            return this;
        }

        private CountingProvider unavailable(
                String blueId, String diagnostic) {
            values.put(blueId,
                    NodeProviderResult.unavailable(diagnostic));
            return this;
        }

        private CountingProvider invalid(
                String blueId, String diagnostic) {
            values.put(blueId,
                    NodeProviderResult.invalidEvidence(diagnostic));
            return this;
        }

        private int reads(String blueId) {
            AtomicInteger count = reads.get(blueId);
            return count != null ? count.get() : 0;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            NodeProviderResult result = fetchResultByBlueId(blueId);
            return result.outcome()
                    == blue.language.api.NodeProviderOutcome.FOUND
                    ? result.nodes()
                    : null;
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            AtomicInteger count = reads.get(blueId);
            if (count == null) {
                count = new AtomicInteger();
                reads.put(blueId, count);
            }
            count.incrementAndGet();
            NodeProviderResult result = values.get(blueId);
            if (result != null) {
                return result;
            }
            return NodeProviderResult.notFound();
        }
    }
}
