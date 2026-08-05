/**
 * Provides the strict Jackson configuration used at Language codec boundaries.
 *
 * <p><strong>Contents.</strong> This package contains the checked-to-unchecked
 * mapper adapter and shared JSON/YAML configurations that preserve numeric
 * identity, reject duplicate keys, and reject unsupported YAML constructs.
 * Language preprocessing, resolution, identity formulas, and general-purpose
 * application serialization do not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.codec.jackson.UncheckedObjectMapper#JSON_MAPPER} and
 * {@link blue.language.codec.jackson.UncheckedObjectMapper#YAML_MAPPER} are the
 * strict advanced-support mappers. Normal application parsing and writing
 * should use {@link blue.language.codec.BlueCodec}.</p>
 *
 * <p><strong>Lifecycle.</strong> Jackson mappers are mutable while configured
 * and thread-safe only after configuration is complete. The shared instances
 * are process-lifetime values: do not register modules or change features
 * after publishing them to concurrent callers.</p>
 *
 * <p><strong>Extension.</strong> Custom mapper variants must retain duplicate-
 * key detection, exact numeric nodes, Blue serializers, and YAML restrictions.
 * New wire semantics belong in {@link blue.language.model.Node} and public
 * format behavior belongs in {@link blue.language.codec.BlueCodec}, not in ad
 * hoc mapper customization.</p>
 */
package blue.language.codec.jackson;
