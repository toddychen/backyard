# Collaborative Rich-Text Editor — Implementation Plan

## Context

This document describes the design, tech stack decisions, and component
responsibilities for a collaborative rich-text editor built into the backyard
repo. The goal is a Google Docs-like experience: multiple users editing the
same document in real time with live cursors, persisted across sessions.

---

## Why We Are Building This

The backyard repo already has a chat feature (real-time messaging via
WebSocket). Collaborative document editing is the natural next step —
it demonstrates the same infrastructure (Redis pub/sub, WebSocket, k8s
horizontal scaling) but with a significantly more complex sync problem,
solved here using a production-grade CRDT approach.

---

## Decision Log

| Decision | Choice | Reason |
|---|---|---|
| Sync algorithm | CRDT (Yjs) | No central sequencer; stateless pods; offline-first; best 2024 ecosystem |
| Document type | Rich text | Most useful; TipTap + Yjs is a complete, production-tested stack |
| Sync server | Hocuspocus (Node.js) | Purpose-built Yjs server; no Java Yjs library exists |
| Frontend framework | React (Vite) | TipTap requires React; no viable vanilla JS path for rich text |
| Frontend hosting | Served from collab pods | Simplest deployment; one service, one Dockerfile |
| Cross-pod fan-out | Redis pub/sub (`@hocuspocus/extension-redis`) | Existing Redis; makes collab pods fully stateless |
| Hot state | Redis key per document | Fast load on client join; no DB hit for active docs |
| Cold persistence | MySQL (LONGBLOB) | Existing MySQL in playground; durable; `mysql2` npm package |
| Table creation | `CREATE TABLE IF NOT EXISTS` on startup | Self-contained; no separate migration step |
| User identity | Name input modal → sessionStorage | Tab-scoped; two tabs = two users; no auth complexity for v1 |
| Cursor colour | Auto-assigned from name hash | Deterministic; no user choice needed |
| Auth | Hocuspocus hook → Spring Boot JWT endpoint | Reuse existing JWT infrastructure |
| Service location | New `node-services/collab/` directory | Separate from Maven-managed `services/` Java projects |

---

## Architecture Overview

```
Browser (React SPA)
   │
   ├── GET /api/documents         REST — list documents
   ├── POST /api/documents        REST — create document
   ├── GET /doc/:id               SPA route — editor page
   │
   └── WebSocket ws://host/       Yjs sync (Hocuspocus protocol)
              │
              ▼
   ┌──────────────────────────┐
   │   Hocuspocus (Node.js)   │   node-services/collab/
   │                          │
   │  Express REST routes      │   GET/POST /api/documents
   │  Express static serve     │   serves React build
   │  WebSocket upgrade        │   Yjs protocol handler
   │                          │
   │  @hocuspocus/extension-  │
   │    redis                  │──── Redis pub/sub + hot state key
   │  @hocuspocus/extension-  │
   │    database               │──── MySQL LONGBLOB (fetch / store)
   │                          │
   │  onAuthenticate hook      │──── HTTP → Spring Boot JWT validate
   └──────────────────────────┘
              │
   ┌──────────┴──────────┐
   │                     │
   ▼                     ▼
Redis                  MySQL
(hot doc state,        (cold doc state,
 pub/sub bus)           documents table)
```

---

## Directory Structure

```
backyard/
├── services/                      (Maven-managed Java services — unchanged)
│   └── playground/                (Spring Boot — add JWT validate endpoint)
│
├── node-services/                 (new — Node.js services)
│   └── collab/
│       ├── src/
│       │   ├── server.js          Hocuspocus + Express (REST + static)
│       │   └── db.js              MySQL helpers (initDB, list, create, fetch, store)
│       ├── client/                React SPA (Vite)
│       │   ├── src/
│       │   │   ├── main.jsx
│       │   │   ├── App.jsx        Router: / and /doc/:id
│       │   │   ├── DocumentList.jsx
│       │   │   ├── Editor.jsx     TipTap + HocuspocusProvider
│       │   │   └── NameModal.jsx  sessionStorage name prompt
│       │   ├── index.html
│       │   └── package.json
│       ├── package.json
│       └── Dockerfile
│
└── docs/
    ├── collaborative-editing-theory.md   (OT + CRDT reference)
    └── collab-implementation-plan.md     (this file)
```

---

