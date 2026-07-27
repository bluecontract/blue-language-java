package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeProviderWrapperCompatibilityTest {

    @Test
    void releasedUnverifiedEntryPointRetainsBinaryShapeButStillVerifies() {
        String requested = BlueIdCalculator.calculateBlueId(
                new Node().value("expected"));
        NodeProvider forged = blueId -> Collections.singletonList(
                new Node().value("forged"));

        NodeProvider compatible =
                NodeProviderWrapper.unverified(forged);

        assertThrows(
                IllegalArgumentException.class,
                () -> compatible.fetchByBlueId(requested));
        assertFalse(
                NodeProviderWrapper.isExplicitlyHostTrusted(
                        compatible));
    }
}
