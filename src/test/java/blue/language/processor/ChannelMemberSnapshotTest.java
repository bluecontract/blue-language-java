package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChannelMemberSnapshotTest {

    @Test
    void shouldProjectNestedMaterializedTypeFromResolverEvidence() {
        // given
        Node nominalType = new Node()
                .name("Test Actor")
                .properties(
                        "kind",
                        new Node().value("actor"));
        String nominalTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(nominalType);
        Node collapsedActor = new Node()
                .type(new Node().blueId(nominalTypeBlueId))
                .properties(
                        "actorId",
                        new Node().value("alice"));
        Node materializedActor = collapsedActor.clone()
                .type(nominalType.clone().blueId(
                        nominalTypeBlueId));

        // when
        ChannelMemberSnapshot collapsed =
                ChannelMemberSnapshot.from(snapshot(
                        collapsedActor),
                        CanonicalTypeIdentityLookup.incomplete());
        ChannelMemberSnapshot materialized =
                ChannelMemberSnapshot.from(snapshot(
                        materializedActor),
                        evidenceFor(
                                nominalTypeBlueId,
                                CanonicalTypeIdentityEvidence
                                        .referenceSource(
                                                nominalTypeBlueId)));

        // then
        assertEquals(
                collapsed.headerIdentityBlueId(),
                materialized.headerIdentityBlueId());
        assertTrue(materialized.contractNode()
                .getProperties()
                .get("actor")
                .getType()
                .isReferenceOnly());
    }

    @Test
    void shouldFailClosedWithoutNestedMaterializedTypeEvidence() {
        // given
        Node materializedActor = new Node()
                .type(new Node().name("Unproven Actor"))
                .properties(
                        "actorId",
                        new Node().value("alice"));

        // when
        Executable projection = () -> ChannelMemberSnapshot.from(
                snapshot(materializedActor),
                CanonicalTypeIdentityLookup.incomplete());

        // then
        assertThrows(
                IllegalStateException.class,
                projection);
    }

    @Test
    void shouldRetainAuthoredInlineTypeBodyInsteadOfCollapsingMixedBlueId() {
        // given
        Node authoredType = new Node()
                .name("Test Actor")
                .properties(
                        "kind",
                        new Node().value("actor"));
        String typeBlueId =
                DirectBlueIdCalculator.calculateBlueId(authoredType);
        Node materializedType = authoredType.clone()
                .blueId(DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("Unrelated asserted identity")));
        Node materializedActor = new Node()
                .type(materializedType)
                .properties(
                        "actorId",
                        new Node().value("alice"));

        // when
        ChannelMemberSnapshot projected = ChannelMemberSnapshot.from(
                snapshot(materializedActor),
                evidenceFor(
                        typeBlueId,
                        CanonicalTypeIdentityEvidence.authoredInline(
                                typeBlueId,
                                authoredType.clone(),
                                authoredType)));

        // then
        Node projectedType = projected.contractNode()
                .getProperties()
                .get("actor")
                .getType();
        assertFalse(projectedType.isReferenceOnly());
        assertEquals("Test Actor", projectedType.getName());
        assertEquals(
                "actor",
                projectedType.getProperties().get("kind").getValue());
    }

    private static EffectiveContractSnapshot snapshot(
            Node actor) {
        String channelTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("Test Channel"));
        return EffectiveContractSnapshot
                .builder("/", "source")
                .effectiveTypeBlueId(channelTypeBlueId)
                .role(EffectiveContractSnapshotConstants
                        .Role.EXTERNAL_CHANNEL)
                .sourceContribution(
                        DirectBlueIdCalculator.calculateBlueId(
                                new Node().value(
                                        "source contribution")))
                .headerField(
                        "actor",
                        FrozenNode.fromResolvedNode(actor))
                .build();
    }

    private static CanonicalTypeIdentityLookup evidenceFor(
            String blueId,
            CanonicalTypeIdentityEvidence evidence) {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                return completedType != null
                                && !completedType.isReferenceOnly()
                        ? Optional.of(evidence)
                        : Optional.of(CanonicalTypeIdentityEvidence
                                .referenceSource(
                                        completedType.getBlueId()));
            }

            @Override
            public String requireCanonicalTypeBlueId(
                    Node completedType) {
                return completedType != null
                                && completedType.isReferenceOnly()
                        ? completedType.getBlueId()
                        : blueId;
            }
        };
    }
}
