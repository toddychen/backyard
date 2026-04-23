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
| Cold persistence | MySQL via Prisma ORM | Existing MySQL; Prisma handles schema + type-safe queries |
| DB schema management | `prisma db push` on pod startup | Idempotent; no separate migration step for v1 |
| Database | Separate `collab` DB + `collab` user | Isolated from playground; dedicated credentials |
| User identity | Name input modal → `sessionStorage` | Tab-scoped; two tabs = two users; no auth complexity for v1 |
| Cursor colour | Auto-assigned from name hash | Deterministic; no user choice needed |
| Auth | None — public site | v1 simplicity; no JWT wiring needed |
| Service location | New `node-services/collab/` directory | Separate from Maven-managed `services/` Java projects |
| Internal port | 2070 | Avoids conflicts; not a well-known port |

---

## Architecture Overview

```
Browser (React SPA)
   │
   ├── GET /api/documents         REST — list documents
   ├── POST /api/documents        REST — create document
   ├── GET /health                Health probe
   ├── GET /doc/:id               SPA route — editor page
   │
   └── WebSocket ws://host/       Yjs sync (Hocuspocus protocol)
              │
              ▼
   ┌──────────────────────────┐
   │   Hocuspocus (Node.js)   │   node-services/collab/
   │                          │
   │  Express REST routes      │   GET/POST /api/documents
   │  Express static serve     │   serves React build from public/
   │  WebSocketServer          │   ws upgrade → hocuspocus.handleConnection
   │                          │
   │  @hocuspocus/extension-  │
   │    redis                  │──── Redis pub/sub + hot state key
   │  @hocuspocus/extension-  │
   │    database               │──── MySQL LONGBLOB (fetch / store via Prisma)
   │                          │
   │  onConnect / onDisconnect │──── console.log with socketId
   └──────────────────────────┘
              │
   ┌──────────┴──────────┐
   │                     │
   ▼                     ▼
Redis                  MySQL (collab DB)
(hot doc state,        (cold doc state,
 pub/sub bus)           collab_documents table)
```

---

## Directory Structure

