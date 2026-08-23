"""Dependency-free RFC 8785 JSON Canonicalization Scheme implementation.

Vendored from Trail of Bits ``rfc8785.py`` 0.1.4, commit
02ecc03315ff17dd90fc892da4faa6998fa9314b.  The upstream internal module was
flattened into this single package tool; canonicalization behavior is
unchanged.  Licensed under Apache-2.0; see JCS-LICENSE.txt and JCS-NOTICE.txt.
"""

from __future__ import annotations

import math
import re
import typing
from io import BytesIO

_Scalar = typing.Union[bool, int, str, float, None]

_Value = typing.Union[
    _Scalar,
    typing.Sequence["_Value"],
    typing.Tuple["_Value"],
    typing.Mapping[str, "_Value"],
]

# The "safe integer" range, per RFC 8785 Appendix B, Note 1. This is slightly
# stricter than RFC 8785 3.1's JSON number data requirement, which states that
# numbers MUST be expressible as IEEE 754 double-precision values.
#
# For integers equal or greater than 2**53, only some are exactly representable
# by IEEE 754 double-precision values. Instead of allowing them (which is what
# section 3.1 implies), we follow Appendix B Note 1, which suggests supporting
# only the range of integers which is exactly representable.
_INT_MAX = 2**53 - 1
_INT_MIN = -(2**53) + 1

# These are adapted from Andrew Rundgren's reference implementation,
# which is licensed under the Apache License, version 2.0.
# See: <https://github.com/cyberphone/json-canonicalization/blob/ba74d44ecf5/python3/src/org/webpki/json/Canonicalize.py>
# See: <https://github.com/cyberphone/json-canonicalization/blob/ba74d44ecf5/python3/src/org/webpki/json/LICENSE>
_ESCAPE = re.compile(r'[\x00-\x1f\\"\b\f\n\r\t]')
_ESCAPE_DCT = {
    "\\": "\\\\",
    '"': '\\"',
    "\b": "\\b",
    "\f": "\\f",
    "\n": "\\n",
    "\r": "\\r",
    "\t": "\\t",
}
for i in range(0x20):
    _ESCAPE_DCT.setdefault(chr(i), f"\\u{i:04x}")


class CanonicalizationError(ValueError):
    """The base error for all errors during canonicalization."""


class IntegerDomainError(CanonicalizationError):
    """The integer lies outside the RFC 8785 safe integer range."""

    def __init__(self, n: int) -> None:
        super().__init__(f"{n} exceeds safe integer domain for JSON floats")


class FloatDomainError(CanonicalizationError):
    """The float is infinite, NaN, or otherwise not representable in JCS."""

    def __init__(self, f: float) -> None:
        super().__init__(f"{f} is not representable in JCS")


def _serialize_str(s: str, sink: typing.IO[bytes]) -> None:
    """Serialize a string per RFC 8785 section 3.2.2.2."""

    def _replace(match: re.Match) -> str:
        return _ESCAPE_DCT[match.group(0)]

    sink.write(b'"')
    try:
        # UTF-8 encoding deliberately rejects lone UTF-16 surrogates.
        sink.write(_ESCAPE.sub(_replace, s).encode("utf-8"))
    except UnicodeEncodeError as error:
        raise CanonicalizationError(
            "input contains non-UTF-8 codepoints"
        ) from error
    sink.write(b'"')


def _serialize_float(f: float, sink: typing.IO[bytes]) -> None:
    """Serialize a float using ECMA-262 plus RFC 8785 section 3.2.2.3."""
    if math.isnan(f) or math.isinf(f):
        raise FloatDomainError(f)

    if f == 0:
        sink.write(b"0")
        return

    if f < 0:
        sink.write(b"-")
        _serialize_float(-f, sink)
        return

    stringified = str(f)

    exponent_str = ""
    exponent_value = 0
    q = stringified.find("e")
    if q > 0:
        exponent_str = stringified[q:]
        if exponent_str[2:3] == "0":
            exponent_str = exponent_str[:2] + exponent_str[3:]
        stringified = stringified[0:q]
        exponent_value = int(exponent_str[1:])

    first = stringified
    dot = ""
    last = ""
    q = stringified.find(".")
    if q > 0:
        dot = "."
        first = stringified[:q]
        last = stringified[q + 1 :]

    if last == "0":
        dot = ""
        last = ""

    if exponent_value > 0 and exponent_value < 21:
        first += last
        last = ""
        dot = ""
        exponent_str = ""
        q = exponent_value - len(first)
        while q >= 0:
            q -= 1
            first += "0"
    elif exponent_value < 0 and exponent_value > -7:
        last = first + last
        first = "0"
        dot = "."
        exponent_str = ""
        q = exponent_value
        while q < -1:
            q += 1
            last = "0" + last

    sink.write(f"{first}{dot}{last}{exponent_str}".encode())


def dumps(obj: _Value) -> bytes:
    """Return the RFC 8785 serialization of *obj*."""
    sink = BytesIO()
    dump(obj, sink)
    return sink.getvalue()


def dump(obj: _Value, sink: typing.IO[bytes]) -> None:
    """Write the RFC 8785 serialization of *obj* to *sink*."""
    if obj is None:
        sink.write(b"null")
    elif isinstance(obj, bool):
        obj = bool(obj)
        sink.write(b"true" if obj is True else b"false")
    elif isinstance(obj, int):
        obj = int(obj)
        if obj < _INT_MIN or obj > _INT_MAX:
            raise IntegerDomainError(obj)
        sink.write(str(obj).encode("utf-8"))
    elif isinstance(obj, str):
        # Do not coerce str/Enum subtypes through their possibly custom __str__.
        _serialize_str(obj, sink)
    elif isinstance(obj, float):
        obj = float(obj)
        _serialize_float(obj, sink)
    elif isinstance(obj, (list, tuple)):
        obj = list(obj)
        if not obj:
            sink.write(b"[]")
            return
        sink.write(b"[")
        for idx, elem in enumerate(obj):
            if idx > 0:
                sink.write(b",")
            dump(elem, sink)
        sink.write(b"]")
    elif isinstance(obj, dict):
        obj = dict(obj)
        if not obj:
            sink.write(b"{}")
            return
        try:
            obj_sorted = sorted(
                obj.items(), key=lambda kv: kv[0].encode("utf-16be")
            )
        except AttributeError as error:
            raise CanonicalizationError(
                "object keys must be strings"
            ) from error

        sink.write(b"{")
        for idx, (key, value) in enumerate(obj_sorted):
            if idx > 0:
                sink.write(b",")
            _serialize_str(key, sink)
            sink.write(b":")
            dump(value, sink)
        sink.write(b"}")
    else:
        raise CanonicalizationError(f"unsupported type: {type(obj)}")
