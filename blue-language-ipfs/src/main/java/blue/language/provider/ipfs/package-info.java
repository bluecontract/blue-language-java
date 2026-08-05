/**
 * Adapts IPFS gateway content to the Language node-provider contract.
 *
 * <p><strong>Contents.</strong> This package contains BlueId-to-CID conversion,
 * bounded HTTP retrieval, and strict JSON parsing for IPFS-backed content.
 * Language semantics, caching policy, mutable publication, and application
 * retry orchestration do not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.provider.ipfs.IPFSNodeProvider} supplies read-only node
 * lookup. {@link blue.language.provider.ipfs.BlueIdToCid} exposes address
 * conversion, while
 * {@link blue.language.provider.ipfs.IPFSContentFetcher} is the compatibility
 * gateway client.</p>
 *
 * <p><strong>Lifecycle.</strong> Provider instances retain no open transport;
 * each fetch owns and closes its HTTP resources. The implementation is safe to
 * share for lookup, but callers must treat network availability as transient
 * and must not derive deterministic semantics from timing or reachability.</p>
 *
 * <p><strong>Extension.</strong> Preserve exact address conversion and strict
 * parsing. Alternative gateways, caches, or retry policies should be separate
 * {@link blue.language.provider.NodeProvider} implementations rather than
 * changes to core Language behavior. General provider contracts live in
 * {@link blue.language.provider.NodeProvider}.</p>
 */
package blue.language.provider.ipfs;