```
backyard/
├── services/                      (Maven-managed Java services — unchanged)
│
├── node-services/                 (Node.js services)
│   └── collab/
│       ├── src/
│       │   ├── server.js          Hocuspocus + Express + WebSocketServer
│       │   └── db.js              Prisma helpers (list, create, fetch, store)
│       ├── client/                React SPA (Vite)
│       │   ├── src/
│       │   │   ├── main.jsx
│       │   │   ├── App.jsx        Router: / and /doc/:id
│       │   │   ├── DocumentList.jsx
│       │   │   ├── Editor.jsx     gate component + EditorInner (TipTap)
│       │   │   └── NameModal.jsx  sessionStorage name prompt
│       │   ├── index.html
│       │   ├── vite.config.js     proxies /api and /ws to :2070 in dev
│       │   └── package.json
│       ├── prisma/
│       │   └── schema.prisma      CollabDocument model
│       ├── package.json
│       └── Dockerfile             multi-stage: client build → production
│
├── infra/helm/collab/             Helm chart (NodePort 32070, 2 replicas)
├── scripts/
│   ├── deploy-service-collab-kind.sh
│   ├── run-service-collab-dev.sh
│   └── collab-mysql-init-kind.sh  one-time DB/user setup for existing cluster
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
- Translates user input (typing, formatting toolbar) into ProseMirror transactions
- `@tiptap/extension-collaboration` keeps ProseMirror in sync with `Y.XmlFragment`
- `@tiptap/extension-collaboration-cursor` renders other users' cursors using
  awareness data from the Hocuspocus provider
- Uses surgical DOM patching on every remote update

**What TipTap does NOT do:** CRDT merge, sync, persistence.

---

### HocuspocusProvider (client side)

**What it is:** The client-side WebSocket adapter (`@hocuspocus/provider`).

**Responsibility:**
- Opens and maintains the WebSocket connection to the Hocuspocus server
- Sends Yjs binary update blobs to the server on every local change
- Receives binary update blobs from the server, feeds them to the local Y.Doc
- Sends and receives **awareness** data (cursor position, user name, colour)
- Handles reconnect with exponential backoff; sends full Y.Doc diff on reconnect

---

### Hocuspocus Server (`@hocuspocus/server`)

**What it is:** A Node.js WebSocket server built specifically for Yjs.

**WebSocket integration:** The `ws` library handles the HTTP upgrade event;
each upgraded connection is passed to `hocuspocus.handleConnection(ws, request)`.
`handleUpgrade` does not exist on the Hocuspocus instance — `handleConnection`
is the correct API for external HTTP server integration.

**Responsibility:**
- Accepts WebSocket connections, one per (client, document) pair
- No authentication — site is public; `onAuthenticate` hook not used
- Maintains an in-memory set of connected clients per document
- When a binary update arrives from a client: fans out to all other clients
  on the same pod and delegates to `extension-redis` and `extension-database`
- When a new client joins: loads current Y.Doc state from Redis (or MySQL fallback)
- Logs connect/disconnect with `socketId` (UUID per connection) and `documentName`

---

### `@hocuspocus/extension-redis`

**What it is:** A Hocuspocus plugin for Redis integration.

**Hot state storage:**
- Keeps Redis key `hocuspocus:doc:{docId}` updated with the latest Y.Doc blob
- Serves the blob on new client join (avoids MySQL hit for active documents)

**Cross-pod pub/sub:**
- Subscribes to Redis channel `hocuspocus:channel:{docId}` per active document
- Publishes updates received on this pod to the channel
- Receives updates published by other pods and forwards to local clients
- Makes all collab pods fully stateless

---

### `@hocuspocus/extension-database`

**What it is:** A Hocuspocus plugin for durable persistence.

**Responsibility:**
- Calls `fetch(docId)` on Redis miss (cold start, first load)
- Calls `store(docId, state)` periodically and when the last client disconnects
- `fetch` → `db.fetchDocument(id)` → Prisma query → returns `Bytes` or null
- `store` → `db.storeDocument(id, state)` → Prisma upsert → MySQL LONGBLOB
- Logs `[db] store doc=<id> bytes=<n>` on every persist

---

### Express (in `server.js`)

**Responsibility:**
- `GET /health` → `{ status: 'ok' }` for k8s liveness/readiness probes
- `GET /api/documents` → `db.listDocuments()`
- `POST /api/documents` → `db.createDocument(title)`
- `app.use(express.static('public'))` → serves Vite build output
- `GET *` → `public/index.html` fallback for SPA client-side routing
- Shares the `http.Server` instance with the `WebSocketServer`

---

### Prisma ORM (`@prisma/client`)

**Replaces:** `mysql2` raw SQL driver.

**Schema** (`prisma/schema.prisma`):
```prisma
model CollabDocument {
  id        String   @id @default(uuid())
  title     String   @default("Untitled")
  content   Bytes?
  createdAt DateTime @default(now())
  updatedAt DateTime @updatedAt
  @@map("collab_documents")
}
```

**Table management:** `npx prisma db push` runs at pod startup (CMD in Dockerfile).
Idempotent — creates the table on first run, no-op on subsequent runs.

**`npx prisma generate`** runs at Docker build time to generate the typed client
into `node_modules/@prisma/client`. The schema file is only used at build time
and during `db push` — not at runtime.

**DB helpers in `db.js`:**
- `listDocuments()` — `findMany` ordered by `updatedAt DESC`
- `createDocument(title)` — `create` with auto UUID and title
- `fetchDocument(id)` — `findUnique`, returns `Bytes` or null
- `storeDocument(id, state)` — `upsert` (insert or update content)

---

### MySQL

**Database:** `collab` (separate from `playground`)
**User:** `collab` / `collab-kind`

**Two setup paths:**
- **Existing cluster:** run `scripts/collab-mysql-init-kind.sh` once as root
- **Fresh cluster:** `infra/helm/mysql/templates/init-configmap.yaml` is mounted
  at `/docker-entrypoint-initdb.d/` — MySQL runs the SQL automatically on first boot

---

### Redis

**Two separate uses, both managed by `@hocuspocus/extension-redis`:**

1. **Hot document state** — key per document, value = Yjs binary blob
2. **Pub/sub fan-out** — channel per document, cross-pod update delivery

The existing Redis instance from the kind cluster is reused. Hocuspocus uses
the `hocuspocus:` key prefix, avoiding collisions with playground's keys.

---

### React SPA (client)

**`/` — DocumentList:**
- `GET /api/documents` on mount → renders list with title and last-updated date
  (uses `doc.updatedAt` — Prisma returns camelCase field names)
- "New Document" button → `prompt()` for title → `POST /api/documents` →
  navigate to `/doc/:id`

**`/doc/:id` — Editor (two-component pattern):**

`Editor` (outer gate):
- Reads `sessionStorage.getItem('collab_user_name')` into React state
- If empty: renders `NameModal` — blocks until name is entered and confirmed
- If set: renders `EditorInner` with the confirmed name

`EditorInner` (only mounts after name is confirmed):
- Creates `Y.Doc` and `HocuspocusProvider` via `useMemo([id])`
- Destroys provider on unmount via `useEffect`
- Initialises TipTap with `StarterKit` (history disabled — Y.UndoManager handles
  undo), `Collaboration`, `CollaborationCursor`
- Cursor colour: `hashColor(userName)` — deterministic HSL from name string
- Renders formatting toolbar (Bold, Italic, H1, H2, bullet list, ordered list)
  and `EditorContent`

**Why two components:** `useEditor` is called at component mount time.
If name check and editor init were in the same component, the cursor would
always be set to 'Anonymous' (sessionStorage is empty on first render, before
the modal confirms). Splitting ensures `EditorInner` only mounts with a real name.

---

## Full User Flow

### Opening the document list

```
User opens http://host/
  → Express serves index.html (React SPA)
  → React Router renders DocumentList
  → GET /api/documents → Express → Prisma → MySQL SELECT → list rendered
  → updatedAt displayed as toLocaleDateString()
