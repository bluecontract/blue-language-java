# Schemas and unconstrained fields

A schema constrains presence and value shape. Absence of a type is itself a
deliberate open-value declaration; it is not a synonym for Dictionary.

## Three distinct declarations

Any Blue value is allowed:

```yaml
payload:
  description: Runtime-defined value
```

An object value is required when present:

```yaml
payload:
  type: Dictionary
```

Presence is required but shape remains open:

```yaml
schema:
  required: [payload]
payload:
  description: Required runtime-defined value
```

The first and third accept scalar, list, object, reference, or specialized
values. The second accepts the Dictionary object shape and rejects a scalar or
list. `required` controls whether the property exists; it does not invent a
type for its value.

Schema validation happens against resolved meaning. Fixed values and enum
members use canonical scalar/identity comparison, so representation or map key
order cannot change validity.

Run `UnconstrainedFieldExample` from `:examples` for accepted and rejected
cases. See the generated package reference for the model schema API.
