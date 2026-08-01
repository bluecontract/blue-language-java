/**
 * Implements the single normative BlueId identity system.
 *
 * <p><strong>Contents.</strong> This package owns direct identity-input
 * validation, canonical identity reconstruction, RFC 8785 hashing, list-fold
 * identity, scalar and object encoding, Source Document identity, and cyclic
 * set calculation. Resolution, minimization, transport, and storage metadata
 * do not form part of these algorithms.</p>
 *
 * <p><strong>Entry points.</strong> Applications use
 * {@link blue.language.identity.BlueIdentity}. Focused integrations can use
 * {@link blue.language.identity.DirectBlueIdCalculator},
 * {@link blue.language.identity.SourceDocumentBlueIdCalculator},
 * {@link blue.language.identity.CircularSetIdentityCalculator}, and the syntax
 * checks in {@link blue.language.identity.BlueIds}.</p>
 *
 * <p><strong>Lifecycle.</strong> Calculators and validators are stateless or
 * immutable after construction and may be shared. They do not mutate caller
 * graphs unless an individual method explicitly documents in-place metadata
 * removal; returned nodes and collections are caller-owned.</p>
 *
 * <p><strong>Extension.</strong> BlueId v1 has one implementation path: new
 * entry points must delegate to these formulas and preserve exact canonical
 * bytes. Alternative hashes or semantic identifiers do not belong here.
 * Mutable inputs live in {@link blue.language.model.Node}; complete Source
 * preparation is exposed through {@link blue.language.resolve.BlueResolution}.</p>
 */
package blue.language.identity;
