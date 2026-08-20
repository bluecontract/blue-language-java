package blue.language.preprocess;

import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact-resource propagation for preprocessing provider suspension. */
final class DirectiveResolverUnavailableTest {

    @Test
    void retainsRequestedExactBlueId() {
        String requested =
                "EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e";
        NodeProvider unavailable = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return Collections.emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return NodeProviderResult.unavailable("temporarily offline");
            }
        };

        ProviderUnavailableException failure = assertThrows(
                ProviderUnavailableException.class,
                () -> new DirectiveResolver(unavailable, Collections.emptyMap())
                        .fetchExactNode(requested, "test directive"));

        assertEquals(requested,
                failure.requiredExactBlueId().orElseThrow(AssertionError::new));
    }
}
