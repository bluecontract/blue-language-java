package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Capability-boundary tests; the interpreter separately proves observable initialization replay. */
class SourceInitializationTest {
    private static final DocumentId CONSUMER = new DocumentId("consumer");

    @Test
    void canonicalComponentAdmissionDoesNotDependOnItsRequestedMemberOrIterationOrder() {
        DocumentId a = new DocumentId("a"), b = new DocumentId("b");
        Set<DocumentId> left = new java.util.LinkedHashSet<DocumentId>(Arrays.asList(a, b));
        Set<DocumentId> right = new java.util.LinkedHashSet<DocumentId>(Arrays.asList(b, a));
        assertEquals(SourceInitialization.canonicalAdmissionCause(left).causeIdentity(), SourceInitialization.canonicalAdmissionCause(right).causeIdentity());
        assertEquals("canonical-source:a", SourceInitialization.canonicalAdmissionCause(left).label());
        assertNull(SourceInitialization.canonicalAdmissionCause(left).triggeringEventBlueId());
        assertNull(SourceInitialization.canonicalAdmissionCause(left).parentTransitionIdentity());
        assertThrows(IllegalArgumentException.class, () -> SourceInitialization.canonicalAdmissionCause(Collections.emptySet()));
    }

    @Test
    void anAuthenticButTemporalOrCreatorDependentAdmissionCannotMasqueradeAsCanonicalInitialization() {
        try (Fixture f = new Fixture()) {
            List<AdmissionCause> otherCauses = Arrays.asList(
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "first-worker", null, null, "FULL_HISTORY"),
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "canonical-source:" + f.source.value(), f.source.value(), null, "FULL_HISTORY"),
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "canonical-source:" + f.source.value(), null, hash('c'), "FULL_HISTORY"),
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "canonical-source:" + f.source.value(), null, null, "FROM_NOW"));
            for (AdmissionCause cause : otherCauses) {
                ManagedDocumentSnapshot source = new ManagedDocumentSnapshot(f.source, f.source.value(), f.authored, false, false, true, 0, 0);
                ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(snapshot(Collections.singletonList(source), Collections.emptyList(), Collections.emptyList()),
                        cause, null, f.policy, f.environment);
                SourceObservationProgram[] program = new SourceObservationProgram[1];
                try (BlueClosureContracts contracts = new BlueClosureContracts(f.owner, new ClosureExecutionObserver() {
                    @Override public boolean capturesSourceObservationProgram() { return true; }
                    @Override public void onSourceObservationProgram(SourceObservationProgram value) { program[0] = value; }
                    @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
                })) {
                    ClosureAttemptResult attempt = contracts.admitExternalScope(input, Collections.singleton(f.source));
                    assertTrue(attempt.isComplete());
                    assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status());
                    assertNotNull(program[0]);
                    assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(program[0]));
                }
            }
        }
    }

    @Test
    void actualAdmissionProgramRoundTripsWithoutAnotherInitialization() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            assertSame(f.program, initialization.program());
            assertEquals(Collections.singleton(f.source), initialization.ownedDocumentIds());
            Map<String, byte[]> fragments = new HashMap<String, byte[]>();
            FrozenNodeEvidenceCodec.Limits limits = FrozenNodeEvidenceCodec.Limits.defaults();
            String trustedRoot = SourceObservationProgramCodec.encode(f.program, fragments::put, limits);
            SourceInitialization restored = SourceInitialization.fromProgram(
                    SourceObservationProgramCodec.decode(trustedRoot, fragments::get, limits));
            assertEquals(f.program.invocationIdentity(), restored.program().invocationIdentity());
            assertEquals(1, restored.program().sourceBeforeComponents().size());
            assertEquals(1, restored.program().sourceAfterComponents().size());
            assertEquals(f.program.sourceBeforeComponents().get(0).componentStateIdentity(),
                    restored.program().sourceBeforeComponents().get(0).componentStateIdentity());
            assertEquals(f.program.sourceAfterComponents().get(0).componentStateIdentity(),
                    restored.program().sourceAfterComponents().get(0).componentStateIdentity());
            assertEquals(NodeWireForm.get(f.result.document()), NodeWireForm.get(restored.program().sourceResults().get(0).document()));
            assertEquals(trustedRoot, SourceObservationProgramCodec.encode(restored.program(), fragments::put, limits));
            assertEquals(1, f.admissions, "Restoring the capability executes no source initializer");
            assertThrows(UnsupportedOperationException.class, () -> restored.ownedDocumentIds().clear());
        }
    }

    @Test
    void acceptsActualCyclicInitializationWithItsMemberIdentityRatherThanDirectBodyHash() {
        try (Fixture f = new Fixture()) {
            Node placeholder = new Node().name("Self-embedded initialization source")
                    .properties("self", new Node().blueId("this#0"))
                    .contracts(new Node().properties("embedded", new Node()
                            .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                            .properties("paths", new Node().items(Collections.singletonList(new Node().value("/self"))))));
            CyclicSetFinalization finalized = new CircularSetIdentityCalculator().finalizeCyclicSet(Collections.singletonList(placeholder));
            String authoredBlueId = finalized.membersInCanonicalOrder().get(0).finalBlueId();
            DocumentId source = new DocumentId(authoredBlueId);
            Node body = finalized.membersInCanonicalOrder().get(0).canonicalMemberBody();
            body.getAsNode("/self").blueId(authoredBlueId);
            CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(finalized.canonicalMemberBodies());
            ManagedReadPin.fromExactEvidence(source, authoredBlueId, body, proof);
            assertNotEquals(authoredBlueId, blueId(body));
            ManagedOccurrenceBinding self = ManagedOccurrenceBinding.derived(f.environment.managedBindingPolicyIdentity(), source,
                    ScopeAddress.embedded("/self", 1L), source, authoredBlueId, true, null);
            ComponentFinalizationResult exact = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                    ManagedDocumentGraph.fromBindings(Collections.singleton(source), Collections.singletonList(self)),
                    Collections.singletonMap(source, 0L), Collections.singletonMap(source, body), Collections.singletonList(self)));
            assertEquals(authoredBlueId, exact.document(source).blueId());
            ManagedDocumentSnapshot initial = new ManagedDocumentSnapshot(source, authoredBlueId, exact.document(source).document(),
                    false, false, true, 0L, 0L);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0L, Collections.singletonList(initial),
                    exact.finalizedGraph().bindings(), Collections.singletonList(exact.components().get(0).component()),
                    Collections.singletonList(source));
            ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(snapshot,
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "canonical-source:" + authoredBlueId, null, null, "FULL_HISTORY"),
                    null, f.policy, f.environment);
            final SourceObservationProgram[] observed = new SourceObservationProgram[1];
            try (BlueClosureContracts contracts = new BlueClosureContracts(f.owner, new ClosureExecutionObserver() {
                @Override public boolean capturesSourceObservationProgram() { return true; }
                @Override public void onSourceObservationProgram(SourceObservationProgram program) { observed[0] = program; }
                @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            })) {
                ClosureAttemptResult attempt = contracts.admitExternalScope(input, Collections.singleton(source));
                assertTrue(attempt.isComplete());
                assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status());
                SourceInitialization initialization = SourceInitialization.fromProgram(observed[0]);
                assertEquals(Collections.singleton(source), initialization.ownedDocumentIds());
                assertEquals(ComponentKind.CYCLIC, observed[0].sourceBeforeComponents().get(0).kind());
                assertNotNull(observed[0].sourceBeforeComponents().get(0).completeCyclicProof());
                assertEquals(authoredBlueId, observed[0].sourceBeforeBindings().get(0).expectedTargetBlueId());
                Map<String, byte[]> fragments = new HashMap<String, byte[]>();
                String trustedRoot = SourceObservationProgramCodec.encode(observed[0], fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
                SourceInitialization restored = SourceInitialization.fromProgram(SourceObservationProgramCodec.decode(
                        trustedRoot, fragments::get, FrozenNodeEvidenceCodec.Limits.defaults()));
                assertEquals(initialization.ownedDocumentIds(), restored.ownedDocumentIds());
                assertEquals(observed[0].sourceBeforeComponents().get(0).cyclicProofIdentity(),
                        restored.program().sourceBeforeComponents().get(0).cyclicProofIdentity());
                assertEquals(observed[0].sourceAfterComponents().get(0).componentStateIdentity(),
                        restored.program().sourceAfterComponents().get(0).componentStateIdentity());
                assertEquals(observed[0].sourceBeforeBindings().get(0).bindingIdentity(),
                        restored.program().sourceBeforeBindings().get(0).bindingIdentity());
                assertEquals(observed[0].sourceAfterBindings().get(0).bindingIdentity(),
                        restored.program().sourceAfterBindings().get(0).bindingIdentity());
                SourceObservationProgram missingSelfEdge = new SourceObservationProgram(observed[0].invocationIdentity(),
                        observed[0].causeKind(), observed[0].causeIdentity(), null, f.environment, f.policy,
                        observed[0].sourcePredecessors(), observed[0].sourceResults(), observed[0].ownedDocumentIds(), observed[0].steps(),
                        observed[0].referenceProjections(), Collections.<ManagedOccurrenceBinding>emptyList(),
                        observed[0].sourceAfterBindings(), observed[0].sourceBeforeComponents(), observed[0].sourceAfterComponents());
                assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(missingSelfEdge));
            }
        }
    }

    @Test
    void acceptsAuthoredCellOrExactHistoricalPinWithoutRewritingConsumer() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            for (long currentEpoch : Arrays.asList(-1L, 0L, 9L)) {
                AffectedClosureSnapshot snapshot = f.installation(currentEpoch, true, true, f.source.value());
                Node before = snapshot.managedDocument(CONSUMER).document();
                initialization.verifyInstallationBasis(snapshot, Collections.singleton(CONSUMER), f.environment, f.policy);
                assertEquals(NodeWireForm.get(before), NodeWireForm.get(snapshot.managedDocument(CONSUMER).document()));
                assertEquals(f.source.value(), snapshot.managedDocument(CONSUMER).document().getAsNode("/child").getBlueId());
                assertEquals(currentEpoch < 0 ? 0L : currentEpoch, snapshot.managedDocument(f.source).epoch());
            }
            initialization.verifyInstallationBasis(f.installation(9L, false, true, f.source.value()),
                    Collections.singleton(CONSUMER), f.environment, f.policy);
        }
    }

    @Test
    void sourcePreflightDoesNotAuthorizeAProspectiveOccurrence() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            AffectedClosureSnapshot accepted = f.installation(9L, false, true, f.source.value());
            ManagedOccurrenceBinding installed = accepted.occurrences().get(0);
            ManagedOccurrenceBinding prospective = ManagedOccurrenceBinding.derived(installed.bindingPolicyIdentity(),
                    installed.sourceDocumentId(), installed.sourceAddress(), installed.targetDocumentId(),
                    installed.expectedTargetBlueId(), false, null);
            AffectedClosureSnapshot beforeSite = snapshot(accepted.managedDocuments(), Collections.singletonList(prospective), accepted.readPins());
            initialization.verifySourceBasis(beforeSite, Collections.singleton(CONSUMER), f.environment, f.policy);
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationOccurrence(beforeSite,
                    Collections.singleton(CONSUMER), prospective.occurrenceIdentity(), f.environment, f.policy));
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(beforeSite,
                    Collections.singleton(CONSUMER), f.environment, f.policy));
            initialization.verifyInstallationOccurrence(accepted, Collections.singleton(CONSUMER),
                    installed.occurrenceIdentity(), f.environment, f.policy);
            assertEquals(9L, beforeSite.managedDocument(f.source).epoch(), "Preflight cannot rewind the live source");
            assertEquals(9L, accepted.managedDocument(f.source).epoch(), "Site verification cannot rewind the live source");
            assertEquals(1, f.admissions, "Neither check executes an initializer");
        }
    }

    @Test
    void oneNewInstallationDoesNotReinitializeAnOlderAliasOfTheSameSource() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            AffectedClosureSnapshot basis = f.installation(9L, false, true, f.source.value());
            ManagedDocumentSnapshot source = basis.managedDocument(f.source);
            Node consumer = basis.managedDocument(CONSUMER).document()
                    .properties("existing", new Node().blueId(source.blueId()));
            ManagedDocumentSnapshot x = new ManagedDocumentSnapshot(CONSUMER, blueId(consumer), consumer, false, false, true, 0L, 0L);
            ManagedOccurrenceBinding existing = ManagedOccurrenceBinding.derived(f.environment.managedBindingPolicyIdentity(),
                    CONSUMER, ScopeAddress.embedded("/existing", 1L), f.source, source.blueId(), true, null);
            ManagedOccurrenceBinding installed = basis.occurrences().get(0);
            AffectedClosureSnapshot both = snapshot(Arrays.asList(x, source), Arrays.asList(installed, existing), basis.readPins());
            initialization.verifyInstallationOccurrence(both, Collections.singleton(CONSUMER),
                    installed.occurrenceIdentity(), f.environment, f.policy);
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationOccurrence(both,
                    Collections.singleton(CONSUMER), existing.occurrenceIdentity(), f.environment, f.policy));
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationOccurrence(both,
                    Collections.singleton(CONSUMER), hash('f'), f.environment, f.policy));
            assertEquals(source.blueId(), both.managedDocument(CONSUMER).document().getAsNode("/existing").getBlueId());
            assertEquals(f.source.value(), both.managedDocument(CONSUMER).document().getAsNode("/child").getBlueId());
            assertEquals(1, f.admissions);
        }
    }

    @Test
    void sourcePreflightStillRequiresExactOriginOwnershipAndEnvironment() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            AffectedClosureSnapshot basis = f.installation(9L, false, true, f.source.value());
            assertThrows(IllegalArgumentException.class, () -> initialization.verifySourceBasis(
                    f.installation(9L, false, false, f.source.value()), Collections.singleton(CONSUMER), f.environment, f.policy));
            assertThrows(IllegalArgumentException.class, () -> initialization.verifySourceBasis(basis,
                    Collections.singleton(f.source), f.environment, f.policy));
            assertThrows(IllegalArgumentException.class, () -> initialization.verifySourceBasis(basis,
                    Collections.singleton(CONSUMER), changedEnvironment(f.environment, 11), f.policy));
        }
    }

    @Test
    void rejectsMissingAuthoredPinPrematureFinalViewAndOverlappingOwnership() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(
                    f.installation(9L, false, false, f.source.value()), Collections.singleton(CONSUMER), f.environment, f.policy));
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(
                    f.installation(0L, true, true, blueId(f.result.document())), Collections.singleton(CONSUMER), f.environment, f.policy));
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(
                    f.installation(0L, true, true, f.source.value()),
                    new java.util.HashSet<DocumentId>(Arrays.asList(CONSUMER, f.source)), f.environment, f.policy));
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(
                    f.installation(0L, true, true, f.source.value()), Collections.singleton(new DocumentId("absent")), f.environment, f.policy));
        }
    }

    @Test
    void verifiesCompleteEnvironmentAndPolicyNotOnlySelectedDigests() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            AffectedClosureSnapshot snapshot = f.installation(0L, true, true, f.source.value());
            ClosureEnvironment original = f.environment;
            for (int changed = 0; changed < 12; changed++) {
                final ClosureEnvironment different = changedEnvironment(original, changed);
                assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(
                        snapshot, Collections.singleton(CONSUMER), different, f.policy), "environment field " + changed);
            }
            List<ExecutionPolicy> differentPolicies = Arrays.asList(
                    new ExecutionPolicy(f.policy.identity(), f.policy.sharedLimit() - 1L, f.policy.localLimits(), f.policy.label()),
                    new ExecutionPolicy(f.policy.identity(), f.policy.sharedLimit(), Collections.singletonMap(f.source, 1L), f.policy.label()),
                    new ExecutionPolicy(f.policy.identity(), f.policy.sharedLimit(), f.policy.localLimits(), "different-label"),
                    new ExecutionPolicy(hash('f'), f.policy.sharedLimit(), f.policy.localLimits(), f.policy.label()));
            for (ExecutionPolicy policy : differentPolicies) assertThrows(IllegalArgumentException.class,
                    () -> initialization.verifyInstallationBasis(snapshot, Collections.singleton(CONSUMER), original, policy));
        }
    }

    @Test
    void rejectsOrdinaryProgramsAndNoncanonicalOrUnusableAdmissionStates() {
        try (Fixture f = new Fixture()) {
            Node event = new Node().name("external");
            ExternalEventCause external = ClosureEvidenceFactory.externalCause(event, blueId(event),
                    ExternalOrderKey.of(Arrays.<Object>asList(1L, blueId(event))), f.environment.externalOrderPolicyIdentity());
            SourceObservationProgram process = new SourceObservationProgram(f.program.invocationIdentity(),
                    ProcessingCause.Kind.EXTERNAL, external.causeIdentity(), external, f.environment, f.policy,
                    f.program.sourcePredecessors(), f.program.sourceResults(), f.program.ownedDocumentIds(), f.program.steps());
            assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(process));
            SourceObservationProgram missingOriginalComponents = new SourceObservationProgram(f.program.invocationIdentity(),
                    ProcessingCause.Kind.ADMISSION, f.program.causeIdentity(), null, f.environment, f.policy,
                    f.program.sourcePredecessors(), f.program.sourceResults(), f.program.ownedDocumentIds(), f.program.steps());
            assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(missingOriginalComponents));
            SourceObservationProgram.SourceState origin = f.program.sourcePredecessors().get(0);
            SourceObservationProgram.SourceState result = f.program.sourceResults().get(0);
            assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(f.changed(
                    state(f.source, origin.blueId(), 1L, false, origin.document()), result)));
            assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(f.changed(
                    state(f.source, result.blueId(), 0L, false, origin.document()), result)));
            assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(f.changed(
                    origin, state(f.source, result.blueId(), 1L, true, result.document()))));
            assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(f.changed(
                    origin, state(f.source, origin.blueId(), 0L, false, origin.document()))));
            assertThrows(IllegalArgumentException.class, () -> SourceInitialization.fromProgram(f.changed(
                    origin, state(f.source, origin.blueId(), 0L, true, origin.document()))));
            assertThrows(IllegalArgumentException.class, () -> new SourceObservationProgram(f.program.invocationIdentity(),
                    ProcessingCause.Kind.ADMISSION, f.program.causeIdentity(), null, f.environment, f.policy,
                    Arrays.asList(origin, origin), f.program.sourceResults(), f.program.ownedDocumentIds(), f.program.steps()));
        }
    }

    @Test
    void rejectsBindingIdentityOrAuthoredBodyMismatchWithoutClaimingProofOfExecution() {
        try (Fixture f = new Fixture()) {
            SourceInitialization initialization = SourceInitialization.fromProgram(f.program);
            AffectedClosureSnapshot basis = f.installation(-1L, true, false, f.source.value());
            ManagedOccurrenceBinding binding = basis.occurrences().get(0);
            ManagedOccurrenceBinding forged = new ManagedOccurrenceBinding(hash('f'), binding.bindingIdentity(),
                    binding.bindingPolicyIdentity(), binding.sourceDocumentId(), binding.sourceAddress(),
                    binding.targetDocumentId(), binding.expectedTargetBlueId(), true, null);
            AffectedClosureSnapshot forgedBinding = snapshot(basis.managedDocuments(), Collections.singletonList(forged), basis.readPins());
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(
                    forgedBinding, Collections.singleton(CONSUMER), f.environment, f.policy));
            Node differentBody = f.authored.clone().properties("counter", new Node().value(87));
            List<ManagedDocumentSnapshot> differentDocuments = Arrays.asList(basis.managedDocument(CONSUMER),
                    new ManagedDocumentSnapshot(f.source, f.source.value(), differentBody, false, false, true, 0L, 0L));
            AffectedClosureSnapshot falseState = snapshot(differentDocuments, basis.occurrences(), Collections.<ManagedReadPin>emptyList());
            assertThrows(IllegalArgumentException.class, () -> initialization.verifyInstallationBasis(
                    falseState, Collections.singleton(CONSUMER), f.environment, f.policy));
        }
    }

    private static ClosureEnvironment changedEnvironment(ClosureEnvironment e, int field) {
        ClosureEnvironment.LabeledIdentityEvidence alteredProvider = new ClosureEnvironment.LabeledIdentityEvidence(
                e.exactNodeProviderDomainIdentity(), "same-digest-different-constructor");
        return new ClosureEnvironment(field == 0 ? hash('f') : e.blueLanguageSpecificationIdentity(),
                field == 1 ? hash('f') : e.contractsSpecificationIdentity(), field == 2 ? hash('f') : e.runtimeRegistryIdentity(),
                field == 3 ? hash('f') : e.gasManifestIdentity(),
                field == 4 ? new ClosureEnvironment.LabeledIdentityEvidence(hash('f'), "other") : e.managedDocumentIdentityPolicy(),
                field == 5 ? new ClosureEnvironment.LabeledIdentityEvidence(hash('f'), "other") : e.managedBindingPolicy(),
                field == 6 ? new ClosureEnvironment.LabeledIdentityEvidence(hash('f'), "other") : field == 11 ? alteredProvider : e.exactNodeProviderDomain(),
                field == 7 ? new ClosureEnvironment.LabeledIdentityEvidence(hash('f'), "other") : e.externalOrderPolicy(),
                field == 8 ? new ClosureEnvironment.PortableLimitPolicyEvidence(e.portableLimitPolicyIdentity(),
                        e.portableLimitPolicy().label(), Collections.singletonMap("changed", 1L)) : e.portableLimitPolicy(),
                field == 9 ? hash('f') : e.cyclicFinalizerIdentity(), field == 10 ? hash('f') : e.cyclicProofVerifierIdentity());
    }

    private static SourceObservationProgram.SourceState state(DocumentId id, String blueId, long epoch, boolean initialized, Node body) {
        return new SourceObservationProgram.SourceState(id, blueId, epoch, initialized, FrozenNode.fromResolvedNode(body));
    }

    private static AffectedClosureSnapshot snapshot(List<ManagedDocumentSnapshot> documents, List<ManagedOccurrenceBinding> bindings,
                                                     List<ManagedReadPin> pins) {
        Map<DocumentId, ManagedDocumentSnapshot> byId = new HashMap<DocumentId, ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot document : documents) byId.put(document.documentId(), document);
        List<ComponentSnapshot> components = new ArrayList<ComponentSnapshot>();
        for (List<DocumentId> component : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), bindings))) {
            assertEquals(1, component.size());
            components.add(ClosureEvidenceFactory.acyclicComponent(byId.get(component.get(0))));
        }
        return ClosureEvidenceFactory.affectedClosure(0L, documents, bindings, components, new ArrayList<DocumentId>(byId.keySet()), pins);
    }

    private static String blueId(Node node) { return DirectBlueIdCalculator.calculateBlueId(node); }
    private static String hash(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return "sha256:" + new String(chars); }

    private static final class Fixture implements AutoCloseable {
        final DocumentProcessor owner = DocumentProcessor.builder().build();
        final Node authored = new Node().name("Canonical initialization Y").properties("counter", new Node().value(0)).contracts(new Node());
        final DocumentId source = new DocumentId(blueId(authored));
        final ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash('a'), hash('b'),
                "source-identity", "source-bindings", "source-provider", "source-order", "source-limits", GasSchedule.contracts10().portableLimits());
        final ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(100000L, Collections.<DocumentId, Long>emptyMap(), "source-policy");
        SourceObservationProgram program;
        final ResultingDocument result;
        int admissions;

        Fixture() {
            ManagedDocumentSnapshot initial = new ManagedDocumentSnapshot(source, source.value(), authored, false, false, true, 0L, 0L);
            ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(snapshot(Collections.singletonList(initial),
                    Collections.<ManagedOccurrenceBinding>emptyList(), Collections.<ManagedReadPin>emptyList()),
                    ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "canonical-source:" + source.value(), null, null, "FULL_HISTORY"),
                    null, policy, environment);
            ClosureExecutionObserver observer = new ClosureExecutionObserver() {
                @Override public boolean capturesSourceObservationProgram() { return true; }
                @Override public void onSourceObservationProgram(SourceObservationProgram value) { program = value; admissions++; }
                @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            };
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner, observer)) {
                ClosureAttemptResult attempt = contracts.admitExternalScope(input, Collections.singleton(source));
                assertTrue(attempt.isComplete());
                assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status());
                result = attempt.processResult().resultingDocuments().get(0);
                assertNotNull(program);
            }
        }

        AffectedClosureSnapshot installation(long epoch, boolean active, boolean pin, String consumerReference) {
            Node body = epoch < 0 ? authored : result.document();
            if (epoch > 0) body = body.properties("counter", new Node().value(epoch));
            ManagedDocumentSnapshot current = new ManagedDocumentSnapshot(source, blueId(body), body, epoch >= 0L, false, true,
                    Math.max(0L, epoch), 0L);
            Node consumer = new Node().name("X").properties("child", new Node().blueId(consumerReference)).contracts(new Node());
            ManagedDocumentSnapshot x = new ManagedDocumentSnapshot(CONSUMER, blueId(consumer), consumer, false, false, true, 0L, 0L);
            ManagedOccurrenceBinding occurrence = ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), CONSUMER,
                    ScopeAddress.embedded("/child", 1L), source, source.value(), active, active ? null : Long.valueOf(-1L));
            List<ManagedReadPin> pins = pin ? Collections.singletonList(ManagedReadPin.fromExactEvidence(source, source.value(), authored, null))
                    : Collections.<ManagedReadPin>emptyList();
            return snapshot(Arrays.asList(x, current), Collections.singletonList(occurrence), pins);
        }

        SourceObservationProgram changed(SourceObservationProgram.SourceState before, SourceObservationProgram.SourceState after) {
            return new SourceObservationProgram(program.invocationIdentity(), ProcessingCause.Kind.ADMISSION, program.causeIdentity(),
                    null, environment, policy, Collections.singletonList(before), Collections.singletonList(after), program.ownedDocumentIds(), program.steps(),
                    program.referenceProjections(), program.sourceBeforeBindings(), program.sourceAfterBindings(),
                    Collections.singletonList(component(before)), Collections.singletonList(component(after)));
        }

        private ComponentSnapshot component(SourceObservationProgram.SourceState state) {
            return ClosureEvidenceFactory.acyclicComponent(new ManagedDocumentSnapshot(state.documentId(), state.blueId(),
                    state.document(), state.initialized(), false, true, state.epoch(), 0L));
        }

        @Override public void close() { owner.close(); }
    }
}
