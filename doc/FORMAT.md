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
- String: compression algorithm
    - Supported values: NONE, GZIP, XZ, BZIP2, ZSTD
    - Default: XZ
    - Note: specific implementations may add additional metadata; XZ and GZIP are natively supported by the tool.

The following data is organized in messages:

- Unsigned varint: Type id
    - 0: int-based type
    - 1: Varint-based type
    - 2: Floating point type
    - 3: String-based type
    - 4: Array-based type
    - 5: Struct-based type
    - 6 - 15: reserved for future use
    - 16+: user defined type
- Content
    - the content depends on the message type

### Type Specification
- unsigned varint: Type ID
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
        - long: multiplier, the value is stored as `value / multiplier` and read as `value * multiplier`
    - Floating point type (maps to `float` in Java)
        - _we don't need double precision for now, maybe add it later_
        - uint8: type
            - 0: IEEE 754 32 bit float
            - 1: BFloat16 (top 16 bits of a 32 bit float)
    - String
        - String: Encoding (e.g. `UTF-8`)
    - Array-based type
        - Unsigned varint: Element type ID
        - Embedding (see struct-based type)
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
            - Embedding
                - 0: inline
                - 1: inline, but nullable (prefix with a byte indicating null or non-null)
                - 2: reference into cache
                    - 0: null
                    - 1: value not in cache, followed by the value
                    - 2: value in cache, followed by the index (id starting at 2 in the cache)
                - 3: reference into cache per embedding type
                    - ...
            - Unsigned varint: reduction id (used in JFR), 0 for none
        - Unsigned varint: reduction id
        - The fields are stored in the order they are defined

The primitive types (not array or struct) are trivially parsed according to their specification.

### Struct-based type
- The fields are stored in the order they are defined

### Array-based type
- unsigned varint: number of elements
- each element is stored according to its type and embedding (see struct-based type)

### User defined type
As specified by the type specification.