## Tech Stack — Component Responsibilities

### Yjs

**What it is:** A CRDT library. The document is stored as a `Y.Doc` containing
a `Y.XmlFragment` tree. Every character, every formatting attribute, every
paragraph is a CRDT node with a unique ID and immutable origin/right pointers.

**Responsibility in the user flow:**
- On every keystroke: the TipTap binding converts the editor transaction into
  a Y.XmlFragment mutation → Yjs encodes this as a compact binary update blob
- On receiving a remote update: Yjs merges the blob into the local Y.Doc
  (commutative, idempotent — order does not matter)
- Provides `Y.UndoManager` for per-user undo/redo

**What Yjs does NOT do:** transport, persistence, cursor positions — those are
handled by the provider and awareness layers.

---

### TipTap (+ ProseMirror underneath)

**What it is:** A React-based rich-text editor built on ProseMirror.

**Responsibility:**
- Renders the document as DOM — paragraphs, bold, italic, headings, lists
- Translates user input (typing, formatting toolbar, keyboard shortcuts) into
  ProseMirror transactions
- The `@tiptap/extension-collaboration` binding keeps ProseMirror's internal
  document model in sync with `Y.XmlFragment` (two-way binding)
- The `@tiptap/extension-collaboration-cursor` extension reads cursor awareness
  data from the Hocuspocus provider and renders other users' cursors in the DOM
- Uses surgical DOM patching (not full re-render) on every remote update —
  only the changed text nodes are updated

**What TipTap does NOT do:** CRDT merge, sync, persistence — Yjs + Hocuspocus
handle those.

---

### HocuspocusProvider (client side)

**What it is:** The client-side WebSocket adapter (`@hocuspocus/provider`).
Runs in the browser.

**Responsibility:**
- Opens and maintains the WebSocket connection to the Hocuspocus server
- Sends Yjs binary update blobs to the server on every local change
- Receives binary update blobs from the server, feeds them to the local Y.Doc
- Sends and receives **awareness** data (cursor position, user name, colour)
  on a separate channel alongside the doc updates
- Handles reconnect with exponential backoff
- On reconnect: sends the full Y.Doc state diff so the server can merge any
  changes made while offline

---

### Hocuspocus Server (`@hocuspocus/server`)

**What it is:** A Node.js WebSocket server built specifically for Yjs.

**Responsibility:**
- Accepts WebSocket connections, one per (client, document) pair
- Runs `onAuthenticate` hook before allowing any connection:
  validates the JWT by calling Spring Boot's `/api/v1/collab/auth/validate`
- Maintains an in-memory set of connected clients per document (the "room")
- When a binary update arrives from client A:
  - Forwards it to all other clients connected to the same document (fan-out)
  - Passes it to `extension-redis` and `extension-database` for persistence
- When a new client joins a document:
  - Loads the current Y.Doc state from `extension-redis` (or falls back to
    `extension-database`) and sends it as the initial state

---

### `@hocuspocus/extension-redis`

**What it is:** A Hocuspocus plugin wrapping `ioredis`.

**Two responsibilities:**

**Hot state storage:**
- Keeps Redis key `hocuspocus:doc:{docId}` updated with the latest Y.Doc
  binary blob after every update
- On new client join: serves the blob from Redis (fast, avoids MySQL hit)
- Redis TTL cleans up keys for inactive documents automatically

**Cross-pod pub/sub:**
- Subscribes to Redis channel `hocuspocus:channel:{docId}` when any client
  is connected to that document on this pod
- When this pod receives an update: publishes it to the Redis channel
- When another pod publishes to the channel: this pod receives it and
  forwards to all locally connected clients
- This makes all collab pods fully stateless — any pod can serve any client

---

### `@hocuspocus/extension-database`

**What it is:** A Hocuspocus plugin for durable persistence.

**Responsibility:**
- Calls your `fetch(docId)` callback when Redis misses (cold start, Redis
  restart, first ever load of a document)
- Calls your `store(docId, state)` callback periodically and when the last
  client leaves a document
- `fetch` returns the MySQL LONGBLOB as a `Uint8Array` (or null for a new doc)
- `store` writes the Y.Doc binary blob to MySQL

---

### Express (in `server.js`)

