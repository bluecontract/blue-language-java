/**
 * Strict JSON and YAML parsing and writing for Blue nodes.
 *
 * <p><strong>Contents.</strong> Text-format selection, Source parsing, exact
 * direct-identity input parsing, and normalized writing belong here.
 * Preprocessing, resolution, identity calculation, and object mapping do not.</p>
 *
 * <p><strong>Entry points.</strong> Applications use
 * {@link blue.language.codec.BlueCodec} with
 * {@link blue.language.codec.BlueFormat};
 * {@link blue.language.codec.StandardBlueCodec} is the standard reusable
 * implementation.</p>
 *
 * <p><strong>Lifecycle.</strong> The standard codec is stateless after
 * construction, thread-safe for concurrent calls, and owns no resources that
 * require closing. Every parse returns a new mutable node graph.</p>
 *
 * <p><strong>Extension.</strong> Alternate transports may implement
 * {@code BlueCodec} without weakening the Blue JSON data model. Node structure
 * is owned by {@code blue.language.model}; semantic preparation is owned by
 * {@code blue.language.preprocess} and {@code blue.language.resolve}.</p>
 */
package blue.language.codec;
