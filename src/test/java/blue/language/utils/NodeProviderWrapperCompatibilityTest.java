package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeProviderWrapperCompatibilityTest {

    @Test
    void shouldRetainBinaryShapeAndStillVerifyReleasedUnverifiedEntryPoint() {
        // given
        String requested = BlueIdCalculator.calculateBlueId(
                new Node().value("expected"));
        NodeProvider forged = blueId -> Collections.singletonList(
                new Node().value("forged"));

        // when
        NodeProvider compatible =
                NodeProviderWrapper.unverified(forged);

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> compatible.fetchByBlueId(requested));
        assertFalse(
                NodeProviderWrapper.isExplicitlyHostTrusted(
                        compatible));
    }

    @Test
    void shouldReverifySubclassOfVerificationWrapper() {
        // given
        Node expected = new Node().value("expected");
        String requested =
                BlueIdCalculator.calculateBlueId(expected);
        VerifyingNodeProvider masquerading =
                new VerifyingNodeProvider(blueId -> null) {
                    @Override
                    public NodeProviderResult fetchResultByBlueId(
                            String blueId) {
                        return NodeProviderResult.found(
                                Collections.singletonList(
                                        new Node().value("forged")));
                    }
                };

        // when
        NodeProviderResult result =
                NodeProviderWrapper.wrap(masquerading)
                        .fetchResultByBlueId(requested);

        // then
        assertEquals(
                NodeProviderOutcome.INVALID_EVIDENCE,
                result.outcome());
    }

    @Test
    void shouldRetainImmutableSnapshotOfSequentialProviders() {
        // given
        Node expected = new Node().value("expected");
        String requested =
                BlueIdCalculator.calculateBlueId(expected);
        List<NodeProvider> mutableProviders =
                new ArrayList<>();
        mutableProviders.add(blueId ->
                requested.equals(blueId)
                        ? Collections.singletonList(
                                expected.clone())
                        : null);
        SequentialNodeProvider sequential =
                new SequentialNodeProvider(
                        mutableProviders);

        // when
        mutableProviders.clear();
        NodeProviderResult result =
                sequential.fetchResultByBlueId(requested);

        // then
        assertEquals(
                NodeProviderOutcome.FOUND,
                result.outcome());
        assertThrows(
                UnsupportedOperationException.class,
                () -> sequential.getNodeProviders().clear());
    }

}
