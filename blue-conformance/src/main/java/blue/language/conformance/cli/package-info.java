/**
 * Provides the strict command-line boundary for release conformance.
 *
 * <p><strong>Contents.</strong> This package owns argument handling, execution
 * of the closed release suites, report-file output, and process failure on
 * non-conformance. Fixture semantics, reusable report models, and application
 * logging frameworks do not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.conformance.cli.ReleaseConformanceCli} runs both the
 * Language 1.0 and Contracts 1.0 suites and writes JSON plus human-readable
 * evidence.</p>
 *
 * <p><strong>Lifecycle.</strong> The CLI is process-scoped and stateless; each
 * invocation owns its output paths and suite execution. It creates parent
 * directories as needed and retains no background resource after completion.</p>
 *
 * <p><strong>Extension.</strong> Preserve stable exit behavior and
 * machine-readable report fields. Reusable report types belong in
 * {@link blue.language.conformance.api}, and fixture execution belongs in the
 * API or Contracts suite rather than in command-line code.</p>
 */
package blue.language.conformance.cli;
