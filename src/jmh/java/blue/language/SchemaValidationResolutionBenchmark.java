package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.limits.PathLimits;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolution benchmarks for completed-instance schema validation.
 *
 * <p>All providers, type graphs, and immutable source templates are built once
 * per trial. Each invocation clones only its source template because resolution
 * expands type metadata on the mutable input node.</p>
 */
@State(Scope.Benchmark)
public class SchemaValidationResolutionBenchmark {

    private Blue schemaFreeBlue;
    private Node schemaFreeTemplate;
    private Blue sparseBlue;
    private Node sparseTemplate;
    private Blue denseBlue;
    private Node denseTemplate;
    private Blue deepBlue;
    private Node deepTemplate;
    private Blue warmReferenceBlue;
    private Node warmReferenceTemplate;
    private Blue pathLimitedBlue;
    private Node pathLimitedTemplate;
    private ThreadLocal<PathLimits> pathLimits;
    private Blue alternatingSnapshotBlue;
    private Node directSnapshotTemplate;
    private Node referencedSnapshotTemplate;
    private AtomicInteger alternatingSnapshotOrder;
    private Blue alternatingNestedSnapshotBlue;
    private Node nestedMaterializedSnapshotTemplate;
    private Node nestedReferencedSnapshotTemplate;
    private AtomicInteger alternatingNestedSnapshotOrder;

    @Setup(Level.Trial)
    public void setUp() {
        schemaFreeBlue = new Blue(new BasicNodeProvider());
        schemaFreeTemplate = materializedDocument(1_024);

        Fixture sparse = constrainedDocument(1_000, 10);
        sparseBlue = sparse.blue;
        sparseTemplate = sparse.instance;

        Fixture pathLimited = constrainedDocument(1_000, 10);
        pathLimitedBlue = pathLimited.blue;
        pathLimitedTemplate = pathLimited.instance;
        Set<String> allowedPaths = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            allowedPaths.add("/field" + index);
        }
        pathLimits = ThreadLocal.withInitial(() -> new PathLimits(allowedPaths, 8));

        Fixture dense = constrainedDocument(512, 512);
        denseBlue = dense.blue;
        denseTemplate = dense.instance;

        Fixture deep = deepInheritedDocument(16);
        deepBlue = deep.blue;
        deepTemplate = deep.instance;

        Fixture warmReference = warmTypedReferenceDocument();
        warmReferenceBlue = warmReference.blue;
        warmReferenceTemplate = warmReference.instance;
        warmReferenceBlue.resolve(warmReferenceTemplate.clone());

        BasicNodeProvider alternatingProvider = new BasicNodeProvider();
        directSnapshotTemplate = new Node().name("Alternating Snapshot Subject")
                .properties("identifier", new Node().value("benchmark-subject"));
        alternatingProvider.addSingleNodes(directSnapshotTemplate);
        referencedSnapshotTemplate = reference(
                alternatingProvider.getBlueIdByName("Alternating Snapshot Subject"));
        alternatingSnapshotBlue = new Blue(alternatingProvider);
        alternatingSnapshotOrder = new AtomicInteger();

