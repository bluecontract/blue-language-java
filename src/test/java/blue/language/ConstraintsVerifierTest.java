package blue.language;

import blue.language.model.Constraints;
import blue.language.model.Node;
import blue.language.processor.ConstraintsVerifier;
import blue.language.processor.SequentialMergingProcessor;
import blue.language.processor.TypeAssigner;
import blue.language.processor.ValuePropagator;
import blue.language.utils.BasicNodesProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConstraintsVerifierTest {

    private Node node;
    private Constraints constraints;
    private BasicNodesProvider nodeProvider;
    private MergingProcessor mergingProcessor;
    private Merger merger;

    @BeforeEach
    public void setUp() {
        constraints = new Constraints();
        node = new Node()
                .value("xyz")
                .constraints(constraints);
        mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ConstraintsVerifier()
                )
        );
        merger = new Merger(mergingProcessor, e -> null);
    }

    @Test
    public void testMinLengthPositive() throws Exception {
        constraints.minLength(3);
        merger.resolve(node);
        // nothing should be thrown
    }

    @Test
    public void testMinLengthNegative() throws Exception {
        constraints.minLength(4);
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(node));
    }

}
