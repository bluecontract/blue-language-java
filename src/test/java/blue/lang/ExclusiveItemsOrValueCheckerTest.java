package blue.lang;

import blue.lang.graph.BasicNode;
import blue.lang.graph.NodeProcessor;
import blue.lang.graph.processor.ExclusiveItemsOrValueChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ExclusiveItemsOrValueCheckerTest {

    @Test
    public void testNodeWithOnlyItemsShouldPass() {
        BasicNode source = new BasicNode()
                .items(new BasicNode(), new BasicNode());
        BasicNode target = new BasicNode();
        NodeProcessor processor = new ExclusiveItemsOrValueChecker();

        assertDoesNotThrow(() -> processor.process(target, source, null));
    }

    @Test
    public void testNodeWithOnlyValueShouldPass() {
        BasicNode source = new BasicNode()
                .value("Some value");
        BasicNode target = new BasicNode();
        NodeProcessor processor = new ExclusiveItemsOrValueChecker();

        assertDoesNotThrow(() -> processor.process(target, source, null));
    }

    @Test
    public void testNodeWithBothItemsAndValueShouldFail() {
        BasicNode source = new BasicNode()
                .items(new BasicNode(), new BasicNode())
                .value("Some value");
        BasicNode target = new BasicNode();
        NodeProcessor processor = new ExclusiveItemsOrValueChecker();

        assertThrows(IllegalArgumentException.class, () -> processor.process(target, source, null));
    }

    @Test
    public void testNodeWithNeitherItemsNorValueShouldPass() {
        BasicNode source = new BasicNode();
        BasicNode target = new BasicNode();
        NodeProcessor processor = new ExclusiveItemsOrValueChecker();

        assertDoesNotThrow(() -> processor.process(target, source, null));
    }
}
