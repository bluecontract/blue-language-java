package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueLimitedOperationTest {

    @Test
    void shouldResolveLimitedNeverFetchesUnrelatedSiblingAndCacheWarmthCannotChangeOutcome() {
        // given
        Node unrelated = new Node().properties(
                "deep", new Node().value("not demanded"));
        String unrelatedBlueId = DirectBlueIdCalculator.calculateBlueId(unrelated);
        Node declaredType = new Node().properties(
                "wanted", new Node().value("yes"),
                "unrelated", new Node().blueId(unrelatedBlueId));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(declaredType);
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

        // when
        BlueOperationResult<Node> cold = blue.resolveLimited(
                new Node().type(new Node().blueId(typeBlueId)), oneExpansion);
        Set<String> coldRequests = new LinkedHashSet<>(requested);
        blue.loadSnapshot(unrelatedBlueId);
        requested.clear();
        BlueOperationResult<Node> warm = blue.resolveLimited(
                new Node().type(new Node().blueId(typeBlueId)), oneExpansion);
        Set<String> warmRequests = new LinkedHashSet<>(requested);

        // then
        assertEquals(BlueOperationOutcome.ESTABLISHED, cold.outcome());
        assertEquals("yes", BlueViewPath.select(cold.requireEstablished(), "/wanted").getValue());
        assertTrue(coldRequests.contains(typeBlueId));
        assertFalse(coldRequests.contains(unrelatedBlueId));
        assertEquals(cold.outcome(), warm.outcome());
        assertEquals("yes", BlueViewPath.select(warm.requireEstablished(), "/wanted").getValue());
        assertFalse(warmRequests.contains(unrelatedBlueId));
    }
}
