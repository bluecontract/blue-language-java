package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.Properties;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

/**
 * Warm-cache resolution benchmarks for ordinary and recursive type graphs.
 */
@State(Scope.Benchmark)
public class RecursiveTypeResolutionBenchmark {

    private Blue acyclicBlue;
    private Node acyclicTemplate;
    private Blue recursiveBlue;
    private Node recursiveTemplate;
    private Blue recursiveReferenceBlue;
    private Node recursiveReferenceTemplate;

    @Setup(Level.Trial)
    public void setUp() {
        BasicNodeProvider acyclicProvider = new BasicNodeProvider();
        Node leaf = new Node().name("Benchmark Leaf")
                .properties("content", new Node().type(reference(Properties.TEXT_TYPE_BLUE_ID)));
        acyclicProvider.addSingleNodes(leaf);
        Node root = new Node().name("Benchmark Root")
                .properties("child", new Node().type(
                        reference(acyclicProvider.getBlueIdByName("Benchmark Leaf"))));
        acyclicProvider.addSingleNodes(root);
        acyclicBlue = new Blue(acyclicProvider);
        acyclicTemplate = instanceOf(acyclicProvider.getBlueIdByName("Benchmark Root"))
                .properties("child", new Node().properties("content", new Node().value("benchmark")));
        acyclicBlue.resolve(acyclicTemplate.clone());

        Node recursiveDocuments = YAML_MAPPER.readValue(
                "- name: Benchmark Person\n"
                        + "  pet:\n"
                        + "    type:\n"
                        + "      blueId: this#1\n"
                        + "- name: Benchmark Dog\n"
                        + "  owner:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n",
                Node.class);
        BasicNodeProvider recursiveProvider = new BasicNodeProvider(recursiveDocuments);
        String personId = recursiveProvider.getBlueIdByName("Benchmark Person");
        String dogId = recursiveProvider.getBlueIdByName("Benchmark Dog");
        recursiveBlue = new Blue(recursiveProvider);
        recursiveTemplate = instanceOf(personId);
        recursiveBlue.resolve(recursiveTemplate.clone());

        Node dog = new Node().name("Benchmark Fido").type(reference(dogId));
        recursiveProvider.addSingleNodes(dog);
        String dogReferenceId = recursiveProvider.getBlueIdByName("Benchmark Fido");
        recursiveReferenceBlue = new Blue(recursiveProvider);
        recursiveReferenceTemplate = instanceOf(personId)
                .properties("pet", reference(dogReferenceId));
        recursiveReferenceBlue.resolve(recursiveReferenceTemplate.clone());
    }

    @Benchmark
    public Node acyclicTypeResolution() {
        return acyclicBlue.resolve(acyclicTemplate.clone());
    }

    @Benchmark
    public Node mutuallyRecursiveTypeResolution() {
        return recursiveBlue.resolve(recursiveTemplate.clone());
    }

    @Benchmark
    public Node typedReferenceBackedByRecursiveTypes() {
        return recursiveReferenceBlue.resolve(recursiveReferenceTemplate.clone());
    }

    private static Node instanceOf(String typeBlueId) {
        return new Node().type(reference(typeBlueId));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
