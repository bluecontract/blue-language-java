package blue.language.conformance.contracts;

import blue.language.processor.util.ProcessorContractConstants;
import blue.language.model.wire.SchemaPropertyConstants;

/**
 * Stable vocabulary of the bundled Contracts 1.0 conformance fixture format.
 *
 * <p>The fixture validator, gas evaluator, assertion evaluator, and execution
 * harness all consume the same closed DSL. Keeping its wire names here avoids
 * accidental spelling drift between validation and execution.</p>
 */
final class ContractsFixtureConstants {

    /** JSON field names shared by the Contracts fixture components. */
    static final class Field {
        static final String ID = "id";
        static final String VECTORS = "vectors";
        static final String CATEGORY = "category";
        static final String DESCRIPTION = "description";
        static final String OPERATION = "operation";
        static final String INPUT = "input";
        static final String EXPECTED = "expected";
        static final String ASSERTIONS = "assertions";
        static final String ROOT = "root";
        static final String EVENT = "event";
        static final String FEEDER = "feeder";
        static final String PROVIDER = "provider";
        static final String RUNTIME = "runtime";
        static final String BUILDERS = "builders";
        static final String VARIANTS = "variants";
        static final String TYPE_REGISTRY_MANIFEST =
                "typeRegistryManifest";
        static final String HANDLERS = "handlers";
        static final String RESULT = "result";
        static final String PATCHES = "patches";
        static final String EVENTS = "events";
        static final String TERMINATION = "termination";
        static final String FAIL = "fail";
        static final String RUNTIME_COUNTERS = "runtimeCounters";
        static final String EVENT_ORDER_KEY = "eventOrderKey";
        static final String DELIVERY_SNAPSHOT = "deliverySnapshot";
        static final String SCOPE_PATH = "scopePath";
        static final String CHANNEL_KEY = "channelKey";
        static final String ORDER = "order";
        static final String ACTIVATION_START_EXCLUSIVE =
                "activationStartExclusive";
        static final String NAMESPACE = "namespace";
        static final String COUNTER = "counter";
        static final String QUANTITY = "quantity";
        static final String WEIGHT_MANIFEST = "weightManifest";
        static final String OLD_LENGTH = "oldLength";
        static final String LIMIT = "limit";
        static final String CHARGES = "charges";
        static final String TEXT_CODE_POINTS_EXAMINED =
                "textCodePointsExamined";
        static final String PROOF_KEY = "proofKey";
        static final String USES = "uses";
        static final String DIRECT_CANONICAL_BYTES =
                "directCanonicalBytes";
        static final String LEFT_LIMBS = "leftLimbs";
        static final String RIGHT_LIMBS = "rightLimbs";
        static final String REPLACE_INDEX = "replaceIndex";
        static final String PRIOR_EXACT_IDENTITY =
                "priorExactIdentity";
        static final String APPEND = "append";
        static final String NAME = "name";
        static final String ROOT_FORM = "rootForm";
        static final String CACHE = "cache";
        static final String BATCHING = "batching";
        static final String ACCEPT = "accept";
        static final String SAME_EVENT = "sameEvent";
        static final String ROOT_REVISION = "rootRevision";
        static final String LIST_OPERATION = "listOperation";
        static final String ACTUAL = "actual";
        static final String OP = "op";
        static final String SIZE = "size";
        static final String DELTA = "delta";
        static final String INDEX = "index";
        static final String EXPECTED_PROJECTION =
                "expectedProjection";
        static final String VARIANT = "variant";
        static final String ORDERED = "ordered";
        static final String TRACE = "trace";
        static final String TOTAL_GAS = "totalGas";
        static final String LIST_FOLD_STEP_RECOMPUTED =
                "listFoldStepRecomputed";
        static final String ADMITTED = "admitted";
        static final String FAILED_CHARGE_ABSENT =
                "failedChargeAbsent";
        static final String TEXT_BLOCK_EXAMINED =
                "textBlockExamined";
        static final String VALIDATION_PROOF_REUSED =
                "validationProofReused";
        static final String DIRECT_IDENTITY_HASH_BLOCK =
                "directIdentityHashBlock";
        static final String INTEGER_LIMB_OPERATION =
                "integerLimbOperation";
        static final String SEQUENCE = "sequence";
        static final String WEIGHT = "weight";
        static final String SUBTOTAL = "subtotal";
        static final String CONTRACT_KEY = "contractKey";
        static final String LOGICAL_PATH = "logicalPath";
        static final String REASON = "reason";

        private Field() {
        }
    }

    /** Top-level operations accepted by the closed fixture envelope. */
    static final class Operation {
        static final String PROCESS = "process";
        static final String PROCESS_ATTEMPT = "process-attempt";
        static final String PLATFORM = "platform";
        static final String GAS_MICRO = "gas-micro";