        Node nestedSubject = new Node().name("Alternating Nested Snapshot Subject")
                .properties("identifier", new Node().value("benchmark-subject"));
        String nestedSubjectId = new Blue().calculateBlueId(nestedSubject);
        nestedMaterializedSnapshotTemplate = new Node().properties("subject", nestedSubject);
        nestedReferencedSnapshotTemplate = new Node().properties(
                "subject", reference(nestedSubjectId));
        alternatingNestedSnapshotBlue = new Blue();
        String nestedHolderBlueId = alternatingNestedSnapshotBlue.calculateBlueId(
                nestedMaterializedSnapshotTemplate);
        if (!nestedHolderBlueId.equals(alternatingNestedSnapshotBlue.calculateBlueId(
                nestedReferencedSnapshotTemplate))) {
            throw new IllegalStateException("Alternating nested snapshots must have one semantic identity.");
        }
        alternatingNestedSnapshotBlue.resolveToSnapshot(nestedMaterializedSnapshotTemplate);
        alternatingNestedSnapshotBlue.resolveToSnapshot(nestedReferencedSnapshotTemplate);
        if (alternatingNestedSnapshotBlue.resolvedSnapshotCacheSize() != 2
                || alternatingNestedSnapshotBlue.resolvedReferenceCacheSize() != 1
                || !alternatingNestedSnapshotBlue.cachedResolvedSnapshot(nestedHolderBlueId).isPresent()) {
            throw new IllegalStateException(
                    "Alternating nested snapshots must retain two exact forms and one verified identity.");
        }
        alternatingNestedSnapshotOrder = new AtomicInteger();
    }

    @Benchmark
    public Node wideSchemaFreeMaterializedDocument() {
        return schemaFreeBlue.resolve(schemaFreeTemplate.clone());
    }

    @Benchmark
    public Node wideSparseSchemaMaterializedDocument() {
        return sparseBlue.resolve(sparseTemplate.clone());
    }

    @Benchmark
    public Node widePathLimitedSchemaDocument() {
        return pathLimitedBlue.resolve(pathLimitedTemplate.clone(), pathLimits.get());
    }

    @Benchmark
    public Node schemaDenseMaterializedDocument() {
        return denseBlue.resolve(denseTemplate.clone());
    }

    @Benchmark
    public Node deepInheritedSchemaChain() {
        return deepBlue.resolve(deepTemplate.clone());
    }

    @Benchmark
    public Node typedReferenceWithVerifiedWarmCache() {
        return warmReferenceBlue.resolve(warmReferenceTemplate.clone());
    }

    @Benchmark
    public blue.language.snapshot.ResolvedSnapshot sparseResolveToSnapshot() {
        return sparseBlue.resolveToSnapshot(sparseTemplate.clone());
    }

    @Benchmark
    public blue.language.snapshot.ResolvedSnapshot alternatingEquivalentDirectAndReferencedSnapshots() {
        Node source = (alternatingSnapshotOrder.getAndIncrement() & 1) == 0
                ? directSnapshotTemplate
                : referencedSnapshotTemplate;
        return alternatingSnapshotBlue.resolveToSnapshot(source.clone());
    }

    @Benchmark
    public blue.language.snapshot.ResolvedSnapshot alternatingEquivalentNestedReferenceAndMaterializedSnapshots() {
        Node source = (alternatingNestedSnapshotOrder.getAndIncrement() & 1) == 0
                ? nestedMaterializedSnapshotTemplate
                : nestedReferencedSnapshotTemplate;
        return alternatingNestedSnapshotBlue.resolveToSnapshot(source.clone());
    }

    private Node materializedDocument(int width) {
        Node document = new Node();
        for (int index = 0; index < width; index++) {
            document.properties("field" + index, new Node().value("value-" + index));
        }
        return document;
    }

    private Fixture constrainedDocument(int width, int constrainedFields) {
        Node instance = new Node();
        for (int index = 0; index < width; index++) {
            Node value = new Node().value("value-" + index);
            if (index < constrainedFields) {
                value.schema(new Schema().required(true));
            }
            instance.properties("field" + index, value);
        }
        return new Fixture(new Blue(new BasicNodeProvider()), instance);
    }

    private Fixture deepInheritedDocument(int depth) {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node root = new Node().name("Deep Type 0")
                .properties("field", new Node().schema(new Schema().required(true)));
        provider.addSingleNodes(root);
        String typeId = provider.getBlueIdByName("Deep Type 0");
        for (int level = 1; level <= depth; level++) {
            Node child = new Node().name("Deep Type " + level)
                    .type(reference(typeId))
                    .properties("field", new Node().schema(new Schema().required(true)));
            provider.addSingleNodes(child);
            typeId = provider.getBlueIdByName("Deep Type " + level);
        }
        Node instance = new Node().type(reference(typeId))
                .properties("field", new Node().value("present"));
        return new Fixture(new Blue(provider), instance);
    }

    private Fixture warmTypedReferenceDocument() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node base = new Node().name("Benchmark Subject");
        provider.addSingleNodes(base);
        String baseId = provider.getBlueIdByName("Benchmark Subject");
        Node holder = new Node().name("Benchmark Holder")
                .properties("subject", new Node().type(reference(baseId))
                        .schema(new Schema().required(true)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Benchmark Holder");
        Node subject = new Node().type(reference(baseId))
                .properties("identifier", new Node().value("benchmark-subject"));
        provider.addSingleNodes(subject);
        Blue blue = new Blue(provider);
        String subjectId = blue.calculateBlueId(blue.preprocess(subject.clone()));
        Node instance = new Node().type(reference(holderId))
                .properties("subject", reference(subjectId));
        return new Fixture(blue, instance);
    }

    private Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture {
        private final Blue blue;
        private final Node instance;

        private Fixture(Blue blue, Node instance) {
            this.blue = blue;
            this.instance = instance;
        }
    }
}
