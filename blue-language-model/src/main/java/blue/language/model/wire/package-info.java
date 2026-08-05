/**
 * Stable wire vocabulary and JSON Pointer values for the Blue Language model.
 *
 * <p><strong>Contents.</strong> Protocol field names, released core-type
 * identities, schema keyword names, and parsed pointer values belong here.
 * Semantic resolution, hashing, I/O codecs, and mutable runtime caches do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.model.wire.BlueLanguageConstants},
 * {@link blue.language.model.wire.SchemaPropertyConstants},
 * {@link blue.language.model.wire.JsonPointer}, and
 * {@link blue.language.model.wire.ParsedJsonPointer} expose the wire contract.</p>
 *
 * <p><strong>Lifecycle.</strong> Constants and pointer operations are stateless;
 * parsed pointers are immutable and thread-safe. No type owns external
 * resources or requires closing.</p>
 *
 * <p><strong>Extension.</strong> Wire names and released identities are closed
 * protocol values and must not be extended ad hoc. Neighboring
 * {@code blue.language.model} owns node structures, and
 * {@code blue.language.codec} owns text parsing and writing.</p>
 */
package blue.language.model.wire;
