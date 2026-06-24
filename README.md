# CompPrehension Toolchain Server

A long-running **JSON-RPC 2.0 HTTP server** that exposes the CompPrehension command-line toolchains
as in-process services:

| Toolchain | Route | What it does |
|-----------|-------|--------------|
| [`its_DomainModel`](../its_DomainModel) | `POST /rpc/domain` | Validate/convert domain models & decision trees (LOQI / XML / RDF / CSV dictionaries) |
| [`its_Reasoner`](../its_Reasoner) | `POST /rpc/reasoner` | Run decision-tree reasoning and LOQI expression queries |
| [`meaning_tree`](../meaning_tree) | `POST /rpc/meaning-tree` | Translate / serialize source code via the universal Meaning Tree |

## Why this exists

Each toolchain ships a CLI packaged as an executable JAR. During development the same operations are
issued **repeatedly and back-to-back** (validate a model, translate a snippet, run the reasoner, …).
Spawning a fresh JVM for every CLI invocation is slow: most of the wall-clock time is JVM start-up and
class loading, not the actual work.

This server starts the JVM **once**, loads all three toolchains as ordinary Maven dependencies, and
calls their library APIs **directly, in-process**. Requests arrive over HTTP and return immediately —
no per-request process spawn. It can be run locally or on a remote host, and it serves several
requests concurrently.

Wherever a CLI printed human-readable text, the server returns **structured JSON** instead. The
`its_Reasoner` JSON (JSONL) event model is reused as-is.

## Design at a glance

