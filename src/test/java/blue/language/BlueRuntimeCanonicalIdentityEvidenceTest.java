package blue.language;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.BlueMapper;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.runtime.LanguageProcessing.ExactResolutionOverlay;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlueRuntimeCanonicalIdentityEvidenceTest {

    @Test
    void shouldUseProducingSnapshotEvidenceForFocusedRuntimeOperations() {
        // given
        Node type = new Node().name("Evidence Type");
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(type);
        NodeProvider provider = blueId -> typeBlueId.equals(blueId)
                ? Collections.singletonList(type.clone())
                : null;
        BlueMapper mapping = BlueMapper.builder()
                .register(typeBlueId, EvidenceValue.class)
                .build();

        try (BlueRuntime runtime = BlueRuntime.builder()
                .nodeProvider(provider)
                .mapping(mapping)
                .build()) {
            Node source = new Node()
                    .type(new Node().blueId(typeBlueId))
                    .properties("message", new Node().value("hello"));

            // when
            ResolvedSnapshot snapshot = runtime.language()
                    .snapshots()
                    .resolve(source);

            // then
            assertEquals(
                    EvidenceValue.class,
                    mapping.mappedClass(
                            snapshot.resolvedRoot(),
                            snapshot.canonicalTypeIdentities()).orElse(null));
            assertEquals(
                    "hello",
                    mapping.fromNode(
                            snapshot.resolvedRoot(),
                            EvidenceValue.class,
                            snapshot.canonicalTypeIdentities()).message);

            assertThrows(
                    IllegalStateException.class,
                    () -> mapping.mappedClass(snapshot.resolvedRoot()));
            assertThrows(
                    RuntimeException.class,
                    () -> mapping.fromNode(
                            snapshot.resolvedRoot(),
                            EvidenceValue.class));
        }
    }

    @Test
    void shouldResolveAggregateTypeIdentityThroughExactOverlay() {
        // given
        Node parent = new Node().name("Overlay-only parent type");
        String parentBlueId = DirectBlueIdCalculator.calculateBlueId(parent);
        Node child = new Node()
                .name("Aggregate inline child")
                .type(new Node().blueId(parentBlueId));
        ExactResolutionOverlay overlay = ExactResolutionOverlay.from(
                blueId -> parentBlueId.equals(blueId)
                        ? Collections.singletonList(parent.clone())
                        : Collections.emptyList());

        try (Blue blue = new Blue(blueId -> null)) {
            // when
            CanonicalTypeIdentityEvidence evidence =
                    blue.resolveTypeDeclarationIdentity(child, overlay);

            // then
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(child),
                    evidence.blueId());
            assertEquals(
                    parentBlueId,
                    evidence.canonicalTypeIdentityInput()
                            .getType()
                            .getBlueId());
        }
    }

    public static final class EvidenceValue {
        public String message;
    }
}
