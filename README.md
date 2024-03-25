Condensed Data
==============

[![ci](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml/badge.svg)](https://github.com/parttimenerd/condensed-data/actions/workflows/ci.yml)

A base library for reading and writing condensed event data
to disk. Focusing on a simple, yet space saving, format.

The main usage will be to compress JFR files related to GC.
This project might later also include the compressor and a
compressing JFR agent.

Data Specification
------------------

This data specification is independent of JFR.
It is designed to be a simple and space efficient way to stream
data with self-describing types to disk.

Basic encodings:
- String is encoded with a length prefix (unsigned varint)

Each file starts with a header:
- String: "CondensedData"
- Unsigned varint: Version
- String: Generator name
- String: Generator version

The following data is organized in messages:

- Unsigned varint: Type id
    - 0: int-based type
    - 1: Varint-based type
    - 2: Floating point type
    - 3: String-based type
    - 4: Struct-based type
    - 5: Array-based type
    - 6 - 15: reserved for future use
    - 16+: user defined type
- Content
  - the content depends on the message type

### Type Specification
- id is given by the order of the type in the file
- String: Type name
- String: Type description
- Followed by the type-specific data
  - int-based type (maps to ``long` in Java)
    - uint8: width (1 to 8) in bytes
    - uint8: flags
      - lowest bit: 0: unsigned, 1: signed
      - second lowest bit: when to large: 0 to throw error, 1 to saturate
  - Varint-based type (maps to `long` in Java)
    - uint8: signedness (0: unsigned, 1: signed)
  - Floating point type (maps to `double` in Java)
    - uint8: width (4: float, 8: double)
  - String
    - String: Encoding (e.g. `UTF-8`)
  - Struct-based type
    - Unsigned varint: Number of fields
    - For each field:
      - String: Field name
      - String: Description
      - Unsigned varint: Field type ID
        - an id of < 16 means the default version of each type
        - int-based type: 32 bit signed integer
        - varint-based type: signed integer
        - floating point type: 32 bit double
        - string-based type: UTF-8 string
        - other non-user defined types are not allowed
    - The fields are stored in the order they are defined
  - Array-based type
    - Unsigned varint: Element type ID
    - uint8: type of embedding
      - 0: directly embedded ("by value")
      - 1: absolute reference
      - 2: relative reference

The primitive types (not array or struct) are trivially parsed according to their specification.

### Struct-based type
- The fields are stored in the order they are defined

### Array-based type
- unsigned varint: number of elements
- each element is stored according to its type and embedding (see struct-based type)

### User defined type
As specified by the type specification.

Requirements
------------
JDK 17+

Development
-----------
Every commit is formatted via `mvn spotless:apply` in a pre-commit hook to ensure consistent formatting, install it via:
```shell
mvn install
```
This pre-commit hook also runs the tests via `mvn test`.

License
-------
MIT, Copyright 2024 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors