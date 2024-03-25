# IPFS Usage Example

This is a simple demonstration of how Blue and InterPlanetary File System (IPFS) can be used together.

## Overview

The demo showcases the process of inserting a document into Blue, printing out Blue IDs and canonical JSONs for all nodes of the document, and then adding any canonical JSON to IPFS. This allows you to reference parts of the document using Blue IDs on the IPFS network.

## Steps to Reproduce

### 1. Prepare the Document

Put some content into `sample.blue`, e.g.:

```yaml
name: Abc
a: xyz
b:
  b1: 1
  b2: 2
```

### 2. Run Sample1Print

Run `Sample1Print` to print out Blue IDs and canonical JSONs for all the nodes of the document. You should see output similar to the following:

```
3QrPqvQdgMbjFaUQcMrgQTSjdiWG5EZonWyEHvbrAv9o
{"value":"xyz"}
---
5y2ziQ9mf8HKGYtwzoSafugJqYKHzWv2DEZfP971vPrr
{"value":2}
---
5rZ9miY2xyczUkmCAMhBxbcw2jtM7zm4Lskzr3yQKtGd
{"value":1}
---
ANJbvdyojDfqp93ZQbo8eLXeyYvvVEr227ELDZpgwHQW
{"b1":"5rZ9miY2xyczUkmCAMhBxbcw2jtM7zm4Lskzr3yQKtGd","b2":"5y2ziQ9mf8HKGYtwzoSafugJqYKHzWv2DEZfP971vPrr"}
---
2WcJWxShcJB2gx4zf57mJxF8Axoy4GFbyGscQbfSihwp
{"a":"3QrPqvQdgMbjFaUQcMrgQTSjdiWG5EZonWyEHvbrAv9o","b":"ANJbvdyojDfqp93ZQbo8eLXeyYvvVEr227ELDZpgwHQW","name":"Abc"}
---
```

### 3. Add to IPFS

Place any canonical JSON from the output above into file `input.txt` and execute the `add-to-ipfs.sh` script.
Ensure that your local IPFS node is running before doing this.

### 4. Run Sample2Resolve

With the `Sample2Resolve` script, you can now use the Blue ID for parts of the document that you've added to IPFS. For example:

```yaml
name: Abc
a: xyz
b: ANJbvdyojDfqp93ZQbo8eLXeyYvvVEr227ELDZpgwHQW
```

IPFS CID will be auto-generated based on Blue ID.