```

### Creating a new document

```
User clicks "New Document"
  → browser prompt() for title
  → POST /api/documents → Express → Prisma create (auto UUID)
  → Response: { id, title }
  → React navigates to /doc/{id}
```

### Joining a document (tab 1)

```
/doc/{id} renders Editor
  → sessionStorage empty → NameModal renders
  → User types "Alice" → sessionStorage.setItem('collab_user_name', 'Alice')
  → NameModal calls onConfirm('Alice') → Editor state updates → EditorInner mounts

EditorInner:
  → Y.Doc + HocuspocusProvider created (useMemo)
  → WebSocket ws://host/ opened (socketId = UUID assigned by Hocuspocus)
  → [ws] connect doc={id} socket={uuid} logged on server

  → extension-redis: check Redis "hocuspocus:doc:{id}"
    → miss (new doc) → extension-database: Prisma findUnique
    → null (new doc) → send empty Y.Doc to client

  → subscribe to Redis channel "hocuspocus:channel:{id}"

TipTap initialises with empty Y.Doc → blank editor
```

### Second user joins (tab 2, different pod in k8s)

```
/doc/{id} → NameModal → "Bob" → EditorInner mounts

HocuspocusProvider opens WebSocket to pod 2
  → extension-redis: check Redis "hocuspocus:doc:{id}"
    → hit (Alice's edits are in Redis) → send current Y.Doc blob to Bob
  → subscribe to Redis channel "hocuspocus:channel:{id}"

TipTap initialises with existing content → Bob sees Alice's work
CollaborationCursor: Bob's cursor appears in Alice's editor (and vice versa)
```

### Editing (real-time sync)

```
Alice types "Hello":
  TipTap → Y.XmlFragment mutation → Yjs binary update blob
  HocuspocusProvider sends blob over WebSocket to pod 1

Pod 1 (Alice's pod):
  hocuspocus.handleConnection receives update
  → extension-redis: update Redis key "hocuspocus:doc:{id}"
  → extension-redis: publish to Redis channel "hocuspocus:channel:{id}"
  → extension-database: schedule MySQL write (debounced)
  → [db] store doc={id} bytes={n} logged on store

Redis channel fan-out:
  Pod 2 (Bob's pod) receives published update
  → forwards binary blob to Bob's WebSocket

Bob's browser:
  Y.applyUpdate(ydoc, blob) → Y.XmlFragment updated
  → TipTap ProseMirror transaction → minimal DOM patch → Bob sees "Hello"
```

### Persisting and reloading

```
Last client disconnects from document:
  → extension-database store callback fires
  → db.storeDocument(id, state) → Prisma upsert → MySQL LONGBLOB

User refreshes page:
  → HocuspocusProvider reconnects
  → Redis hot state hit → served immediately
  → OR Redis expired → Prisma findUnique → blob sent to client
  → TipTap initialises with saved content ✓
```

---

## k8s / kind Deployment

```
kind cluster (local)
├── collab Deployment   (2 replicas — to test Redis fan-out)
│   image: ghcr.io/toddychen/service-collab:<sha>
│   port: 2070
│   env: REDIS_HOST, REDIS_PORT, DATABASE_URL
│   startup: prisma db push && node src/server.js
│
├── playground Deployment (2 replicas, port 8080)
├── Redis pod            (1 replica — shared)
├── MySQL pod            (1 replica — collab + playground DBs)
│
└── NodePort: 32070 (host) → 32070 (kind node) → 2070 (pod)
    (port-forward used until cluster is recreated with 32070 in extraPortMappings)
```

**Scaling test:** 2 collab pods → two browser tabs land on different pods.
Edits from tab 1 appear in tab 2 via Redis pub/sub.
Verify with: `kubectl -n collab logs -f <pod>` — watch `[ws] connect` and
`[db] store` lines on each pod.

---

## Dockerfile (node-services/collab/)

```dockerfile
# Stage 1: build React SPA (client has its own package.json and dev deps)
# Vite and React source never reach the final image
FROM node:24-alpine AS client-builder
WORKDIR /build/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

# Stage 2: production server — only server deps + compiled client bundle
FROM node:24-alpine AS production
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY prisma/ ./prisma/
# Generate Prisma client into node_modules at build time (reads schema.prisma)
RUN npx prisma generate
COPY src/ ./src/
# Rescue compiled React bundle from stage 1 into public/
COPY --from=client-builder /build/client/dist ./public
EXPOSE 2070
# Push schema to DB (idempotent) then start the server
CMD ["sh", "-c", "npx prisma db push && node src/server.js"]
```

---

## CI/CD

**GitHub Actions:** `.github/workflows/service-collab-docker.yml`
- Triggers on push to `main` when `node-services/collab/**` changes
- Multi-platform build: `linux/amd64,linux/arm64`
- Pushes to `ghcr.io/toddychen/service-collab:<git-sha>`
- No Java/Maven steps — entire build happens inside the Dockerfile

**Deploy to kind:**
```bash
./scripts/deploy-service-collab-kind.sh <git-sha>
kubectl -n collab port-forward svc/service-collab 2070:2070
```

---

## Verification Checklist

- [x] Prisma `db push` creates `collab_documents` table on first pod start
- [x] `GET /health` returns `{ status: 'ok' }`
- [x] `GET /api/documents` returns empty array for fresh DB
- [x] `POST /api/documents` creates a row, returns `{id, title}`
- [x] React SPA loads at `/`, shows document list with correct dates
- [x] Clicking "New Document" navigates to `/doc/:id`
- [x] Name modal appears on first visit, stores name in `sessionStorage`
- [x] TipTap editor renders with correct user name in cursor (not 'Anonymous')
- [x] Open same `/doc/:id` in second tab → second cursor visible with correct name
- [x] Type in tab 1 → text appears in tab 2 in real time
- [x] Refresh tab 1 → content reloaded from MySQL/Redis
- [ ] Deploy 2 collab pods to kind → confirm edits cross pods via Redis logs
- [ ] Kill one pod → surviving pod serves reconnected client, no data loss