        private Operation() {
        }
    }

    /** Operators accepted by one fixture assertion. */
    static final class AssertionOperator {
        static final String EQUALS = "equals";
        static final String NOT_EQUALS = "notEquals";
        static final String EQUALS_PROJECTION =
                "equalsProjection";
        static final String ABSENT = "absent";
        static final String PRESENT = "present";
        static final String SEQUENCE_EQUALS = "sequenceEquals";
        static final String CONTAINS = "contains";
        static final String NOT_CONTAINS = "notContains";
        static final String LESS_THAN = "lessThan";
        static final String GREATER_THAN = "greaterThan";
        static final String SAME_ACROSS_VARIANTS =
                "sameAcrossVariants";
        static final String FAILS_WITH = "failsWith";
        static final String ALL = "all";
        static final String NONE = "none";

        private AssertionOperator() {
        }
    }

    /** Integer operations selected by standalone gas microfixtures. */
    static final class IntegerOperation {
        static final String MULTIPLY = "multiply";
        static final String DIVISION = "division";
        static final String REMAINDER = "remainder";
        static final String GCD = "gcd";
        static final String MULTIPLE_OF =
                SchemaPropertyConstants.KEY_MULTIPLE_OF;
        static final String ADD = "add";
        static final String SUBTRACT = "subtract";
        static final String EQUALS = "equals";
        static final String ORDER = "order";
        static final String LCM = "lcm";

        private IntegerOperation() {
        }
    }

    /** Runtime gas-ledger namespaces accepted by fixture-only controls. */
    static final class RuntimeNamespace {
        static final String RUNTIME = Field.RUNTIME;

        private RuntimeNamespace() {
        }
    }

    /** Peer-channel dependency modes accepted by fixture channels. */
    static final class DependencyMode {
        static final String NONE = AssertionOperator.NONE;
        static final String EXACT = "exact";
        static final String CATALOG = "catalog";

        private DependencyMode() {
        }
    }

    /** Fixture-channel fields that declare peer-channel dependencies. */
    static final class DependencyField {
        static final String MODE = "dependencyMode";
        static final String CHANNEL_KEY = "dependentChannelKey";

        private DependencyField() {
        }
    }

    /** Variant selectors accepted by cross-variant assertions. */
    static final class VariantSelector {
        static final String ALL = AssertionOperator.ALL;

        private VariantSelector() {
        }
    }

    /** Operations accepted by the list-identity variant control. */
    static final class ListOperation {
        static final String APPEND = Field.APPEND;
        static final String REPLACE = "replace";

        private ListOperation() {
        }
    }

    /** Operations accepted by scripted JSON patches. */
    static final class PatchOperation {
        static final String ADD = IntegerOperation.ADD;
        static final String REPLACE = ListOperation.REPLACE;
        static final String REMOVE = "remove";

        private PatchOperation() {
        }
    }

    /** Wire fields used by scripted JSON patches. */
    static final class PatchField {
        static final String OPERATION = Field.OP;
        static final String PATH = ProcessorContractConstants.KEY_PATH;
        static final String VALUE = "val";

        private PatchField() {
        }
    }

    /** Stable sentinel values projected by the fixture harness. */
    static final class ProjectionValue {
        static final String RETRY_MATCHES_ORIGINAL_TRACE = Field.TRACE;

        private ProjectionValue() {
        }
    }

    /** Projection paths written and consumed by gas fixture components. */
    static final class Projection {
        static final String GAS_TRACE = "__gas.trace";
        static final String GAS_TOTAL = "__gas.totalGas";
        static final String GAS_ADMITTED = "__gas.admitted";
        static final String GAS_FAILED_CHARGE_ABSENT =
                "__gas.failedChargeAbsent";
        static final String GAS_LIST_FOLD_STEP_RECOMPUTED =
                "__gas.listFoldStepRecomputed";
        static final String GAS_TEXT_BLOCK_EXAMINED =
                "__gas.textBlockExamined";
        static final String GAS_VALIDATION_PROOF_REUSED =
                "__gas.validationProofReused";
        static final String GAS_DIRECT_IDENTITY_HASH_BLOCK =
                "__gas.directIdentityHashBlock";
        static final String GAS_INTEGER_LIMB_OPERATION =
                "__gas.integerLimbOperation";
        static final String TRACE_NAMED_ENTRIES =
                "trace.namedEntries";
        static final String MANIFEST_COUNTER_COVERAGE_COMPLETE =
                "manifest.counterCoverage.complete";

        private Projection() {
        }
    }

    private ContractsFixtureConstants() {
    }
}
