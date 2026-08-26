package blue.language.conformance.contracts;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.HandlerContract;

/**
 * Fixture-only Operation with an exact external invocation selector.
 *
 * <p>The value remains an ordinary {@link HandlerContract}, so selection,
 * executable-body loading, and effect buffering all use the production
 * Contracts path. Its distinct registered type lets fixtures prove Operation
 * routing without depending on a downstream Coordination implementation.</p>
 */
final class MockOperation {

    private MockOperation() {
    }

    /** Public reflection carrier hidden behind this package-private holder. */
    @TypeBlueId(MockTypeBlueIds.MOCK_OPERATION)
    public static final class Value extends HandlerContract {

        private String operationId;
        private Node result;

        /** Creates an empty fixture Operation for mapper population. */
        public Value() {
        }

        /**
         * Returns the optional exact Fixture Event id selector.
         *
         * @return operation id, or {@code null} for Channel-event matching
         */
        public String getOperationId() {
            return operationId;
        }

        /**
         * Sets the optional exact Fixture Event id selector.
         *
         * @param operationId operation id, or {@code null}
         */
        public void setOperationId(String operationId) {
            this.operationId = operationId;
        }

        /**
         * Returns the declared fixture result.
         *
         * @return retained mutable result node, or {@code null}
         */
        public Node getResult() {
            return result;
        }

        /**
         * Sets the declared fixture result.
         *
         * @param result result node retained by reference, or {@code null}
         */
        public void setResult(Node result) {
            this.result = result;
        }
    }
}
