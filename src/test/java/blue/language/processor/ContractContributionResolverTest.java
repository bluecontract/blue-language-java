package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContractContributionResolverTest {

    @Test
    void contextuallyInheritedTypeIsReverifiedFromItsExactBlueId() {
        Node contribution = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL))
                .properties("dispatch", new Node().value("exact"));
        Node contextualType = new Node()
                .name("Contextual Scope Type")
                .contracts(new Node().properties(
                        "channel", contribution));
        BasicNodeProvider provider =
                new BasicNodeProvider(contextualType);
        String contextualTypeBlueId =
                provider.getBlueIdByName(contextualType.getName());
        Node selectedCanonicalFragment = new Node()
                .properties("local", new Node().value(true));
        FrozenNode effectiveScope = FrozenNode.fromResolvedNode(
                new Node()
                        .type(provider.fetchFirstByBlueId(
                                contextualTypeBlueId))
                        .contracts(new Node().properties(
                                "channel", contribution.clone())));

        assertEquals(
                Collections.singletonList(
                        BlueIdCalculator.calculateBlueId(
                                contribution)),
                new ContractContributionResolver(provider).resolve(
                        selectedCanonicalFragment,
                        effectiveScope,
                        "channel",
                        true));
    }

    @Test
    void effectiveContentWithoutExactTypeIdentityIsNotSourceEvidence() {
        Node contribution = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL));
        FrozenNode effectiveScope = FrozenNode.fromResolvedNode(
                new Node()
                        .type(new Node()
                                .name("Unidentified Effective Type")
                                .contracts(new Node().properties(
                                        "channel",
                                        contribution.clone())))
                        .contracts(new Node().properties(
                                "channel", contribution)));

        assertThrows(
                MustUnderstandFailureException.class,
                () -> new ContractContributionResolver(null).resolve(
                        new Node(),
                        effectiveScope,
                        "channel",
                        true));
    }
}
