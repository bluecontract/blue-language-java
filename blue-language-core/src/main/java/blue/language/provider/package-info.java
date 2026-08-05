/**
 * Exact content lookup SPI and provider-evidence verification boundary.
 *
 * <p><strong>Contents.</strong> Node lookup outcomes, verified composition,
 * cyclic-set proof, exact graph fragments, and bounded provider wrappers
 * belong here. Language resolution rules and transport-specific networking do not.</p>
 *
 * <p><strong>Entry points.</strong> Implement
 * {@link blue.language.provider.NodeProvider}; compose outcomes with
 * {@link blue.language.provider.SequentialNodeProvider} and verify untrusted
 * leaves with {@link blue.language.provider.VerifyingNodeProvider}.</p>
 *
 * <p><strong>Lifecycle.</strong> The interface borrows provider-owned data.
 * Implementations define their own thread-safety and resource lifecycle;
 * returned nodes are treated as external mutable values and independently
 * verified before semantic use.</p>
 *
 * <p><strong>Extension.</strong> Providers may change storage or transport but
 * must preserve exact BlueId evidence and exhaustive outcomes. IPFS integration
 * lives in {@code blue.language.provider.ipfs}; bootstrap content lives in
 * {@code blue.language.registry}.</p>
 */
package blue.language.provider;
