package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused integration coverage for stable-key embedded collection occurrence
 * continuity, mutation boundaries, scope-local bindings, and physical
 * locality.
 */
final class EmbeddedCollectionLifecycleIntegrationTest {

    private static final String ROOT_SCOPE = "/";
    private static final String COLLECTION_PATH = "/lessons";
    private static final String SELECTED_MEMBER_KEY = "lesson-a";
    private static final String SIBLING_MEMBER_KEY = "lesson-b";
    private static final String SELECTED_MEMBER_PATH =
            COLLECTION_PATH + "/" + SELECTED_MEMBER_KEY;
    private static final String SIBLING_MEMBER_PATH =
            COLLECTION_PATH + "/" + SIBLING_MEMBER_KEY;
    private static final String CHANNEL_KEY = "incoming";
    private static final String HANDLER_KEY = "child-handler";
    private static final String KEY_CHANNEL = "channel";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_PROPERTY_KEY = "propertyKey";

    private static final UpdateMaterializationMetrics NOOP_METRICS =
            new UpdateMaterializationMetrics() {
                @Override
                public void recordBeforeNodeMaterialization() {
                }

                @Override
                public void recordAfterNodeMaterialization() {
                }
            };

    @Test
    void shouldStartFreshTypedCheckpointLineageWhenCollectionMemberIsRemovedAndReadded() {
        // given
        Node oldSubject = checkpointSubject("old-event");
        String oldDomainBlueId = checkpointIdentity("old-domain");
        String oldSubjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(oldSubject);
        Node oldMember = memberWithCheckpoint(
                "old",
                oldDomainBlueId,
                oldSubject);
        Node replacement = member("new");
        Node root = rootWithMembers(oldMember, member("sibling"));
        DocumentProcessor processor = new DocumentProcessor();
        ContractBundle oldBundle = processor.contractLoader().load(
                FrozenNode.fromResolvedNode(oldMember),
                SELECTED_MEMBER_PATH,
                CanonicalTypeIdentityLookup.incomplete());
        CheckpointManager.CheckpointRecord oldCheckpoint =
                new CheckpointManager(
                        new DocumentProcessingRuntime(root.clone()))
                        .findCheckpoint(
                                oldBundle,
                                CHANNEL_KEY,
                                oldDomainBlueId);
        FrozenNode canonical = FrozenNode.fromNode(root);
        FrozenNode resolved = FrozenNode.fromResolvedNode(root);
        SequentialPatchPlanningSession session = planningSession(
                canonical, resolved);
        String oldOccurrenceBlueId =
                canonical.at(SELECTED_MEMBER_PATH).blueId();

        // when
        session.planNext(JsonPatch.remove(SELECTED_MEMBER_PATH));
        SequentialPatchPlanningSession.PlannedStep readded =
                session.planNext(JsonPatch.add(
                        SELECTED_MEMBER_PATH, replacement));
        FrozenNode freshOccurrence = readded.result()
                .resolvedRoot()
                .at(SELECTED_MEMBER_PATH);
        Node freshRoot = readded.result().resolvedRoot().toNode();
        ContractBundle freshBundle = processor.contractLoader().load(
                freshOccurrence,
                SELECTED_MEMBER_PATH,
                CanonicalTypeIdentityLookup.incomplete());
        DocumentProcessingRuntime freshRuntime =
                new DocumentProcessingRuntime(freshRoot);
        CheckpointManager freshManager =
                new CheckpointManager(freshRuntime);
        String newDomainBlueId = checkpointIdentity("new-domain");
        Node newSubject = checkpointSubject("new-event");
        String newSubjectBlueId =
                DirectBlueIdCalculator.calculateBlueId(newSubject);
        CheckpointManager.CheckpointRecord freshCheckpoint =
                freshManager.findCheckpoint(
                        freshBundle,
                        CHANNEL_KEY,
                        newDomainBlueId);
        boolean freshDomainMatchedBeforeWrite =
                freshCheckpoint.domainMatches;
        Node freshPreviousSubjectBeforeWrite =
                freshCheckpoint.lastEventNode;
        freshManager.persist(
                SELECTED_MEMBER_PATH,
                freshBundle,
                freshCheckpoint,
                newSubjectBlueId,
                newSubject);
        ChannelEventCheckpoint persisted =
                (ChannelEventCheckpoint) freshBundle.marker(
                        ProcessorContractConstants.KEY_CHECKPOINT);

        // then
        assertInstanceOf(
                ChannelEventCheckpoint.class,
                oldBundle.marker(
                        ProcessorContractConstants.KEY_CHECKPOINT));
        assertTrue(oldCheckpoint.domainMatches);
        assertEquals(oldSubjectBlueId,
                oldCheckpoint.lastEventSignature);
        assertNotNull(freshOccurrence);
        assertEquals("new",
                freshOccurrence.at("/" + KEY_GENERATION).getValue());
        assertNotEquals(oldOccurrenceBlueId, freshOccurrence.blueId());
        assertFalse(freshDomainMatchedBeforeWrite);
        assertNull(freshPreviousSubjectBeforeWrite);
        assertNotNull(persisted);
        assertEquals(newDomainBlueId,
                persisted.entry(CHANNEL_KEY).domainBlueId());
        assertEquals(newSubjectBlueId,
                persisted.entry(CHANNEL_KEY).subjectBlueId());
        assertNotEquals(oldDomainBlueId,
                persisted.entry(CHANNEL_KEY).domainBlueId());
        assertNotEquals(oldSubjectBlueId,
                persisted.entry(CHANNEL_KEY).subjectBlueId());
    }

