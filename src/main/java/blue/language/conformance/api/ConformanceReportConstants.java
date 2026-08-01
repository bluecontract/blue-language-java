package blue.language.conformance.api;

import blue.language.utils.Properties;

/**
 * Stable wire vocabulary and fixture cardinalities shared by conformance
 * reports.
 *
 * <p>Field names, categorical values, and schema identifiers in this class are
 * consumed by release tooling. Changes therefore require an explicit report
 * schema decision.</p>
 */
final class ConformanceReportConstants {

    /** Specification version shared by the final Language and Contracts reports. */
    static final String SPECIFICATION_VERSION_1_0 = "1.0";

    private ConformanceReportConstants() {
    }

    /** Machine-readable report field names. */
    static final class Field {
        static final String SCHEMA = Properties.OBJECT_SCHEMA;
        static final String ID = "id";
        static final String NAME = "name";
        static final String CATEGORY = "category";
        static final String OPERATION = "operation";
        static final String STATUS = "status";
        static final String ERROR_CATEGORY = "errorCategory";
        static final String EXCEPTION_CLASS = "exceptionClass";
        static final String MESSAGE = "message";
        static final String PATH = "path";
        static final String ROLE = "role";
        static final String VECTORS = "vectors";
        static final String FAILURE = "failure";
        static final String RESULT_KEY = "resultKey";
        static final String SUITE = "suite";
        static final String SPECIFICATION_VERSION = "specificationVersion";
        static final String SPECIFICATION_SHA256 = "specificationSha256";
        static final String REGISTRY_PACKAGE_IDENTITY =
                "registryPackageIdentity";
        static final String GAS_PACKAGE_IDENTITY = "gasPackageIdentity";
        static final String FIXTURE_PACKAGE_IDENTITY =
                "fixturePackageIdentity";
        static final String PACKAGE_IDENTITY = "packageIdentity";
        static final String CORE_REGISTRY_BLUE_IDS = "coreRegistryBlueIds";
        static final String FIXTURE_COUNT = "fixtureCount";
        static final String PASSED_COUNT = "passedCount";
        static final String FAILED_COUNT = "failedCount";
        static final String RESULTS = "results";
        static final String RELEASE = "release";
        static final String LANGUAGE = "language";
        static final String CONTRACTS = Properties.OBJECT_CONTRACTS;
        static final String PACKAGES = "packages";
        static final String SPECIFICATIONS = "specifications";
        static final String SUMMARY = "summary";
        static final String FIXTURES = "fixtures";
        static final String TOTAL = "total";
        static final String PASSED = "passed";
        static final String FAILED = "failed";
        static final String SKIPPED = "skipped";
        static final String CONFORMANT = "conformant";
        static final String LANGUAGE_REGISTRY = "languageRegistry";
        static final String LANGUAGE_FIXTURES = "languageFixtures";
        static final String CONTRACTS_REGISTRY = "contractsRegistry";
        static final String CONTRACTS_GAS = "contractsGas";
        static final String CONTRACTS_FIXTURES = "contractsFixtures";
        static final String LANGUAGE_SHA256 = "languageSha256";
        static final String CONTRACTS_SHA256 = "contractsSha256";

        private Field() {
        }
    }

    /** Versioned conformance-report schema identifiers. */
    static final class Schema {
        static final String CONTRACTS =
                "blue-contracts-conformance-report/1.0";
        static final String RELEASE =
                "blue-language-java-release-conformance-report/1.0";

        private Schema() {
        }
    }

    /** Fixture execution status values. */
    static final class Status {
        static final String PASS = "PASS";
        static final String FAIL = "FAIL";

        private Status() {
        }
    }

    /** Stable failure categories emitted directly by report harnesses. */
    static final class ErrorCategory {
        static final String HARNESS_DID_NOT_RUN_FIXTURE =
                "HarnessDidNotRunFixture";

        private ErrorCategory() {
        }
    }

    /** Suite discriminators in a combined release report. */
    static final class Suite {
        static final String LANGUAGE = Field.LANGUAGE;
        static final String CONTRACTS = Field.CONTRACTS;

        private Suite() {
        }
    }

    /** Normative fixture subtotals not exposed by the combined report API. */
    static final class FixtureCount {
        static final int CONTRACTS_BEHAVIOR = 82;
        static final int CONTRACTS_GAS = 58;

        private FixtureCount() {
        }
    }
}
