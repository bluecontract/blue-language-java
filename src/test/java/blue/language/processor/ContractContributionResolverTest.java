package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractContributionResolverTest {

    @Test
    void shouldVerifyContextuallyInheritedTypeIsReverifiedFromItsExactBlueId() {
        // given
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
        // when
        FrozenNode effectiveScope = FrozenNode.fromResolvedNode(
                new Node()
                        .type(provider.fetchFirstByBlueId(
                                contextualTypeBlueId))
                        .contracts(new Node().properties(
                                "channel", contribution.clone())));
        java.util.List<String> contributions =
                new ContractContributionResolver(provider).resolve(
                        selectedCanonicalFragment,
                        effectiveScope,
                        "channel",
                        true);

        // then
        assertEquals(
                Collections.singletonList(
                        DirectBlueIdCalculator.calculateBlueId(
                                contribution)),
                contributions);
    }

    @Test
    void shouldVerifyEffectiveContentWithoutExactTypeIdentityIsNotSourceEvidence() {
        // given
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

        // when
        Throwable failure = captureFailure(
                () -> new ContractContributionResolver(null).resolve(
                        new Node(),
                        effectiveScope,
                        "channel",
                        true));

        // then
        assertTrue(failure instanceof MustUnderstandFailureException);
    }

    @Test
    void shouldVerifyExecutableBodySourceUsesEscapedRfc6901Pointer() {
        // given
        String field = "body~/part";
        Node body = new Node().value("cold");
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(body);
        Node contribution =
                new Node().properties(
                        field,
                        new Node().blueId(
                                bodyBlueId));
        Node selectedScope =
                new Node().contracts(
                        new Node().properties(
                                "handler",
                                contribution));

        // when
        ContractContributionResolver.BindingResolution
                resolution =
                new ContractContributionResolver(null)
                        .resolveBinding(
                                selectedScope,
                                null,
                                "handler",
                                true,
                                Collections.singletonList(
                                        field));
        ContractContributionResolver.ExecutableBodySource
                source =
                resolution.executableBodySources()
                        .get(field);

        // then
        assertEquals(
                Collections.singletonList(
                        DirectBlueIdCalculator.calculateBlueId(
                                contribution)),
                resolution.sourceContributions());
        assertEquals(
                resolution.sourceContributions().get(0),
                source.owningContributionBlueId());
        assertEquals(
                "/body~0~1part",
                source.sourcePointer());
        assertTrue(source.pureReference());
        assertEquals(
                bodyBlueId,
                resolution.exactExecutableBodies()
                        .get(field)
                        .getBlueId());
    }

    @Test
    void shouldVerifyUnavailableSourceContributionRetainsItsExactDemand() {
        // given
        Node type =
                new Node().name(
                        "Unavailable Source type");
        String typeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        type);
        Node selectedScope =
                new Node().type(
                        new Node().blueId(
                                typeBlueId));

        // when
        ExecutionEvidenceUnavailableException failure =
                captureFailure(
                        () -> new ContractContributionResolver(
                                blueId -> null)
                                .resolveBinding(
                                        selectedScope,
                                        null,
                                        "handler",
                                        true,
                                        Collections.singletonList(
                                                "program")));

        // then
        assertEquals(ExecutionEvidenceUnavailableException.class,
                failure.getClass());
        assertEquals(
                Collections.singletonList(
                        typeBlueId),
                failure.requiredExactBlueIds());
    }

    @Test
    void shouldVerifyMostDerivedInheritedInlineBodyOwnsMultipleOverlayDescriptor() {
        // given
        Node baseBody =
                new Node().value("base");
        Node derivedBody =
                new Node().value("derived");
        Node baseContribution =
                new Node().properties(
                        "program",
                        baseBody);
        Node derivedContribution =
                new Node().properties(
                        "program",
                        derivedBody);
        Node baseType =
                new Node()
                        .name("Body Source base")
                        .contracts(
                                new Node().properties(
                                        "run",
                                        baseContribution));
        String baseTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        baseType);
        Node derivedType =
                new Node()
                        .name("Body Source derived")
                        .type(new Node().blueId(
                                baseTypeBlueId))
                        .contracts(
                                new Node().properties(
                                        "run",
                                        derivedContribution));
        String derivedTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        derivedType);
        BasicNodeProvider provider =
                new BasicNodeProvider(
                        baseType,
                        derivedType);

        // when
        ContractContributionResolver.BindingResolution
                resolution =
                new ContractContributionResolver(provider)
                        .resolveBinding(
                                new Node().type(
                                        new Node().blueId(
                                                derivedTypeBlueId)),
                                null,
                                "run",
                                true,
                                Collections.singletonList(
                                        "program"));
        ContractContributionResolver.ExecutableBodySource
                source =
                resolution.executableBodySources()
                        .get("program");

        // then
        assertEquals(
                Arrays.asList(
                        DirectBlueIdCalculator.calculateBlueId(
                                baseContribution),
                        DirectBlueIdCalculator.calculateBlueId(
                                derivedContribution)),
                resolution.sourceContributions());
        assertEquals(
                resolution.sourceContributions().get(1),
                source.owningContributionBlueId());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        derivedBody),
                DirectBlueIdCalculator.calculateBlueId(
                        resolution.exactExecutableBodies()
                                .get("program")));
        assertEquals("/program", source.sourcePointer());
        assertFalse(source.pureReference());
    }
}
