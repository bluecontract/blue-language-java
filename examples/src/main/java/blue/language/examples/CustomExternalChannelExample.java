package blue.language.examples;

import blue.language.BlueRuntime;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;

import java.math.BigInteger;

/** Processes an event through a custom External Channel and Handler runtime. */
public final class CustomExternalChannelExample {

    private CustomExternalChannelExample() {
    }

    /** Runs one exact external delivery and returns its committed counter. */
    public static Result run() {
        // tag::custom-external-channel-handler[]
        ContractsExampleSupport.RuntimeWorkProcessor unusedRuntimeWork =
                new ContractsExampleSupport.RuntimeWorkProcessor();
        Node root = ContractsExampleSupport.initializedCounterRoot();
        Node event = ContractsExampleSupport.amountEvent(7L);

        ExampleSupport.require(
                !ContractsExampleSupport.SOURCE_CHANNEL_KEY.equals(
                        ContractsExampleSupport.TARGET_CHANNEL_KEY),
                "The accepting source and Handler target must be distinct");

        try (BlueRuntime runtime = ContractsExampleSupport.runtime(
                unusedRuntimeWork)) {
            DocumentProcessingResult processed =
                    runtime.contracts().process(root, event);

            ExampleSupport.require(
                    processed.status() == ProcessorStatus.SUCCESS,
                    "The custom External Channel delivery must commit: "
                            + ContractsExampleSupport.diagnostic(processed));
            BigInteger counter = (BigInteger) processed.document()
                    .getProperties()
                    .get(ContractsExampleSupport.COUNTER_KEY)
                    .getValue();
            ExampleSupport.require(
                    BigInteger.valueOf(7L).equals(counter),
                    "The custom Handler must apply its buffered patch");
            return new Result(
                    counter,
                    processed.status(),
                    processed.totalGas(),
                    ContractsExampleSupport.SOURCE_CHANNEL_KEY,
                    ContractsExampleSupport.TARGET_CHANNEL_KEY);
        }
        // end::custom-external-channel-handler[]
    }

    /** Runs from a shell and prints the committed counter. */
    public static void main(String[] args) {
        System.out.println(run().getCounter());
    }

    /** Immutable custom-runtime result. */
    public static final class Result {
        private final BigInteger counter;
        private final ProcessorStatus status;
        private final long totalGas;
        private final String sourceChannelKey;
        private final String handlerChannelKey;

        private Result(
                BigInteger counter,
                ProcessorStatus status,
                long totalGas,
                String sourceChannelKey,
                String handlerChannelKey) {
            this.counter = counter;
            this.status = status;
            this.totalGas = totalGas;
            this.sourceChannelKey = sourceChannelKey;
            this.handlerChannelKey = handlerChannelKey;
        }

        public BigInteger getCounter() {
            return counter;
        }

        public ProcessorStatus getStatus() {
            return status;
        }

        public long getTotalGas() {
            return totalGas;
        }

        public String getSourceChannelKey() {
            return sourceChannelKey;
        }

        public String getHandlerChannelKey() {
            return handlerChannelKey;
        }
    }
}
