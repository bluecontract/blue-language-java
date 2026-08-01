package blue.language.examples;

import blue.language.BlueRuntime;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.provider.NodeProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Processes pure-reference Root/event inputs backed by exact provider fragments. */
public final class PureReferenceFragmentsExample {

    private PureReferenceFragmentsExample() {
    }

    /** Runs fragmented processing and reports every exact fetched identity. */
    public static Result run() {
        // tag::pure-reference-fragments[]
        Node fragmentedRoot = ContractsExampleSupport
                .initializedCounterRoot();
        Node handler = fragmentedRoot.getContracts()
                .getProperties().get(
                        ContractsExampleSupport.ADD_HANDLER_KEY);
        String handlerBlueId = ContractsExampleSupport.blueId(handler);
        fragmentedRoot.getContracts().getProperties().put(
                ContractsExampleSupport.ADD_HANDLER_KEY,
                ContractsExampleSupport.reference(handlerBlueId));

        Node fragmentedEvent = ContractsExampleSupport.amountEvent(5L);
        String rootBlueId = ContractsExampleSupport.blueId(fragmentedRoot);
        String eventBlueId = ContractsExampleSupport.blueId(fragmentedEvent);
        Map<String, Node> exactFragments = new LinkedHashMap<>();
        exactFragments.put(rootBlueId, fragmentedRoot);
        exactFragments.put(eventBlueId, fragmentedEvent);
        exactFragments.put(handlerBlueId, handler);
        List<String> requestedBlueIds = new ArrayList<>();
        NodeProvider provider = blueId -> {
            requestedBlueIds.add(blueId);
            Node exact = exactFragments.get(blueId);
            return exact != null
                    ? Collections.singletonList(exact.clone())
                    : null;
        };

        try (BlueRuntime runtime = ContractsExampleSupport.runtime(
                provider,
                new ContractsExampleSupport.RuntimeWorkProcessor())) {
            DocumentProcessingResult processed =
                    runtime.contracts().process(
                            ContractsExampleSupport.reference(rootBlueId),
                            ContractsExampleSupport.reference(eventBlueId));

            ExampleSupport.require(
                    processed.status() == ProcessorStatus.SUCCESS,
                    "Pure-reference processing must commit: "
                            + ContractsExampleSupport.diagnostic(processed));
            BigInteger counter = (BigInteger) processed.document()
                    .getProperties()
                    .get(ContractsExampleSupport.COUNTER_KEY)
                    .getValue();
            ExampleSupport.require(
                    BigInteger.valueOf(5L).equals(counter),
                    "The selected Handler fragment must update Root");
            ExampleSupport.require(
                    requestedBlueIds.contains(handlerBlueId),
                    "The selected Handler fragment must be fetched");
            return new Result(
                    rootBlueId,
                    eventBlueId,
                    counter,
                    requestedBlueIds);
        }
        // end::pure-reference-fragments[]
    }

    /** Runs from a shell and prints the committed counter. */
    public static void main(String[] args) {
        System.out.println(run().getCounter());
    }

    /** Immutable fragmented-processing result. */
    public static final class Result {
        private final String rootBlueId;
        private final String eventBlueId;
        private final BigInteger counter;
        private final List<String> requestedBlueIds;

        private Result(
                String rootBlueId,
                String eventBlueId,
                BigInteger counter,
                List<String> requestedBlueIds) {
            this.rootBlueId = rootBlueId;
            this.eventBlueId = eventBlueId;
            this.counter = counter;
            this.requestedBlueIds = Collections.unmodifiableList(
                    new ArrayList<>(requestedBlueIds));
        }

        public String getRootBlueId() {
            return rootBlueId;
        }

        public String getEventBlueId() {
            return eventBlueId;
        }

        public BigInteger getCounter() {
            return counter;
        }

        public List<String> getRequestedBlueIds() {
            return requestedBlueIds;
        }
    }
}