**Responsibility:**
- Serves the React SPA build from `public/` (static files)
- `GET *` falls back to `public/index.html` for client-side routing
- REST routes:
  - `GET /api/documents` → `db.listDocuments()` → list of `{id, title, createdAt}`
  - `POST /api/documents` → `db.createDocument(title)` → `{id, title}`
- Shares the same `http.Server` instance with Hocuspocus for WebSocket upgrades
  (Hocuspocus intercepts the upgrade request on the same port)

---

### MySQL (`mysql2` npm package)

**Responsibility:**
- Durable storage for document content and metadata
- `documents` table auto-created on server startup via `CREATE TABLE IF NOT EXISTS`
- Schema:
  ```sql
  id         VARCHAR(36)   PRIMARY KEY        -- UUID
  title      VARCHAR(255)  NOT NULL           -- display name
  owner_id   VARCHAR(36)   NOT NULL           -- 'anonymous' for v1
  content    LONGBLOB                         -- Yjs binary blob (Y.Doc state)
  created_at DATETIME      DEFAULT NOW()
  updated_at DATETIME      ON UPDATE NOW()
  ```
- `content` column stores the raw `Uint8Array` from `Y.encodeStateAsUpdate(ydoc)`
- On read: returned as `Buffer` by `mysql2`, passed directly to Yjs as
  `Uint8Array` — no parsing, no serialisation

---

### Redis

**Two separate uses:**

1. **Hot document state** — managed entirely by `@hocuspocus/extension-redis`.
   Key per document, value = Yjs binary blob. Updated on every edit.

2. **Pub/sub fan-out** — also managed by `@hocuspocus/extension-redis`.
   Channel per document. Each pod publishes updates it receives, subscribes
   to updates from other pods. Makes the collab tier horizontally scalable.

The existing Redis instance from `services/playground` is reused. The collab
service uses a dedicated key prefix (`hocuspocus:`) to avoid collisions with
playground's JWT denylist and cache keys.

---

### Spring Boot (playground) — JWT Validate Endpoint

**New endpoint added:**
```
POST /api/v1/collab/auth/validate
Authorization: Bearer <jwt>

200 OK  { "userId": "...", "email": "..." }
401     Unauthorized
```

**Responsibility:** Validates the Bearer token using the existing
`JwtAuthenticationFilter` logic and the Redis JWT denylist check. Returns
user info on success. Hocuspocus calls this endpoint inside `onAuthenticate`
before any WebSocket connection is permitted.

This reuses all existing auth infrastructure — no new auth logic.

---

### React SPA (client)

**Pages and components:**

`/` — **DocumentList:**
- `GET /api/documents` on mount → renders list of document titles
- "New Document" button → `POST /api/documents` → navigate to `/doc/:id`

`/doc/:id` — **Editor:**
- Wraps everything in `NameModal` — checks `sessionStorage.getItem('userName')`
- If absent: blocks rendering, shows input modal, stores name in `sessionStorage`
  (`sessionStorage` is tab-scoped — each tab has an independent identity)
- Creates `Y.Doc` and `HocuspocusProvider` bound to the document ID
- Initialises TipTap with `Collaboration` + `CollaborationCursor` extensions
- Cursor colour is `hashColor(userName)` — deterministic from name, no choice needed
- Renders TipTap's `EditorContent` and a simple formatting toolbar

---

## Full User Flow

### Opening the document list

```
User opens http://host/
  → Express serves index.html (React SPA)
  → React router renders DocumentList
  → GET /api/documents → Express → MySQL SELECT → list rendered
```

### Creating a new document

```
User clicks "New Document"
  → POST /api/documents → Express → MySQL INSERT (id=UUID, title="Untitled")
  → Response: { id, title }
  → React navigates to /doc/{id}
```

### Joining a document for the first time (tab 1)

```
/doc/{id} renders Editor
  → NameModal checks sessionStorage — empty → shows name input
  → User types "Alice" → sessionStorage.setItem("userName", "Alice")
  → NameModal renders children

HocuspocusProvider opens WebSocket ws://host/
  → Hocuspocus server receives connection for document {id}
  → onAuthenticate: POST to Spring Boot /api/v1/collab/auth/validate
    → 200 OK { userId, email }  (or 401 → reject)
  → extension-redis: check Redis "hocuspocus:doc:{id}"
    → miss (new doc) → extension-database: SELECT content FROM documents WHERE id={id}
    → null (new doc) → send empty Y.Doc to client
  → subscribe to Redis channel "hocuspocus:channel:{id}"

TipTap initialises with empty Y.Doc → blank editor
```