    @Test
    void shouldCutOffOldOccurrenceWhenWholeCollectionMemberGetsDifferentIdentity() {
        // given
        Node before = member("old");
        Node after = member("replacement");
        ProcessorInvocationState execution = executionWithSelectedMember(
                before);
        ScopeCutoffTracker cutoffs = new ScopeCutoffTracker(execution);
        DocumentUpdateData replacement = replacementUpdate(before, after);

        // when
        cutoffs.recordEmbeddedReplacement(
                ROOT_SCOPE,
                collectionBundle(SELECTED_MEMBER_KEY),
                replacement);

        // then
        assertTrue(cutoffs.shouldStop(SELECTED_MEMBER_PATH));
        assertEquals(
                Collections.singleton(SELECTED_MEMBER_PATH),
                execution.runtime().replacedEmbeddedScopePaths());
    }

    @Test
    void shouldPreserveOccurrenceWhenWholeCollectionMemberKeepsSameBlueId() {
        // given
        Node before = member("unchanged");
        Node equivalent = before.clone();
        ProcessorInvocationState execution = executionWithSelectedMember(
                before);
        ScopeCutoffTracker cutoffs = new ScopeCutoffTracker(execution);
        DocumentUpdateData replacement = replacementUpdate(
                before, equivalent);
        String beforeBlueId =
                DirectBlueIdCalculator.calculateBlueId(before);
        String replacementBlueId =
                DirectBlueIdCalculator.calculateBlueId(equivalent);

        // when
        cutoffs.recordEmbeddedReplacement(
                ROOT_SCOPE,
                collectionBundle(SELECTED_MEMBER_KEY),
                replacement);

        // then
        assertEquals(beforeBlueId, replacementBlueId);
        assertFalse(cutoffs.shouldStop(SELECTED_MEMBER_PATH));
        assertTrue(execution.runtime()
                .replacedEmbeddedScopePaths()
                .isEmpty());
    }

    @Test
    void shouldRejectReplacingCollectionContainerWithFrozenActiveMembers() {
        // given
        ContractBundle frozenEntryBundle = collectionBundle(
                SELECTED_MEMBER_KEY, SIBLING_MEMBER_KEY);
        PatchInput replacement = PatchInput.mutable(JsonPatch.replace(
                COLLECTION_PATH,
                new Node().properties(
                        "replacement",
                        new Node().value(true))));

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> PatchBoundaryValidator.validate(
                        ROOT_SCOPE,
                        frozenEntryBundle,
                        replacement));

