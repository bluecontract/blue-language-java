/**
 * Exposes executable conformance entry points and immutable result evidence.
 *
 * <p><strong>Contents.</strong> This package contains the closed Blue Language
 * fixture runner, Language and Contracts result models, fixture categories,
 * and release-level report aggregation. Fixture implementation details,
 * command-line I/O, and production Language algorithms do not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.conformance.api.BlueConformanceSuiteRunner} executes
 * the bundled Language suite. Consumers inspect
 * {@link blue.language.conformance.api.BlueConformanceReport},
 * {@link blue.language.conformance.api.BlueContractsConformanceReport}, or
 * {@link blue.language.conformance.api.BlueReleaseConformanceReport}.</p>
 *
 * <p><strong>Lifecycle.</strong> Suite methods are stateless entry points and
 * each run creates invocation-local execution state. Returned reports and
 * failure records defensively own their collections and may be shared across
 * threads.</p>
 *
 * <p><strong>Extension.</strong> The fixture manifest and its package identity
 * define the closed operation vocabulary; unsupported input must fail rather
 * than be skipped. Contracts fixture execution lives in
 * {@link blue.language.conformance.contracts}; command-line publication lives
 * in {@link blue.language.conformance.cli}.</p>
 */
package blue.language.conformance.api;
