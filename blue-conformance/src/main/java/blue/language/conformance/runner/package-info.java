/**
 * Supplies narrow public runner adapters for conformance suites.
 *
 * <p><strong>Contents.</strong> This package contains stable delegation entry
 * points used by build and release tooling. Fixture engines, report models,
 * serialization, and command-line file handling do not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.conformance.runner.BlueContractsConformanceSuiteRunner}
 * delegates complete execution and inventory inspection to the closed
 * Contracts suite.</p>
 *
 * <p><strong>Lifecycle.</strong> Runners are stateless and invocation-scoped;
 * they own no caches, threads, files, or closeable resources. Their returned
 * reports are immutable evidence values.</p>
 *
 * <p><strong>Extension.</strong> Keep adapters thin and deterministic. Add
 * fixture behavior to {@link blue.language.conformance.contracts}, report
 * contracts to {@link blue.language.conformance.api}, and process-level output
 * to {@link blue.language.conformance.cli}.</p>
 */
package blue.language.conformance.runner;