        // then
        ProcessorEngine.BoundaryViolationException failure =
                assertInstanceOf(
                        ProcessorEngine.BoundaryViolationException.class,
                        captured);
        assertTrue(failure.getMessage().contains(
                "is a strict ancestor of embedded scope "
                        + SELECTED_MEMBER_PATH));
    }

    @Test
    void shouldNotBindChildHandlerToIdenticallyNamedParentChannel() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                new SetPropertyContractProcessor());
        Node document = rootWithMembers(
                memberWithHandler(),
                member("sibling"));
        document.getContracts().properties(
                CHANNEL_KEY,
                new Node().type(new Node().blueId(
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL)));
        ProcessorInvocationState execution = new ProcessorInvocationState(
                blue.getDocumentProcessor(), document);
        execution.preflightScope(ROOT_SCOPE);
        execution.preflightScope(SELECTED_MEMBER_PATH);
        ContractBundle parentBundle =
                execution.bundleForScope(ROOT_SCOPE);
        ContractBundle childBundle =
                execution.bundleForScope(SELECTED_MEMBER_PATH);
        EffectiveContractSnapshot childHandler =
                childBundle.effectiveContractSnapshot(HANDLER_KEY);
        HandlerChannelSelector selector =
                new HandlerChannelSelector(execution);

        // when
        Throwable captured = FailureCapture.captureFailure(
                () -> selector.requireExecutableTarget(
                        SELECTED_MEMBER_PATH,
                        childBundle,
                        CHANNEL_KEY));
        DocumentProcessingResult result = execution.result();

        // then
        assertNotNull(new SameScopeChannelCatalog(parentBundle)
                .handlerTarget(CHANNEL_KEY));
        assertNull(new SameScopeChannelCatalog(childBundle)
                .handlerTarget(CHANNEL_KEY));
        assertNotNull(childHandler);
        assertEquals(
                EffectiveContractSnapshotConstants.Role.HANDLER,
                childHandler.role());
        assertEquals(
                CHANNEL_KEY,
                childHandler.dispatchFields().get(
                        EffectiveContractSnapshotConstants
                                .DispatchField.CHANNEL));
        assertTrue(childBundle.handlersFor(CHANNEL_KEY).isEmpty());
        assertInstanceOf(RunTerminationException.class, captured);
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
        assertTrue(result.diagnostic().message().contains(
                "same-scope Channel at "
                        + SELECTED_MEMBER_PATH + "/" + CHANNEL_KEY));
    }

    @Test
    void shouldRebuildOnlySelectedCollectionMemberAndAncestorSpine() {
        // given
        Node sameInitialMember = member("before");
        FrozenNode before = FrozenNode.fromNode(rootWithMembers(
                sameInitialMember,
                sameInitialMember.clone()));
        FrozenNode selectedBefore = before.at(SELECTED_MEMBER_PATH);
        FrozenNode siblingBefore = before.at(SIBLING_MEMBER_PATH);
        String rootBlueId = before.blueId();
        String collectionBlueId = before.at(COLLECTION_PATH).blueId();
        String siblingBlueId = siblingBefore.blueId();
        ImmutablePatchPlanner planner =
                ImmutablePatchPlanner.forFrozen(before);

        // when
        FrozenNode after = planner.plan(
                        ROOT_SCOPE,
                        JsonPatch.replace(
                                SELECTED_MEMBER_PATH + "/generation",
                                new Node().value("after")))
                .root();

        // then
        assertEquals(selectedBefore.blueId(), siblingBlueId);
        assertNotSame(before, after);
        assertNotEquals(rootBlueId, after.blueId());
        assertNotSame(before.at(COLLECTION_PATH),
                after.at(COLLECTION_PATH));
        assertNotEquals(collectionBlueId,
                after.at(COLLECTION_PATH).blueId());
        assertNotSame(selectedBefore, after.at(SELECTED_MEMBER_PATH));
        assertNotEquals(selectedBefore.blueId(),
                after.at(SELECTED_MEMBER_PATH).blueId());
        assertSame(siblingBefore, after.at(SIBLING_MEMBER_PATH));
        assertEquals(siblingBlueId,
                after.at(SIBLING_MEMBER_PATH).blueId());
        assertSame(before.at("/contracts/embedded"),
                after.at("/contracts/embedded"));
    }

    private static SequentialPatchPlanningSession planningSession(
            FrozenNode canonical,
            FrozenNode resolved) {
        EmbeddedScopePlan entryPlan = ProcessingSnapshotBootstrap
                .embeddedScopePlan(
                        resolved.at(ROOT_SCOPE),
                        ROOT_SCOPE,
                        null,
                        CanonicalTypeIdentityLookup.incomplete());
        PatchPlanningContext planning =
                PatchPlanningContextFactory.create(
                        canonical,
                        resolved,
                        false,
                        null,
                        Collections.singletonMap(ROOT_SCOPE, entryPlan));
        return new SequentialPatchPlanningSession(
                ROOT_SCOPE,
                planning,
                null,
                null,
                NOOP_METRICS);
    }

    private static ProcessorInvocationState executionWithSelectedMember(
            Node member) {
        ProcessorInvocationState execution = new ProcessorInvocationState(
                new DocumentProcessor(),
                rootWithMembers(member, member("sibling")));
        execution.runtime().scope(SELECTED_MEMBER_PATH);
        return execution;
    }

    private static DocumentUpdateData replacementUpdate(
            Node before,
            Node after) {
        return new DocumentUpdateData(
                SELECTED_MEMBER_PATH,
                before,
                after,
                JsonPatch.Op.REPLACE,
                ROOT_SCOPE,
                Collections.singletonList(ROOT_SCOPE));
    }

    private static ContractBundle collectionBundle(String... memberKeys) {
        return ContractBundle.builder()
                .setEmbedded(new ProcessEmbedded()
                        .addCollectionPath(COLLECTION_PATH))
                .build()
                .withEmbeddedScopePlan(collectionPlan(memberKeys));
    }

    private static EmbeddedScopePlan collectionPlan(String... memberKeys) {
        List<String> keys = Collections.unmodifiableList(
                new ArrayList<>(Arrays.asList(memberKeys)));
        Map<String, List<String>> members = new LinkedHashMap<>();
        members.put(COLLECTION_PATH, keys);
        List<EmbeddedConcretePath> concrete = new ArrayList<>();
        for (String key : keys) {
            concrete.add(new EmbeddedConcretePath(
                    COLLECTION_PATH + "/" + key,
                    EmbeddedPathOrigin.COLLECTION_MEMBER,
                    COLLECTION_PATH,
                    key));
        }
        return new EmbeddedScopePlan(
                ROOT_SCOPE,
                Collections.<String>emptyList(),
                Collections.singletonList(COLLECTION_PATH),
                members,
                Collections.singletonMap(
                        COLLECTION_PATH,
                        EmbeddedCollectionState.PRESENT_COLLECTION),
                concrete);
    }

    private static Node rootWithMembers(
            Node selected,
            Node sibling) {
        Node embedded = new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        ProcessorContractConstants.KEY_COLLECTION_PATHS,
                        new Node().items(
                                new Node().value(COLLECTION_PATH)));
        return new Node()
                .contracts(new Node().properties("embedded", embedded))
                .properties(
                        "lessons",
                        new Node().properties(
                                SELECTED_MEMBER_KEY,
                                selected,
                                SIBLING_MEMBER_KEY,
                                sibling));
    }

    private static Node member(String generation) {
        return new Node().properties(
                KEY_GENERATION, new Node().value(generation));
    }

    private static Node memberWithCheckpoint(
            String generation,
            String domainBlueId,
            Node subject) {
        Node entry = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.CHECKPOINT_ENTRY))
                .properties(
                        ProcessorContractConstants.KEY_DOMAIN,
                        new Node().blueId(domainBlueId),
                        ProcessorContractConstants.KEY_SUBJECT,
                        subject.clone());
        Node checkpoint = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                .properties(
                        ProcessorContractConstants.KEY_ENTRIES,
                        new Node().properties(CHANNEL_KEY, entry));
        return member(generation).contracts(
                new Node().properties(
                        ProcessorContractConstants.KEY_CHECKPOINT,
                        checkpoint));
    }

    private static Node memberWithHandler() {
        Node handler = new Node()
                .type(new Node().blueId(
                        ProcessorTestTypeBlueIds.SET_PROPERTY))
                .properties(
                        KEY_CHANNEL,
                        new Node().value(CHANNEL_KEY),
                        KEY_PROPERTY_KEY,
                        new Node().value("selected"));
        return member("child").contracts(
                new Node().properties(HANDLER_KEY, handler));
    }

    private static Node checkpointSubject(String eventId) {
        return new Node().properties(
                "eventId", new Node().value(eventId));
    }

    private static String checkpointIdentity(String discriminator) {
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().name(discriminator));
    }
}