### Second user joins (tab 2, different pod in k8s)

```
/doc/{id} renders → NameModal → "Bob"

HocuspocusProvider opens WebSocket to pod 2
  → onAuthenticate: validates JWT
  → extension-redis: check Redis "hocuspocus:doc:{id}"
    → hit (Alice's edits are in Redis) → send current Y.Doc blob to Bob
  → subscribe to Redis channel "hocuspocus:channel:{id}"

TipTap initialises with existing content → Bob sees Alice's work
Awareness: Bob's cursor appears in Alice's editor (and vice versa)
```

### Editing (real-time sync)

```
Alice types "Hello":
  TipTap → Y.XmlFragment mutation → Yjs encodes binary update
  HocuspocusProvider sends update blob over WebSocket to pod 1

Pod 1 (Alice's pod):
  Receives update
  → extension-redis: update Redis key "hocuspocus:doc:{id}"
  → extension-redis: publish to Redis channel "hocuspocus:channel:{id}"
  → extension-database: (batched) schedule MySQL write

Redis channel fan-out:
  Pod 2 (Bob's pod) receives published update
  → forwards binary blob to Bob over Bob's WebSocket

Bob's browser:
  HocuspocusProvider receives blob → Y.applyUpdate(ydoc, blob)
  → Y.XmlFragment updated → TipTap binding detects change
  → ProseMirror transaction → minimal DOM patch → Bob sees "Hello"
```

### Persisting and reloading

```
All users close the document (last client disconnects from pod):
  → Hocuspocus triggers extension-database store callback
  → MySQL UPDATE documents SET content=<blob>, updated_at=NOW()

User refreshes page:
  → HocuspocusProvider reconnects
  → Redis may still have hot state → served immediately
  → OR Redis expired → MySQL fetch → blob sent to client
  → TipTap initialises with saved content ✓
```

---

## k8s / kind Deployment

```
kind cluster (local)
├── collab Deployment   (2 replicas — to test Redis fan-out)
│   image: node-services/collab Dockerfile
│   port: 1234
│   env: REDIS_HOST, MYSQL_HOST, MYSQL_PASS, PLAYGROUND_URL
│
├── playground Deployment (2 replicas)
│   port: 8080
│
├── Redis pod            (1 replica — shared)
├── MySQL pod            (1 replica — shared)
│
└── nginx ingress
    annotations:
      proxy-read-timeout: "3600"   ← required for WebSocket
      proxy-send-timeout: "3600"
    routes:
      /api/*     → playground-service:8080    (Spring Boot REST)
      /*         → collab-service:1234        (React SPA + WS + collab REST)
```

**Scaling test:** with 2 collab pods, two browser tabs will likely land on
different pods. Edits from tab 1 must appear in tab 2 via Redis pub/sub.
Verify in pod logs — each pod should log the Redis publish/receive events.

---

## Dockerfile (node-services/collab/)

```dockerfile
# Stage 1: build React SPA
FROM node:20-alpine AS client-build
WORKDIR /app/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build          # outputs to client/dist/

# Stage 2: production server
FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY src/ ./src/
COPY --from=client-build /app/client/dist ./public   # React build → public/
EXPOSE 1234
CMD ["node", "src/server.js"]
```

---

## Verification Checklist

- [ ] `node src/server.js` starts and auto-creates `documents` table in MySQL
- [ ] `GET /api/documents` returns empty array for fresh DB
- [ ] `POST /api/documents` creates a row, returns `{id, title}`
- [ ] React SPA loads at `/`, shows document list
- [ ] Clicking "New Document" navigates to `/doc/:id`
- [ ] Name modal appears, stores name in `sessionStorage`
- [ ] TipTap editor renders with empty content
- [ ] Open same `/doc/:id` in second tab → name modal → second cursor visible
- [ ] Type in tab 1 → text appears in tab 2 in real time
- [ ] Refresh tab 1 → content reloaded from MySQL (or Redis)
- [ ] Deploy 2 collab pods to kind → confirm edits cross pods via Redis logs
- [ ] Kill one pod → surviving pod serves reconnected client, no data loss
- [ ] Invalid JWT → WebSocket connection rejected with 401
