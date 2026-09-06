package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;
import static org.junit.jupiter.api.Assertions.*;

/** The saved root models the trusted association subsequently carried by the owning receipt. */
final class ReusableComponentAuthorityCodecTest {
    private static final DocumentId A = new DocumentId("header-a"), B = new DocumentId("header-b");

    @Test void coldExactChainNeedsNoOriginalBodyOrProofFragments() {
        AffectedClosureSnapshot full = ReusableComponentAuthorityTest.chain();
        AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.emptySet());
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        List<String> trustedRoots = new ArrayList<String>();
        Encoder encoder = new Encoder(store::put, Limits.defaults());
        for (ReusableComponentAuthority authority : sparse.reusableComponents())
            trustedRoots.add(ReusableComponentAuthorityCodec.encode(authority, encoder));
        assertEquals(3, store.size(), "Only three compact component authority roots, no application body fragments");
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            Decoder decoder = new Decoder(store::get, Limits.defaults());
            List<ReusableComponentAuthority> decoded = new ArrayList<ReusableComponentAuthority>();
            for (String root : trustedRoots) decoded.add(ReusableComponentAuthorityCodec.decode(root, decoder, processor));
            AffectedClosureSnapshot cold = snapshot(decoded, full.graphGeneration());
            assertEquals(full.closureIdentity(), cold.closureIdentity());
            ClosureEvidenceVerifier.verifySnapshot(cold);
            for (ManagedDocumentSnapshot member : cold.managedDocuments()) assertFalse(member.hasResidentBody());
            ManagedReadPin exactA = ManagedReadPin.fromExactEvidence(A, full.managedDocument(A).blueId(), full.managedDocument(A).document(), null);
            AffectedClosureSnapshot hydrated = cold.withResidentBody(exactA);
            assertEquals(full.closureIdentity(), hydrated.closureIdentity());
            assertFalse(hydrated.managedDocument(B).hasResidentBody());
            assertEquals(full.canonicalSemanticView().closureIdentity(), hydrated.canonicalSemanticView().closureIdentity());
        }
    }

    @Test void coldCyclicAuthorityRetainsRealMappingAndNormalizationWithoutProofPayload() {
        AffectedClosureSnapshot full = ReusableComponentAuthorityTest.cycle("not-retained-application-body");
        ReusableComponentAuthority authority = full.retainResidentBodies(Collections.emptySet()).reusableComponents().get(0);
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        String root = ReusableComponentAuthorityCodec.encode(authority, store::put, Limits.defaults());
        assertEquals(1, store.size());
        assertFalse(new String(store.get(root), StandardCharsets.UTF_8).contains("not-retained-application-body"));
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ReusableComponentAuthority decoded = ReusableComponentAuthorityCodec.decode(root, store::get, Limits.defaults(), processor);
            assertEquals(authority.preliminaryBlueIds(), decoded.preliminaryBlueIds());
            assertEquals(authority.canonicalMemberIndexes(), decoded.canonicalMemberIndexes());
            assertEquals(authority.component().cyclicProofIdentity(), decoded.component().cyclicProofIdentity());
            assertFalse(decoded.component().hasResidentCyclicProof());
            AffectedClosureSnapshot cold = snapshot(Collections.singletonList(decoded), full.graphGeneration());
            assertEquals(full.closureIdentity(), cold.closureIdentity());
            assertEquals(full.canonicalSemanticView().closureIdentity(), cold.canonicalSemanticView().closureIdentity());
            ClosureEvidenceVerifier.verifySnapshot(cold);
            ManagedReadPin exactA = ManagedReadPin.fromExactEvidence(A, full.managedDocument(A).blueId(),
                    full.managedDocument(A).document(), full.component(A).completeCyclicProof());
            ClosureEvidenceVerifier.verifySnapshot(cold.withResidentBody(exactA));
            assertThrows(ExecutionEvidenceUnavailableException.class, () -> cold.managedDocument(B).document());
        }
    }

    @Test void coldRootRoutingMetadataRemainsBoundToTheExactVerifiedMember() {
        AffectedClosureSnapshot full = ReusableComponentAuthorityTest.chain();
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        try (DocumentProcessor processor = DocumentProcessor.builder().build(); BlueClosureContracts contracts = new BlueClosureContracts(processor)) {
            AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.emptySet(), contracts.captureRootMetadata(full));
            ReusableComponentAuthority original = sparse.managedDocument(B).reusableAuthority().get();
            String root = ReusableComponentAuthorityCodec.encode(original, store::put, Limits.defaults());
            ReusableComponentAuthority decoded = ReusableComponentAuthorityCodec.decode(root, store::get, Limits.defaults(), processor);
            RootChannelMetadata metadata = decoded.memberHeader(B).rootMetadata().get();
            metadata.verifyState(full.managedDocument(B));
            assertNull(metadata.routingDocument().getProperties(), "Routing evidence is not a disguised application body");
            assertFalse(decoded.memberHeader(B).hasResidentBody());
            ObjectNode rootJson = (ObjectNode) json(store.get(root));
            String metadataRoot = rootJson.get("members").get(0).get("rootMetadata").textValue();
            ObjectNode badMetadata = (ObjectNode) json(store.get(metadataRoot)); badMetadata.put("epoch", 9L);
            String changedMetadataRoot = put(store, badMetadata);
            ((ObjectNode) rootJson.get("members").get(0)).put("rootMetadata", changedMetadataRoot);
            String changedRoot = put(store, rootJson);
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> ReusableComponentAuthorityCodec.decode(changedRoot, store::get, Limits.defaults(), processor));
        }
    }

    @Test void damagedFragmentsAndInternallyInconsistentRehashedHeadersAreRejected() {
        ReusableComponentAuthority original = ReusableComponentAuthorityTest.chain().retainResidentBodies(Collections.emptySet())
                .managedDocument(B).reusableAuthority().get();
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        String root = ReusableComponentAuthorityCodec.encode(original, store::put, Limits.defaults());
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            byte[] altered = store.get(root).clone(); altered[10] ^= 1;
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> ReusableComponentAuthorityCodec.decode(root, ignored -> altered, Limits.defaults(), processor));
            ObjectNode inconsistent = (ObjectNode) json(store.get(root));
            ((ObjectNode) inconsistent.get("members").get(0)).put("blueId", DirectBlueIdCalculator.calculateBlueId(new Node().name("different")));
            String changedRoot = put(store, inconsistent);
            assertThrows(IllegalArgumentException.class,
                    () -> ReusableComponentAuthorityCodec.decode(changedRoot, store::get, Limits.defaults(), processor));
        }
    }

    @Test void staleOutgoingActivationAndForeignCanonicalMemberMapAreRejected() {
        ReusableComponentAuthority original = ReusableComponentAuthorityTest.cycle("cycle").retainResidentBodies(Collections.emptySet()).reusableComponents().get(0);
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        String root = ReusableComponentAuthorityCodec.encode(original, store::put, Limits.defaults());
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ObjectNode altered = (ObjectNode) json(store.get(root));
            ((ObjectNode) altered.get("bindings").get(0)).put("activation", 9L);
            String activation = put(store, altered);
            assertThrows(IllegalArgumentException.class,
                    () -> ReusableComponentAuthorityCodec.decode(activation, store::get, Limits.defaults(), processor));
            ObjectNode mapping = (ObjectNode) json(store.get(root));
            ((ObjectNode) mapping.get("canonicalIndexes")).put("foreign", 0);
            String foreign = put(store, mapping);
            assertThrows(IllegalArgumentException.class,
                    () -> ReusableComponentAuthorityCodec.decode(foreign, store::get, Limits.defaults(), processor));
        }
    }

    @Test void AcquisitionFailuresRemainNamedAndBoundedRatherThanSemanticFailures() {
        ReusableComponentAuthority original = ReusableComponentAuthorityTest.chain().retainResidentBodies(Collections.emptySet())
                .managedDocument(B).reusableAuthority().get();
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        String root = ReusableComponentAuthorityCodec.encode(original, store::put, Limits.defaults());
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ExecutionEvidenceUnavailableException absent = assertThrows(ExecutionEvidenceUnavailableException.class,
                    () -> ReusableComponentAuthorityCodec.decode(root, ignored -> null, Limits.defaults(), processor));
            assertEquals(Collections.singletonList(root), absent.requiredExactBlueIds());
            Limits small = new Limits(32, 32, 8, 8);
            assertThrows(CapacityExceeded.class, () -> ReusableComponentAuthorityCodec.decode(root, store::get, small, processor));
            assertThrows(CapacityExceeded.class, () -> ReusableComponentAuthorityCodec.encode(original, store::put,
                    new Limits(16000, 16000, 8, 1)));
            ObjectNode extra = (ObjectNode) json(store.get(root)); extra.put("unverifiedLatest", true);
            String changed = put(store, extra);
            assertThrows(InvalidExecutionEvidenceException.class,
                    () -> ReusableComponentAuthorityCodec.decode(changed, store::get, Limits.defaults(), processor));
        }
    }

    @Test void authoredPendingCursorMinusOneRoundTripsWithoutLiveActivation() {
        Node child = new Node().name("not-yet-initialized");
        String childId = DirectBlueIdCalculator.calculateBlueId(child);
        Map<DocumentId, Node> bodies = new LinkedHashMap<DocumentId, Node>();
        bodies.put(A, new Node().name("creator").properties("child", new Node().blueId(childId)));
        bodies.put(B, child);
        String policy = "sha256:" + String.join("", Collections.nCopies(64, "a"));
        ManagedOccurrenceBinding pending = ManagedOccurrenceBinding.derived(policy, A, ScopeAddress.embedded("/child", 1L),
                B, childId, false, -1L);
        ReusableComponentAuthority original = ReusableComponentAuthorityTest.snapshot(bodies, Collections.singletonList(pending))
                .retainResidentBodies(Collections.emptySet()).managedDocument(A).reusableAuthority().get();
        Map<String, byte[]> store = new LinkedHashMap<String, byte[]>();
        String root = ReusableComponentAuthorityCodec.encode(original, store::put, Limits.defaults());
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ReusableComponentAuthority cold = ReusableComponentAuthorityCodec.decode(root, store::get, Limits.defaults(), processor);
            assertEquals(Long.valueOf(-1L), cold.outgoingBindings().get(0).pendingHistoricalEpoch());
            assertFalse(cold.outgoingBindings().get(0).active());
        }
    }

    private static String put(Map<String, byte[]> store, JsonNode value) {
        byte[] bytes = bytes(value); String identity = digest(bytes); store.put(identity, bytes); return identity;
    }
    private static AffectedClosureSnapshot snapshot(List<ReusableComponentAuthority> authorities, long generation) {
        List<ManagedDocumentSnapshot> members = new ArrayList<ManagedDocumentSnapshot>();
        List<ManagedOccurrenceBinding> rows = new ArrayList<ManagedOccurrenceBinding>();
        Map<DocumentId, ComponentSnapshot> owners = new LinkedHashMap<DocumentId, ComponentSnapshot>();
        List<DocumentId> publicRoots = new ArrayList<DocumentId>();
        for (ReusableComponentAuthority authority : authorities) {
            for (ManagedDocumentSnapshot member : authority.memberHeaders()) {
                members.add(member); owners.put(member.documentId(), authority.component());
                if (member.publicRoot()) publicRoots.add(member.documentId());
            }
            rows.addAll(authority.outgoingBindings());
        }
        List<ComponentSnapshot> components = new ArrayList<ComponentSnapshot>();
        for (List<DocumentId> component : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(owners.keySet(), rows)))
            components.add(owners.get(component.get(0)));
        return ClosureEvidenceFactory.affectedClosure(generation, members, rows, components, publicRoots);
    }
}
