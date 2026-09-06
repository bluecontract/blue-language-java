package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClosureResourceDemandCodecTest {
    @Test void coldDemandRestoresEveryClosedKindWithoutRerunningProducerOrLosingInlineValue() {
        try (SourceInitializationAttachmentTest.Fixture f = new SourceInitializationAttachmentTest.Fixture()) {
            SameOriginProcessAttempt pending = f.contracts.processSameOrigin(f.input, f.attachments,
                    Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
            SourceInitializationDemand initialization = assertInstanceOf(SourceInitializationDemand.class, pending.resourceDemands().get(0));
            Node inline = new Node().value("exact inline occurrence");
            String id = DirectBlueIdCalculator.calculateBlueId(inline);
            ManagedOccurrenceEvidenceDemand occurrence = ManagedOccurrenceEvidenceDemand.derived(f.input.cause().causeIdentity(),
                    f.input.snapshot().closureIdentity(), f.input.snapshot().graphGeneration(), initialization.sourceDocumentId(),
                    "/another", initialization.suppliedValueBlueId(), id, 1L, inline);
            for (ClosureResourceDemand original : Arrays.asList(initialization, occurrence,
                    ExactNodeDemand.derived(id, initialization.sourceDocumentId(), "/exact"))) {
                Map<String, byte[]> bytes = new HashMap<String, byte[]>();
                String root = ClosureResourceDemandCodec.encode(original, bytes::put, FrozenNodeEvidenceCodec.Limits.defaults());
                ClosureResourceDemand cold = ClosureResourceDemandCodec.decode(root, bytes::get, FrozenNodeEvidenceCodec.Limits.defaults());
                assertEquals(original, cold); assertEquals(original.getClass(), cold.getClass());
                assertEquals(original.sourceDocumentId(), cold.sourceDocumentId());
                assertEquals(original.sourcePath(), cold.sourcePath());
                assertEquals(root, ClosureResourceDemandCodec.encode(cold, (key, value) -> { }, FrozenNodeEvidenceCodec.Limits.defaults()));
                if (cold instanceof SourceInitializationDemand) {
                    SourceInitializationDemand restored = (SourceInitializationDemand) cold;
                    assertEquals(initialization.selection().identity(), restored.selection().identity());
                    assertEquals(initialization.creatorSeedIdentity(), restored.creatorSeedIdentity());
                    assertEquals(initialization.creatorPatchSite(), restored.creatorPatchSite());
                    assertEquals(SourceObservationProgramCodec.environment(initialization.environment()), SourceObservationProgramCodec.environment(restored.environment()));
                    assertEquals(SourceObservationProgramCodec.policy(initialization.executionPolicy()), SourceObservationProgramCodec.policy(restored.executionPolicy()));
                }
                if (cold instanceof ManagedOccurrenceEvidenceDemand)
                    assertEquals(id, DirectBlueIdCalculator.calculateBlueId(((ManagedOccurrenceEvidenceDemand) cold).suppliedExactValue().get()));
            }
            assertEquals(1, f.initializerExecutions);
        }
    }

    @Test void rejectsForeignFragmentsAndUnknownFieldsRatherThanDroppingThem() {
        String id = DirectBlueIdCalculator.calculateBlueId(new Node().value("required"));
        ExactNodeDemand demand = ExactNodeDemand.derived(id, new DocumentId("source"), "/child");
        Map<String, byte[]> bytes = new HashMap<String, byte[]>();
        String root = ClosureResourceDemandCodec.encode(demand, bytes::put, FrozenNodeEvidenceCodec.Limits.defaults());
        com.fasterxml.jackson.databind.node.ObjectNode changed = (com.fasterxml.jackson.databind.node.ObjectNode) FrozenNodeEvidenceCodec.json(bytes.get(root));
        changed.put("foreign", "must not disappear");
        byte[] encoded = FrozenNodeEvidenceCodec.bytes(changed);
        String changedRoot = FrozenNodeEvidenceCodec.digest(encoded); bytes.put(changedRoot, encoded);
        assertThrows(blue.language.processor.InvalidExecutionEvidenceException.class, () -> ClosureResourceDemandCodec.decode(root, key -> encoded, FrozenNodeEvidenceCodec.Limits.defaults()));
        assertThrows(blue.language.processor.InvalidExecutionEvidenceException.class, () -> ClosureResourceDemandCodec.decode(changedRoot, bytes::get, FrozenNodeEvidenceCodec.Limits.defaults()));
    }
}
