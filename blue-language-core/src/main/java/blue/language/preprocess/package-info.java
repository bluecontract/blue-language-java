/**
 * Deterministic Source-to-Preprocessed-Document preparation.
 *
 * <p><strong>Contents.</strong> Root {@code blue} directive resolution,
 * imports, ordered transformations, and mandatory baseline normalization
 * belong here. Type resolution, canonicalization, and provider transport do not.</p>
 *
 * <p><strong>Entry points.</strong> Applications use
 * {@link blue.language.preprocess.BluePreprocessing} or
 * {@link blue.language.preprocess.Preprocessor}. Host transformations implement
 * {@link blue.language.preprocess.TransformationProcessor} and are selected by
 * {@link blue.language.preprocess.TransformationProcessorProvider}.</p>
 *
 * <p><strong>Lifecycle.</strong> A configured preprocessor is reusable when its
 * borrowed provider and processors are thread-safe. Each call clones Source
 * input and builds invocation-local plans; no close operation is owned here.</p>
 *
 * <p><strong>Extension.</strong> A transformation must be registered by an
 * exact verified type BlueId and remain deterministic. Core baseline stages
 * are closed Language behavior. Providers neighbor this package in
 * {@code blue.language.provider}; resolution follows in
 * {@code blue.language.resolve}.</p>
 */
package blue.language.preprocess;
