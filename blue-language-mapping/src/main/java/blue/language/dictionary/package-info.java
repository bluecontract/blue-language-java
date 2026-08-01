/**
 * Describes named, version-aware dictionaries of Blue types.
 *
 * <p><strong>Contents.</strong> This package contains dictionary contracts,
 * their explicit registry, and export support for translating a node graph
 * through a selected dictionary. Java reflection mapping and Language-level
 * identity or reference semantics do not belong here.</p>
 *
 * <p><strong>Entry points.</strong> Implement
 * {@link blue.language.dictionary.TypeDictionary}, register instances in
 * {@link blue.language.dictionary.DictionaryRegistry}, and export through
 * {@link blue.language.dictionary.DictionaryAwareExporter} with an explicit
 * {@link blue.language.dictionary.ExportContext}.</p>
 *
 * <p><strong>Lifecycle.</strong> A registry is mutable during configuration
 * and is not intended for concurrent mutation. Its read APIs return snapshots;
 * callers should finish registration before sharing a registry. Export
 * contexts are operation-scoped.</p>
 *
 * <p><strong>Extension.</strong> Dictionaries must use stable names, exact
 * BlueIds, deterministic aliases, and side-effect-free export rules. Use
 * {@link blue.language.mapping.BlueMapper} for Java-object materialization and
 * {@link blue.language.model.Node} for the values being exported.</p>
 */
package blue.language.dictionary;
