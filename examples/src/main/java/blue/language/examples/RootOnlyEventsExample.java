package blue.language.examples;

import blue.language.BlueRuntime;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;

/** Demonstrates that only Root-scope application emissions leave PROCESS. */
public final class RootOnlyEventsExample {

    private RootOnlyEventsExample() {
    }

    /**
     * Processes one child delivery and one Root delivery.
     *
     * @return immutable summary of internal and externally visible events
     */
    public static Result run() {
        // tag::root-only-events[]
        Node root = ContractsExampleSupport
                .initializedRootAndChildEmitters();
        Node childEvent = ContractsExampleSupport.event(
                ContractsExampleSupport.CHILD_SCOPE);
        Node rootEvent = ContractsExampleSupport.event(
                ContractsExampleSupport.ROOT_SCOPE);

        try (BlueRuntime runtime = ContractsExampleSupport.runtime(
                new ContractsExampleSupport.RuntimeWorkProcessor())) {
            DocumentProcessingResult childProcessed =
                    runtime.contracts().process(root, childEvent);
            DocumentProcessingResult rootProcessed =
                    runtime.contracts().process(
                            childProcessed.document(), rootEvent);

            ExampleSupport.require(
                    childProcessed.status() == ProcessorStatus.SUCCESS,
                    "The embedded delivery must commit: "
                            + ContractsExampleSupport.diagnostic(
                            childProcessed));
            ExampleSupport.require(
                    childProcessed.events().isEmpty(),
                    "Embedded-scope events must remain internal");
            ExampleSupport.require(
                    rootProcessed.events().size() == 1,
                    "Exactly one Root event must be returned");
            String origin = (String) rootProcessed.events().get(0)
                    .getProperties()
                    .get(ContractsExampleSupport.KEY_ORIGIN_SCOPE)
                    .getValue();
            ExampleSupport.require(
                    ContractsExampleSupport.ROOT_SCOPE.equals(origin),
                    "The public emission must originate at Root");
            return new Result(
                    childProcessed.events().size(),
                    rootProcessed.events().size(),
                    origin);
        }
        // end::root-only-events[]
    }

    /**
     * Runs from a shell and prints the number of returned Root events.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        System.out.println(run().getRootEventCount());
    }

    /** Immutable Root/embedded event visibility result. */
    public static final class Result {
        private final int childEventCount;
        private final int rootEventCount;
        private final String publicEventOrigin;

        private Result(
                int childEventCount,
                int rootEventCount,
                String publicEventOrigin) {
            this.childEventCount = childEventCount;
            this.rootEventCount = rootEventCount;
            this.publicEventOrigin = publicEventOrigin;
        }

        /**
         * Returns the number of child-scope events exposed by PROCESS.
         *
         * @return child-scope event count
         */
        public int getChildEventCount() {
            return childEventCount;
        }

        /**
         * Returns the number of Root-scope events exposed by PROCESS.
         *
         * @return Root-scope event count
         */
        public int getRootEventCount() {
            return rootEventCount;
        }

        /**
         * Returns the scope origin recorded on the public event.
         *
         * @return public event origin path
         */
        public String getPublicEventOrigin() {
            return publicEventOrigin;
        }
    }
}
