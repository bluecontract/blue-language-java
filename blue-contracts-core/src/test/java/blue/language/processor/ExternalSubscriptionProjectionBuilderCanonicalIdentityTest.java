package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalSubscriptionProjectionBuilderCanonicalIdentityTest {

    private static final String CHANNEL_KEY = "incoming";

    @Test
    void preservesCompletedEffectiveTypesWithoutTrustingMixedBlueIdMetadata() {
        Node scopeTypeSource = new Node()
                .name("Authored scope type")
                .description("scope semantics retained by canonical identity");
        Node channelTypeSource = new Node()
                .name("Authored channel type")
                .description("channel semantics retained by canonical identity");
        String scopeTypeBlueId = blueId(scopeTypeSource);
        String channelTypeBlueId = blueId(channelTypeSource);
        Node selectedChannel = new Node()
                .blueId(blueId(new Node().name(
                        "Unrelated channel object metadata")))
                .type(mixedType(channelTypeSource, "channel"))
                .properties("configuration", new Node().value("retained"));
        Node selectedScope = new Node()
                .type(mixedType(scopeTypeSource, "scope"))
                .contracts(new Node().properties(
                        CHANNEL_KEY, selectedChannel));
        Node effectiveScope = selectedScope.clone();
        effectiveScope.getType().properties(
                "effectiveScopeHeader", new Node().value("completed"));
        effectiveScope.getContracts().getProperties().get(CHANNEL_KEY)
                .getType().properties(
                        "effectiveChannelHeader",
                        new Node().value("completed"));
        CanonicalTypeIdentityLookup identities = completeEvidence(
                scopeTypeSource,
                channelTypeSource);

        FrozenNode projection = builder().subscriptionProjection(
                selectedScope,
                effectiveScope,
                Collections.singleton(CHANNEL_KEY),
                false,
                identities);

        assertFalse(projection.getType().isReferenceOnly());
        assertEquals("Authored scope type", projection.getType().getName());
        assertEquals(
                "completed",
                projection.getType().getProperties()
                        .get("effectiveScopeHeader").getValue());
        FrozenNode projectedChannel = projection.getContracts()
                .getProperties().get(CHANNEL_KEY);
        assertNotNull(projectedChannel);
        assertFalse(projectedChannel.getType().isReferenceOnly());
        assertEquals(
                "Authored channel type",
                projectedChannel.getType().getName());
        assertEquals(
                "completed",
                projectedChannel.getType().getProperties()
                        .get("effectiveChannelHeader").getValue());
        assertEquals(
                "retained",
                projectedChannel.getProperties()
                        .get("configuration").getValue());

        assertEquals("Authored scope type",
                selectedScope.getType().getName());
        assertEquals("Authored channel type",
                effectiveScope.getContracts().getProperties()
                        .get(CHANNEL_KEY).getType().getName());
        assertEquals(
                scopeTypeBlueId,
                identities.requireCanonicalTypeBlueId(
                        scopeTypeSource));
        assertEquals(
                channelTypeBlueId,
                identities.requireCanonicalTypeBlueId(
                        channelTypeSource));
    }

    @Test
    void failsClosedWhenMaterializedTypeEvidenceIsUnavailable() {
        Node typeSource = new Node().name("Unproven inline type");
        Node selectedScope = new Node()
                .type(mixedType(typeSource, "unproven"))
                .contracts(new Node().properties(
                        CHANNEL_KEY,
                        new Node()
                                .type(mixedType(
                                        typeSource,
                                        "unproven contract"))
                                .value("retained")));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> builder().subscriptionProjection(
                        selectedScope,
                        selectedScope.clone(),
                        Collections.<String>emptySet(),
                        true,
                        CanonicalTypeIdentityLookup.incomplete()));

        assertTrue(failure.getMessage().contains(
                "canonical type identity evidence"));
    }

    private static ExternalSubscriptionProjectionBuilder builder() {
        return new ExternalSubscriptionProjectionBuilder(
                null,
                null,
                null,
                Collections.<String, List<String>>emptyMap());
    }

    private static Node mixedType(Node authoredType, String label) {
        return authoredType.clone().blueId(blueId(
                new Node().name("Unrelated " + label + " metadata")));
    }

    private static CanonicalTypeIdentityLookup completeEvidence(
            Node... authoredTypes) {
        Map<String, CanonicalTypeIdentityEvidence> evidence =
                new LinkedHashMap<>();
        for (Node authoredType : authoredTypes) {
            String typeBlueId = blueId(authoredType);
            evidence.put(
                    authoredType.getName(),
                    CanonicalTypeIdentityEvidence.authoredInline(
                            typeBlueId,
                            authoredType.clone(),
                            authoredType));
        }
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                return Optional.ofNullable(
                        evidence.get(completedType.getName()));
            }
        };
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }
}
