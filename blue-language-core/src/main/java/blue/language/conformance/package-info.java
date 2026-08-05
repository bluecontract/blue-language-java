/**
 * Language conformance planning and canonical generalization operations.
 *
 * <p><strong>Contents.</strong> Deterministic conformance plans, immutable
 * results, and canonical generalization patches belong here. Fixture loading,
 * CLI reporting, and Contracts conformance harnesses do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.conformance.ConformanceEngine} evaluates and applies
 * plans represented by {@link blue.language.conformance.ConformancePlan} and
 * {@link blue.language.conformance.ConformanceResult}.</p>
 *
 * <p><strong>Lifecycle.</strong> Plans and results are immutable. An engine
 * owns bounded derived state, is reusable for its configured environment, and
 * must be closed when that environment is released.</p>
 *
 * <p><strong>Extension.</strong> Language conformance semantics are closed;
 * new fixtures belong in {@code blue.language.conformance.api}. Matching and
 * immutable patch mechanics live in {@code blue.language.matching} and
 * {@code blue.language.snapshot}.</p>
 */
package blue.language.conformance;
