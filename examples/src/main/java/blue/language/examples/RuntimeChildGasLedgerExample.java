package blue.language.examples;

import blue.language.BlueRuntime;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;

/** Accounts for hosted-runtime work in an invocation-owned child gas ledger. */
public final class RuntimeChildGasLedgerExample {

    private static final long WORK_UNITS = 3L;
    private static final long EXPECTED_CHILD_GAS = 21L;

    private RuntimeChildGasLedgerExample() {
    }

    /**
     * Runs deterministic hosted work and returns child and total gas.
     *
     * @return immutable child-ledger and PROCESS gas totals
     */
    public static Result run() {
        // tag::runtime-child-gas-ledger[]
        ContractsExampleSupport.RuntimeWorkProcessor runtimeWork =
                new ContractsExampleSupport.RuntimeWorkProcessor();
        Node root = ContractsExampleSupport.initializedRuntimeWorkRoot(
                WORK_UNITS);
        Node event = ContractsExampleSupport.event(
                ContractsExampleSupport.ROOT_SCOPE);

        try (BlueRuntime runtime = ContractsExampleSupport.runtime(
                runtimeWork)) {
            DocumentProcessingResult processed =
                    runtime.contracts().process(root, event);
            long childGas = runtimeWork.lastChildGas();

            ExampleSupport.require(
                    processed.status() == ProcessorStatus.SUCCESS,
                    "The runtime work delivery must commit: "
                            + ContractsExampleSupport.diagnostic(processed));
            ExampleSupport.require(
                    childGas == EXPECTED_CHILD_GAS,
                    "Three units at weight seven must cost 21 gas");
            ExampleSupport.require(
                    processed.totalGas() >= childGas,
                    "PROCESS total gas must include submitted child gas");
            return new Result(childGas, processed.totalGas());
        }
        // end::runtime-child-gas-ledger[]
    }

    /**
     * Runs from a shell and prints the exact runtime child subtotal.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        System.out.println(run().getChildGas());
    }

    /** Immutable gas-accounting result. */
    public static final class Result {
        private final long childGas;
        private final long processGas;

        private Result(long childGas, long processGas) {
            this.childGas = childGas;
            this.processGas = processGas;
        }

        /**
         * Returns the gas submitted from the hosted-runtime child ledger.
         *
         * @return child gas subtotal
         */
        public long getChildGas() {
            return childGas;
        }

        /**
         * Returns total gas charged for the PROCESS invocation.
         *
         * @return PROCESS gas total
         */
        public long getProcessGas() {
            return processGas;
        }
    }
}
