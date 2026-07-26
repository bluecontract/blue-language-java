package blue.language;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueLimitedOperationTest {

    @Test
    void resolveLimitedNeverFetchesUnrelatedSiblingAndCacheWarmthCannotChangeOutcome() {
        Node unrelated = new Node().properties(
                "deep", new Node().value("not demanded"));
        String unrelatedBlueId = BlueIdCalculator.calculateBlueId(unrelated);
        Node declaredType = new Node().properties(
                "wanted", new Node().value("yes"),
                "unrelated", new Node().blueId(unrelatedBlueId));
        String typeBlueId = BlueIdCalculator.calculateBlueId(declaredType);
        Set<String> requested = new LinkedHashSet<>();
        Blue blue = new Blue(blueId -> {
            requested.add(blueId);
            if (typeBlueId.equals(blueId)) {
                return Collections.singletonList(declaredType.clone());
            }
            if (unrelatedBlueId.equals(blueId)) {
                return Collections.singletonList(unrelated.clone());
            }
            return null;
        });
        BlueOperationLimits oneExpansion =
                BlueOperationLimits.demandedPath("/wanted")
                        .withMaxReferenceExpansions(1);

        BlueOperationResult<Node> cold = blue.resolveLimited(
                new Node().type(new Node().blueId(typeBlueId)), oneExpansion);

        assertEquals(BlueOperationOutcome.ESTABLISHED, cold.outcome());
        assertEquals("yes", BlueViewPath.select(cold.requireEstablished(), "/wanted").getValue());
        assertTrue(requested.contains(typeBlueId));
        assertFalse(requested.contains(unrelatedBlueId));

        blue.loadSnapshot(unrelatedBlueId);
        requested.clear();
        BlueOperationResult<Node> warm = blue.resolveLimited(
                new Node().type(new Node().blueId(typeBlueId)), oneExpansion);

        assertEquals(cold.outcome(), warm.outcome());
        assertEquals("yes", BlueViewPath.select(warm.requireEstablished(), "/wanted").getValue());
        assertFalse(requested.contains(unrelatedBlueId));
    }
}
