package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;
import static org.junit.jupiter.api.Assertions.*;

/** Transport fixtures do not assert execution authority; actual group/cold replay tests cover execution. */
class RetainedOriginIdentityCodecTest {
    private static final Limits LIMITS = Limits.defaults();
    private static final DocumentId OWNER = new DocumentId("retained-owner");

    @Test void exactOriginalWorkAndTransitionSurviveDifferentFinalGroupReceiptIdentity() {
        Map<String, byte[]> store = new HashMap<>();
        SourceObservationProgram program = program(hash(1), hash(2), hash(3));
        String key = SourceObservationProgramCodec.encode(program, store::put, LIMITS);
        SourceObservationProgram cold = SourceObservationProgramCodec.decode(key, store::get, LIMITS);
        assertEquals(hash(2), cold.steps().get(0).workIdentity());
        assertEquals(hash(3), ((SourceObservationProgram.Patch) cold.steps().get(0).actions().get(0)).transitionIdentity());
        assertNotEquals(cold.invocationIdentity(), cold.steps().get(0).workIdentity());
        assertNotEquals(cold.steps().get(0).entrySiteIdentity(), cold.steps().get(0).workIdentity());
        assertEquals(key, SourceObservationProgramCodec.encode(cold, (identity, bytes) -> {}, LIMITS));
        SourceObservationProgram regrouped = program(hash(9), hash(2), hash(3));
        String groupKey = SourceObservationProgramCodec.encode(regrouped, store::put, LIMITS);
        assertNotEquals(key, groupKey);
        SourceObservationProgram restoredGroup = SourceObservationProgramCodec.decode(groupKey, store::get, LIMITS);
        assertEquals(cold.steps().get(0).workIdentity(), restoredGroup.steps().get(0).workIdentity());
        assertEquals(((SourceObservationProgram.Patch) cold.steps().get(0).actions().get(0)).transitionIdentity(),
                ((SourceObservationProgram.Patch) restoredGroup.steps().get(0).actions().get(0)).transitionIdentity());
    }

    @Test void missingOriginalTransitionCannotBeInferredFromEntryOrFinalGroup() {
        assertThrows(InvalidExecutionEvidenceException.class, () -> SourceObservationProgramCodec.encode(program(hash(1), hash(2), null), (key, bytes) -> {}, LIMITS));
        assertThrows(NullPointerException.class, () -> program(hash(1), null, hash(3)));
        Map<String, byte[]> store = new HashMap<>();
        String key = SourceObservationProgramCodec.encode(program(hash(1), hash(2), hash(3)), store::put, LIMITS);
        ObjectNode root = (ObjectNode) json(store.get(key));
        ObjectNode step = (ObjectNode) json(store.get(root.get("steps").get(0).textValue()));
        ((ObjectNode) step.get("actions").get(0)).remove("transition");
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("steps")).set(0,
                com.fasterxml.jackson.databind.node.TextNode.valueOf(retain(bytes(step), store)));
        String changed = retain(bytes(root), store);
        assertThrows(InvalidExecutionEvidenceException.class, () -> SourceObservationProgramCodec.decode(changed, store::get, LIMITS));
        ObjectNode missingRoot = (ObjectNode) json(store.get(key));
        ObjectNode missingStep = (ObjectNode) json(store.get(missingRoot.get("steps").get(0).textValue())); missingStep.remove("work");
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingRoot.get("steps")).set(0,
                com.fasterxml.jackson.databind.node.TextNode.valueOf(retain(bytes(missingStep), store)));
        String missingWork = retain(bytes(missingRoot), store);
        assertThrows(InvalidExecutionEvidenceException.class, () -> SourceObservationProgramCodec.decode(missingWork, store::get, LIMITS));
        ObjectNode missingTopologyRoot = (ObjectNode) json(store.get(key));
        ObjectNode missingTopologyStep = (ObjectNode) json(store.get(missingTopologyRoot.get("steps").get(0).textValue()));
        ((ObjectNode) missingTopologyStep.get("actions").get(0)).remove("resultingBindings");
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingTopologyRoot.get("steps")).set(0,
                com.fasterxml.jackson.databind.node.TextNode.valueOf(retain(bytes(missingTopologyStep), store)));
        String missingTopology = retain(bytes(missingTopologyRoot), store);
        assertThrows(InvalidExecutionEvidenceException.class, () -> SourceObservationProgramCodec.decode(missingTopology, store::get, LIMITS));
    }

    private static SourceObservationProgram program(String operation, String work, String transition) {
        FrozenNode body = FrozenNode.fromResolvedNode(new Node().name("retained source").properties("x", new Node().value(1)));
        String blueId = DirectBlueIdCalculator.calculateBlueId(body.toNode());
        SourceObservationProgram.SourceState state = new SourceObservationProgram.SourceState(OWNER, blueId, 0, false, body);
        FrozenJsonPatch patch = FrozenJsonPatch.replace("/x", FrozenNode.fromNode(new Node().value(1)));
        SourceObservationProgram.Step step = new SourceObservationProgram.Step(work, hash(4), OWNER, WorkKind.INITIALIZATION,
                "source", body, body, body, Collections.emptyList(), Collections.singletonList(patch), false,
                Collections.singletonList(new SourceObservationProgram.Patch(hash(5), transition, body, patch, Collections.emptyList())
                        .withResultingBindings(Collections.emptyList())));
        ClosureEnvironment environment = new ClosureEnvironment(hash(10), hash(11), hash(12), hash(13), label(14), label(15), label(16), label(17),
                new ClosureEnvironment.PortableLimitPolicyEvidence(hash(18), "transport-limits", Collections.emptyMap()), hash(19), hash(20));
        return new SourceObservationProgram(operation, ProcessingCause.Kind.ADMISSION, hash(21), null, environment,
                new ExecutionPolicy(hash(22), 100000, Collections.emptyMap(), "transport-policy"), Collections.singletonList(state),
                Collections.singletonList(state), Collections.singleton(OWNER), Collections.singletonList(step));
    }
    private static ClosureEnvironment.LabeledIdentityEvidence label(int value) { return new ClosureEnvironment.LabeledIdentityEvidence(hash(value), "transport-" + value); }
    private static String hash(int value) { return "sha256:" + String.format(Locale.ROOT, "%064x", value); }
    private static String retain(byte[] bytes, Map<String, byte[]> store) { String key = digest(bytes); store.put(key, bytes); return key; }
}
