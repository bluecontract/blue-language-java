/**
 * Executes the closed Blue Contracts 1.0 conformance fixture package.
 *
 * <p><strong>Contents.</strong> This package owns fixture validation,
 * deterministic assertion evaluation, scripted processors and external
 * channels, gas-schedule checks, and projection catalogs used only by the
 * bundled suite. Production contract implementations and host integrations do
 * not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.conformance.contracts.ContractsConformanceSuite}
 * validates package integrity and executes every manifest fixture. Public
 * callers normally consume its immutable
 * {@link blue.language.conformance.api.BlueContractsConformanceReport}.</p>
 *
 * <p><strong>Lifecycle.</strong> A suite run owns fresh harness and scripted
 * runtime state for the invocation. Fixture doubles are not application
 * services and must not escape into production; the returned report may be
 * retained and shared.</p>
 *
 * <p><strong>Extension.</strong> New fixtures require manifest inventory,
 * package-identity, vector, gas-coverage, and assertion-vocabulary updates.
 * Unknown or malformed fixture data must fail closed. Public result contracts
 * live in {@link blue.language.conformance.api}; thin runner delegation lives
 * in {@link blue.language.conformance.runner}.</p>
 */
package blue.language.conformance.contracts;
