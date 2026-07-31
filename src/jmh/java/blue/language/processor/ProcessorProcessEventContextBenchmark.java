package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.model.SetProperty;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Baseline benchmark for lazy Processing Event snapshots.
 *
 * <p>Run {@code ./gradlew jmh}. JMH writes JSON results, including JVM and
 * execution environment metadata, to
 * {@code build/reports/jmh/processor-process-event-context.json}. The two
 * shape values intentionally remain separate because no unmeasured complexity
 * claim is made for snapshot construction. Used and unused variants execute
 * the same handler path and differ only in whether that handler reads the
 * Processing Event.</p>
 */
@State(Scope.Benchmark)
public class ProcessorProcessEventContextBenchmark {

    private static final String TEST_EVENT_TYPE = ProcessorTestTypeBlueIds.TEST_EVENT;
    private static final String TEST_EVENT_CHANNEL_TYPE =
            ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL;
    private static final String SET_PROPERTY_TYPE = ProcessorTestTypeBlueIds.SET_PROPERTY;

    @Param({"wide", "deep"})
    public String shape;

    @Param({"unused", "used"})
    public String access;

    private DocumentProcessor processor;
    private Node initializedDocument;
    private blue.language.snapshot.ResolvedSnapshot initializedSnapshot;
    private Node event;

    @Setup(Level.Trial)
    public void setUp() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new SnapshotAccessHandler("used".equals(access)));
        processor = blue.getDocumentProcessor();
        DocumentProcessingResult initialized = blue.initializeDocument(blue.yamlToNode(documentYaml()));
        initializedDocument = initialized.document();
        initializedSnapshot = blue.loadSnapshot(initializedDocument);
        event = "wide".equals(shape) ? wideEvent() : deepEvent();
    }

    @Benchmark
    public DocumentProcessingResult processNode() {
        return processor.processDocument(initializedDocument, event);
    }

    @Benchmark
    public DocumentProcessingResult processResolvedSnapshot() {
        return processor.processDocument(initializedSnapshot, event);
    }

    private String documentYaml() {
        String handler = "  accessProcessEvent:\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: " + SET_PROPERTY_TYPE + "\n";
        return "name: Processing Event Benchmark\n" +
                "contracts:\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: " + TEST_EVENT_CHANNEL_TYPE + "\n" +
                handler;
    }

    private Node wideEvent() {
        Node result = event("wide");
        for (int index = 0; index < 256; index++) {
            result.properties("field" + index, new Node().value(index));
        }
        return result;
    }

    private Node deepEvent() {
        Node result = event("deep");
        Node cursor = result;
        for (int index = 0; index < 128; index++) {
            Node child = new Node();
            cursor.properties("next", child);
            cursor = child;
        }
        cursor.properties("leaf", new Node().value("end"));
        return result;
    }

    private Node event(String kind) {
        return new Node()
                .type(new Node().blueId(TEST_EVENT_TYPE))
                .properties("eventId", new Node().value("benchmark-" + kind))
                .properties("kind", new Node().value(kind));
    }

    public static final class SnapshotAccessHandler implements HandlerProcessor<SetProperty> {
        private final boolean readProcessEvent;

        SnapshotAccessHandler(boolean readProcessEvent) {
            this.readProcessEvent = readProcessEvent;
        }

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            if (readProcessEvent) {
                context.frozenProcessEvent();
            }
        }
    }
}