- **Stack:** Kotlin + [Javalin](https://javalin.io) (embedded Jetty thread pool ⇒ concurrent requests)
  + Jackson. Built to a single runnable fat JAR with the Maven Shade plugin.
- **In-process reuse:** the toolchains are consumed as dependencies
  (`com.github.CompPrehension:its_DomainModel`, `com.github.CompPrehension:its_Reasoner`,
  `org.vstu.meaningtree:application`), so their logic runs without subprocesses or stdout capture.
- **One route per toolchain**, each hosting a set of JSON-RPC methods that mirror the CLI subcommands.
- **Auto-generated docs:** an [OpenRPC](https://open-rpc.org) document (the JSON-RPC equivalent of
  OpenAPI/Swagger) is generated per route from the same method registry the server dispatches against,
  so the docs can never drift from the implementation. A self-contained HTML docs page is served too.
- **Concurrency:** independent requests run in parallel. The few toolchain operations that touch
  process-global mutable state (the XML writer's CDATA flag; meaning_tree's node/token id counters)
  are guarded by narrow per-tool locks.

## Prerequisites

- **JDK 21+** (developed/tested on JDK 25).
- **Maven 3.9+**.
- The three toolchain artifacts must be resolvable — either already installed in your local
  `~/.m2/repository` (run `mvn install` in each project), or available via the configured
  [jitpack.io](https://jitpack.io) repository. The expected versions are pinned in
  [`pom.xml`](pom.xml) (`its.domainmodel.version`, `its.reasoner.version`, `meaningtree.version`).

## Build

```bash
mvn -DskipTests package
```

This produces the runnable fat JAR `target/compph-toolchain-server-<version>.jar`.

## Run

```bash
java -jar target/compph-toolchain-server-0.1.0.jar [PORT]
```

Configuration:

| Setting | Source | Default |
|---------|--------|---------|
| Port | first CLI arg, or `PORT` env var | `8080` |
| Host/bind address | `HOST` env var | `0.0.0.0` |

On start-up the server prints its address and the available routes. Open `http://<host>:<port>/` for
the documentation index.

## Documentation endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /` | HTML index listing all routes and the payload conventions |
| `GET /health` | Liveness probe (`{"status":"ok"}`) |
| `GET {route}/docs` | Self-contained HTML docs for one toolchain |
| `GET {route}/openrpc.json` | Machine-readable OpenRPC document for one toolchain |

The OpenRPC document can also be opened in the hosted
[OpenRPC Playground](https://playground.open-rpc.org) (linked from each `/docs` page).

## Request format

Every route speaks **JSON-RPC 2.0**. A request is either a single request object or a batch (array).
Requests without an `id` are **notifications** and receive no response (a body consisting solely of
notifications returns HTTP `204`).

```jsonc
{ "jsonrpc": "2.0", "id": 1, "method": "<method>", "params": { /* by-name params */ } }
```

Send the request with either content type:

- **`application/json`** — the body is the JSON-RPC request. File payloads must be inline
  (text or base64).
- **`multipart/form-data`** — a form field named **`request`** holds the JSON-RPC body, and any
  number of additional file parts can be uploaded and referenced by name. This is the efficient way
  to send large files (no base64 overhead).

### File & directory payloads

Methods that consume files or directories accept *imitated* payloads that the server materializes into
a real temporary directory for the duration of the request, then deletes.

**FileSource** — one of:

```jsonc
"inline text directly as a string"
{ "text": "inline text" }
{ "base64": "<base64-encoded bytes>" }
{ "ref": "<multipart field name of an uploaded file>" }
```

**DirSource** (an imitated directory) — one of:

```jsonc
{ "files": { "domain.loqi": <FileSource>, "tree_main.xml": <FileSource>, "sub/dir/x.tpg": <FileSource> } }
{ "ref": "<multipart field name of an uploaded .zip archive>" }
```

The toolchains pick up files from a directory by name/extension. The relevant types are:

- **LOQI models:** `domain.loqi`, `tag_<name>.loqi`
- **Decision trees:** `tree.xml` / `tree_<name>.xml` / `tpg_<name>.xml`, the matching `.loqi`
  variants, and `<name>.tpg`
- **CSV-dictionary builds (`buildMethod=DICT_RDF`):** `enums.csv`, `classes.csv`, `properties.csv`,
  `relationships.csv` and `domain.ttl`

You may include other relative paths too; only the names the toolchains look for are read.

## Methods

### `/rpc/domain` (its_DomainModel)

| Method | Purpose |
|--------|---------|
| `validate-dsm` | Validate a DomainSolvingModel directory |
| `tree-loqi-to-xml` | Convert a decision tree LOQI → XML (optionally validate against a model) |
| `decompile-tree` | Decompile a decision tree XML → LOQI/TPG (experimental) |
| `dict-to-loqi` | Build a domain from CSV dictionaries + `domain.ttl`, emit LOQI |
| `validate-domain-loqi` | Validate an extra domain LOQI merged into a model |
| `domain-to-rdf` | Build a concrete domain and write it as RDF Turtle |
| `rdf-to-domain-loqi` | Fill a concrete domain from RDF TTL and emit LOQI |

### `/rpc/reasoner` (its_Reasoner)

| Method | Purpose |
|--------|---------|
| `reason` | Run decision-tree reasoning; returns result, variables, exceptions, trace, metrics |
| `expression-query` | Evaluate a LOQI expression and return matching object names |

### `/rpc/meaning-tree` (meaning_tree)

| Method | Purpose |
|--------|---------|
| `translate` | Translate code between languages, or serialize its Meaning Tree |
| `generate` | Generate code / re-serialize / tokenize from a serialized Meaning Tree |
| `list-langs` | List supported languages |
| `node-hierarchy` | Return the Meaning Tree node type hierarchy |

See each route's `/docs` (or `/openrpc.json`) for the full parameter set and schemas.

## Examples

Translate Python to Java (inline):

```bash
curl -s http://localhost:8080/rpc/meaning-tree \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"translate",
       "params":{"from":"python","to":"java","code":"a = 1 + 2"}}'
```

Translate a file via multipart upload (no base64):

```bash
curl -s http://localhost:8080/rpc/meaning-tree \
  -F 'request={"jsonrpc":"2.0","id":1,"method":"translate","params":{"from":"python","to":"c++","code":{"ref":"src"}}}' \
  -F 'src=@snippet.py'
```

Build a domain from CSV dictionaries (imitated directory, inline files):

```bash
curl -s http://localhost:8080/rpc/domain \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"dict-to-loqi",
       "params":{"model":{"files":{
         "classes.csv":"Item|\n","enums.csv":"","properties.csv":"","relationships.csv":"",
         "domain.ttl":"@prefix poas: <http://vstu.ru/poas/code#> .\n"}}}}'
```

Run a LOQI expression query:

```bash
curl -s http://localhost:8080/rpc/reasoner \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"expression-query",
       "params":{"domainLoqi":"class Item\nobj a: Item\n","query":"1 < 2","trace":true}}'
```

## Error model

Errors follow JSON-RPC 2.0. In addition to the standard codes
(`-32700` parse, `-32600` invalid request, `-32601` method not found, `-32602` invalid params,
`-32603` internal), the server uses:

- `-32000` — a toolchain raised an exception while processing valid params. The `error.data` carries
  `exceptionName`, `rootCause`, `rootCauseMessage` and a trimmed `stackTrace`.
- `-32001` — a file/directory payload could not be resolved or materialized.

## License

Part of the CompPrehension project; see the individual toolchain repositories for their licenses.
