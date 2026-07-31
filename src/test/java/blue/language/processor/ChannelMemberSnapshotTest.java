package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChannelMemberSnapshotTest {

    @Test
    void shouldIgnoreNestedNominalTypeMaterializationProvenance() {
        // given
        Node nominalType = new Node()
                .name("Test Actor")
                .properties(
                        "kind",
                        new Node().value("actor"));
        String nominalTypeBlueId =
                BlueIdCalculator.calculateBlueId(nominalType);
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
                        collapsedActor));
        ChannelMemberSnapshot materialized =
                ChannelMemberSnapshot.from(snapshot(
                        materializedActor));

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

    private static EffectiveContractSnapshot snapshot(
            Node actor) {
        String channelTypeBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().name("Test Channel"));
        return EffectiveContractSnapshot
                .builder("/", "source")
                .effectiveTypeBlueId(channelTypeBlueId)
                .role(EffectiveContractSnapshotConstants
                        .Role.EXTERNAL_CHANNEL)
                .sourceContribution(
                        BlueIdCalculator.calculateBlueId(
                                new Node().value(
                                        "source contribution")))
                .headerField(
                        "actor",
                        FrozenNode.fromResolvedNode(actor))
                .build();
    }
}
