/**
 * Defines the Java data models consumed by the Contracts processor kernel.
 *
 * <p><strong>Contents.</strong> This package contains contract declarations,
 * channels, markers, document updates, checkpoints, and patch payload models.
 * Dispatch, mutation planning, gas accounting, identity calculation, and host
 * persistence do not belong in these representation classes.</p>
 *
 * <p><strong>Entry points.</strong> Contract families derive from
 * {@link blue.language.processor.model.Contract}, with channel and handler
 * specializations rooted at
 * {@link blue.language.processor.model.ChannelContract} and
 * {@link blue.language.processor.model.HandlerContract}. Processor-owned
 * payloads include {@link blue.language.processor.model.DocumentUpdate} and
 * {@link blue.language.processor.model.JsonPatch}.</p>
 *
 * <p><strong>Lifecycle.</strong> These are mutable mapping and loader models,
 * generally created for one load or processing invocation. They are not
 * thread-safe and must not be published as immutable snapshots without an
 * explicit defensive conversion.</p>
 *
 * <p><strong>Extension.</strong> New models require a stable specification
 * identity and an explicitly registered processor; model classes must not
 * perform I/O or observe ambient state. Dispatch and execution extensions live
 * in {@link blue.language.processor}; published built-in identities live in
 * {@link blue.language.processor.registry}.</p>
 */
package blue.language.processor.model;
