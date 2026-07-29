package blue.language.provider;

import blue.language.model.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.*;

class ClasspathBasedNodeProviderTest {

    private ClasspathBasedNodeProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        provider = new ClasspathBasedNodeProvider("samples");
    }

    @Test
    void shouldFetchByBlueId() {
        // given
        Node sample = provider.findNodeByName("Sample 1")
                .orElseThrow(() -> new AssertionError("Sample 1 should be present"));
        String knownBlueId = sample.getAsText("/blueId");

        // when
        List<Node> nodes = provider.fetchByBlueId(knownBlueId);

        // then
        assertNotNull(nodes);
        assertFalse(nodes.isEmpty());
        assertEquals(knownBlueId, nodes.get(0).get("/blueId"));
    }

    @Test
    void shouldRejectInvalidClasspathDirectory() {
        // given
        String invalidDirectory = "non-existent-directory";

        // when
        Throwable failure = captureFailure(() -> new ClasspathBasedNodeProvider(invalidDirectory));

        // then
        assertInstanceOf(IOException.class, failure);
    }
}
