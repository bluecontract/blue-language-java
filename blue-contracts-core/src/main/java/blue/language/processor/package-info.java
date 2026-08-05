/**
 * Executes the deterministic, runtime-neutral Blue Contracts processor.
 *
 * <p><strong>Contents.</strong> This package owns processing orchestration,
 * contract dispatch, gas accounting, snapshots, diagnostics, observations,
 * subscription validation, and external-delivery evidence. Concrete business
 * contract implementations, host persistence, networking, and wall-clock or
 * random inputs do not belong in the kernel.</p>
 *
 * <p><strong>Entry points.</strong> Applications should compose
 * {@link blue.language.processor.BlueContracts}; lower-level hosts can build a
 * {@link blue.language.processor.DocumentProcessor}. Results are returned as
 * {@link blue.language.processor.DocumentProcessingResult},
 * {@link blue.language.processor.ProcessAttemptResult}, or
 * {@link blue.language.processor.PlatformProcessingResult}.</p>
 *
 * <p><strong>Lifecycle.</strong> {@code BlueContracts} and
 * {@code DocumentProcessor} are thread-safe, closeable service owners. Close
 * them after admitted work completes. Execution contexts and working documents
 * are invocation-scoped and must not escape or be shared between calls;
 * immutable result and trace values may be retained.</p>
 *
 * <p><strong>Extension.</strong> Register only exact, evidenced type identities
 * through {@link blue.language.processor.ContractProcessor},
 * {@link blue.language.processor.ChannelProcessor}, and
 * {@link blue.language.processor.HandlerProcessor}. Host integrations belong
 * behind the published evidence, snapshot, validation, and observation SPIs.
 * Contract data models live in {@link blue.language.processor.model}; verified
 * built-in identities live in {@link blue.language.processor.registry}.</p>
 *
 * <p><strong>Embedded scopes.</strong> One invocation-local immutable plan
 * freezes exact {@code Process Embedded.paths}, collection declarations,
 * enumerated stable member keys, concrete child paths, and provenance before
 * processing begins. Discovery, delivery, mutation boundaries, lifecycle,
 * checkpoints, fragmentation inspection, and post-commit subscription deltas
 * consume that same plan so an event cannot observe membership it created.
 * Hosts may inspect the read-only public projection through
 * {@link blue.language.processor.EmbeddedScopePlanView}; it contains no
 * executable behavior and consumes no Contracts gas.</p>
 *
 * <p><strong>Managed-host evidence.</strong> A host that persists Root
 * revisions and external subscription intervals should obtain
 * {@link blue.language.processor.SubscriptionSurfaceProjection} and
 * {@link blue.language.processor.IndexedDeliveryEvaluator} from its configured
 * Contracts service or processor administration view. A custom processor can
 * import the same verified Language generation through
 * {@link blue.language.processor.ProcessorRuntimeAccess}; none of these
 * boundaries exposes mutable loaders, registries, caches, or matcher
 * sessions.</p>
 */
package blue.language.processor;
