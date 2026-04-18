# Collaborative Editing — Theory, Algorithms, and Key Concepts

A complete reference covering Operational Transformation (OT) and
Conflict-free Replicated Data Types (CRDT), with worked examples and
clarifications for every non-obvious detail.

---

## Table of Contents

1. [The Core Problem](#1-the-core-problem)
2. [Operational Transformation (OT)](#2-operational-transformation-ot)
   - History
   - Transform functions with examples
   - The Jupiter Protocol (client-server OT)
   - Multi-pending-op walkthrough
   - Shadow pending queue
   - Revision tagging and send timing
   - Rich text challenges
   - TP1 and TP2
   - Intent preservation
   - Undo/redo
   - Google Docs today
3. [CRDT — Conflict-free Replicated Data Types](#3-crdt--conflict-free-replicated-data-types)
   - The root insight
   - RGA — Replicated Growable Array
   - YATA — the Yjs algorithm
   - Concurrent insert resolution with examples
   - Tombstones
   - Causality and causal delivery
   - What "concurrent" formally means
   - Yjs data types
   - Persistence model
   - Garbage collection
   - Undo/redo in Yjs
4. [OT vs CRDT — Side by Side](#4-ot-vs-crdt--side-by-side)
5. [Transport — WebSockets](#5-transport--websockets)
6. [Remaining Hard Problems](#6-remaining-hard-problems)

---

## 1. The Core Problem

When two users edit the same document simultaneously, their edits conflict.
The naive approach — last write wins — loses one user's work. The correct
approach must satisfy two properties:

- **Convergence**: all clients eventually reach the same document state
- **Intent preservation**: the result reflects what each user intended

Both OT and CRDTs guarantee convergence. Intent preservation is harder and
neither approach solves it perfectly for all operations.

---

## 2. Operational Transformation (OT)

**Full name:** Operational Transformation
**Invented:** 1989 (Ellis & Gibbs), popularised by Google Wave and Google Docs

### History

| Year | Event |
|---|---|
| 1989 | OT invented for GROVE (GRoup Outline Viewing Editor) |
| 1995 | Jupiter protocol (Xerox PARC) — simplifies OT to client-server model |
| 2006 | Google Docs launches, uses Jupiter-style OT |
| 2009 | Google Wave uses full P2P OT — notoriously buggy, shut down 2010 |
| 2011 | CRDTs gain traction academically |
| 2018+ | CRDTs become the industry default for new systems |

### The Core Idea

Every keystroke becomes an **operation**: `insert("X", position=3)` or
`delete(position=5)`. When two operations are made concurrently from the same
base state, one must be **transformed** so both can apply and produce the same
result.

**Example — two concurrent inserts:**

```
Base document: "AC"

User A: insert("B", 1)  →  wants "ABC"
User B: insert("D", 1)  →  wants "ADC"

Naive (no transform): apply A then B at position 1 → "ADBC"  ← wrong
With transform:       apply A → "ABC", transform B: insert("D", 2) → "ABDC"  ✓
```

### Transform Functions

Four cases for plain text (insert/delete):

**insert vs insert:**
```
xform(insert(p1, c1), insert(p2, c2)):
  p1 < p2  →  (insert(p1, c1),   insert(p2+1, c2))
  p1 > p2  →  (insert(p1+1, c1), insert(p2, c2))
  p1 == p2 →  tiebreak by user ID; loser shifts right +1
```

**delete vs delete:**
```
xform(delete(p1), delete(p2)):
  p1 < p2  →  (delete(p1),   delete(p2-1))
  p1 > p2  →  (delete(p1-1), delete(p2))
  p1 == p2 →  (noop, noop)   ← same character, only delete once
```

**insert vs delete:**
```
xform(insert(p1, c), delete(p2)):
  p1 <= p2  →  (insert(p1, c),   delete(p2+1))
  p1 >  p2  →  (insert(p1-1, c), delete(p2))
```

**delete vs insert:** mirror of above.

### End-to-End Example

```
Base: "Hello"   (positions 0-4)

User A inserts "!" at 5  →  local: "Hello!"
User B deletes "o" at 4  →  local: "Hell"

Server receives A first:
  Applies insert("!", 5)  →  server: "Hello!"
  Transforms B's delete(4) against insert("!", 5):
    p_delete(4) < p_insert(5)  →  delete unchanged
  Applies delete(4)  →  server: "Hell!"

Client B receives A's insert("!", 5):
  B has pending [delete(4)].
  Transform insert("!",5) against delete(4):
    p_insert(5) > p_delete(4)  →  insert shifts left → insert("!", 4)
  Applies insert("!", 4) to "Hell"  →  "Hell!"  ✓

Both converge to "Hell!"
```

---

### The Jupiter Protocol — Production Client-Server OT

Peer-to-peer OT is extremely complex (see TP2 below). The Jupiter protocol
simplifies everything with one insight: **one server, one linear history**.

Each operation carries a **revision number** = how many server ops the client
has seen so far.

```
Client sends: (op, rev=R)
Server is at revision N >= R.
Server transforms op against ops[R..N] in sequence.
Server appends transformed op at revision N+1.
Server broadcasts to all clients.
```

Because there is only one server and one linear log, the hard TP2 problem
disappears entirely.

---

### Multi-Pending-Op Walkthrough

**Setup:**
- Server doc at revision R: `"Hello World"` — applies A then B (two remote ops)
- Client at revision R, has pending local ops C then D (not yet sent)

**Server op A = `insert("!", 11)` received by client:**

Client pending = `[C=insert("_",5), D=delete(1)]`

Transform A through pending queue:
```
A₁ = xform(insert("!",11), insert("_",5)):
  p_A(11) > p_C(5)  →  A₁ = insert("!", 12)

A_final = xform(insert("!",12), delete(1)):
  p_A(12) > p_D(1)  →  A_final = insert("!", 11)

Apply insert("!",11) to local doc "Hllo_ World"  →  "Hllo_ World!"
```

Rebase shadow pending (for transforming the next server op):
```
C_shadow = xform(C=insert("_",5), A=insert("!",11)):
  p_C(5) < p_A(11)  →  unchanged → C_shadow = insert("_",5)

D_shadow = xform(D=delete(1), A₁=insert("!",12)):
  p_D(1) < p_A₁(12)  →  unchanged → D_shadow = delete(1)
```

**Server op B = `delete(6)` received by client:**

Transform B through shadow pending `[C_shadow, D_shadow]`:
```
B₁ = xform(delete(6), insert("_",5)):
  p_B(6) > p_C_shadow(5)  →  B₁ = delete(7)

B_final = xform(delete(7), delete(1)):
  p_B(7) > p_D_shadow(1)  →  B_final = delete(6)

Apply delete(6) to "Hllo_ World!"  →  "Hllo_ orld!"
```

Both converge to `"Hllo_ orld!"` ✓

---

### The Shadow Pending Queue

The shadow pending queue is **the client's internal bookkeeping**, never sent
to the server.

```
Original pending [C, D]    →  sent to server as-is (tagged rev=R)
Shadow pending   [C', D']  →  used to transform the next server op
```

After each incoming server op, the client:
1. Transforms the server op through the current shadow → applies to local doc
2. Replaces the shadow with the rebased versions for the next server op

Once the original ops are sent, the originals can be discarded. The shadow
IS the new pending from that point forward.

---

### Revision Tagging and Send Timing

The revision tag on an outgoing op is stamped **at send time, not creation time**.

- If server op A arrives **before** client sends C:
  - Client rebases C → C', tags with `rev=R+1`, sends C'
  - Server only transforms C' against ops after R+1 — less work

- If server op A arrives **after** client sends C:
  - C was sent with `rev=R`, server transforms it against `[A, B, ...]`
  - Client cannot recall or update C

**When the client types a new op E after receiving A:**

E is made against the current local doc (which already has A applied).
E's revision tag = current server revision seen (R+1).
E drops into the shadow pending tail **without rebasing** — it was born
in the post-A world, same base as the shadow.

```
Client pending before E:  [C, D]    shadow: [C', D']
Client types E:           [C, D, E] shadow: [C', D', E]  ← E enters directly
```

---

### Rich Text OT Challenges

Plain text OT is tractable. Rich text is where OT breaks down:

**Formatting spans:**
```
A: bold(0, 5)       — make "Hello" bold
B: insert("! ", 5)  — insert after "Hello"

Should "!" be bold? OT has no way to know A's intent.
Google Docs resolves this by making each character carry its own
attribute map — no spans, just per-character attributes.
```

**Structural ops (tables, lists):**
Transform functions for concurrent row/column operations in tables require
handling two dimensions simultaneously — correctness is very hard to prove.

**Undo:**
Undo in a collaborative session requires *selective undo* — reversing your
own op in the context of everything that happened after it:

```
A typed insert("X", 2) at time T.
Since then: insert("Y", 3) happened.

Naive undo: delete(2)  →  deletes wrong character.
Correct:    transform delete(2) against insert("Y", 3)
            →  delete(2) stays  →  deletes X correctly ✓
```

---

### TP1 and TP2

**TP1 (Transformation Property 1):** Two concurrent ops must converge
regardless of application order. Jupiter satisfies this.

**TP2 (Transformation Property 2):** When transforming through a sequence
of already-transformed ops, the result must still be correct. Required for
P2P OT. Jupiter sidesteps TP2 by imposing a single linear server order.

**Why this matters:** Google Wave required TP2 for its P2P model. Many
published algorithms claimed TP2 compliance but were later proven incorrect.
This is the fundamental reason Wave failed. Jupiter/Google Docs avoids TP2
entirely — and is correct and reliable as a result.

---

### Google Docs Today (2024)

Google Docs almost certainly still uses OT or a close derivative:

- Google's 2010 blog post describes Jupiter-style OT
- Google has **never announced** a migration to CRDTs
- OT's server-authority model is ideal for access control, version history,
  "suggest edits" mode — all natural with a central op log
- Migrating billions of documents to a new sync algorithm is enormous risk

Google Wave (P2P OT + TP2) was a separate, failed experiment. Google Docs
uses the simpler client-server Jupiter model which is correct and stable.

---

## 3. CRDT — Conflict-free Replicated Data Types

**Full name:** Conflict-free Replicated Data Type
**Mainstream since:** ~2018, invented academically 2011 (Shapiro et al.)

### The Root Insight

OT's complexity comes from one decision: **positions are indices**. Indices
shift when anything is inserted or deleted, requiring transforms to chase
the moving target.

CRDTs make a different decision: **every character gets a permanent, globally
unique identity**. Instead of "insert X at position 3," a CRDT says "insert X
**after the node with ID `e:42`**, wherever that node is." No shifting.
Nothing to transform.

---

### RGA — Replicated Growable Array

The dominant CRDT algorithm for ordered sequences (text). Invented 2011,
basis for Yjs.

Each character is a **node**:
```
Node {
  id:      (clientId, counter)   // globally unique
  value:   char                  // the character
  origin:  nodeId                // "I was inserted right after this node"
  deleted: bool                  // tombstone flag
}
```

**Inserting "X" between "e" and "l":**
```
Before: [H:A1] → [e:A2] → [l:A3]
Insert X after A2:  { id:(A,4), value:"X", origin:A2 }
After:  [H:A1] → [e:A2] → [X:A4] → [l:A3]
```

No position arithmetic. The node simply declares its parent.

---

### YATA — The Yjs Algorithm

Yjs uses a refinement of RGA called **YATA (Yet Another Transformation
Approach)**. The key addition over basic RGA:

```
Node {
  id:      (clientId, counter)
  value:   char
  origin:  nodeId    // left neighbor AT INSERTION TIME  (immutable)
  right:   nodeId    // right neighbor AT INSERTION TIME (immutable)
  deleted: bool
}
```

**Critical point: `origin` and `right` are birth records, not live pointers.**
They record the world as the node saw it when it was created. They never
update as neighbors change.

```
Before:  [A] → [B] → [C]
Insert X between A and B:  X.origin = A, X.right = B

After inserting Z between A and X:
  [A] → [Z] → [X] → [B] → [C]
  X's origin is still A.  X's right is still B.
  They describe X's birth moment, not current neighbors.
```

---

### Concurrent Insert Resolution — Full Example

**Setup:**
```
Doc: "ABC"   nodes: [A:1][B:2][C:3]

Client 1's ops (sequential):
  1. Insert X between A and B:   X = { id:(1,4), origin:A:1, right:B:2 }
     Doc: [A:1][X:(1,4)][B:2][C:3]   "AXBC"

  2. Insert Y between A and X:   Y = { id:(1,5), origin:A:1, right:X:(1,4) }
     Doc: [A:1][Y:(1,5)][X:(1,4)][B:2][C:3]   "AYXBC"

  3. Insert Z between A and Y:   Z = { id:(1,6), origin:A:1, right:Y:(1,5) }
     Doc: [A:1][Z:(1,6)][Y:(1,5)][X:(1,4)][B:2][C:3]   "AZYXBC"

Client 2 receives X, then inserts P between A and X:
  Client 2's world: [A:1][X:(1,4)][B:2][C:3]   (never saw Y or Z)
  P = { id:(2,5), origin:A:1, right:X:(1,4) }
```

**Client 1 receives P:**

Client 1 doc: `[A:1][Z:(1,6)][Y:(1,5)][X:(1,4)][B:2][C:3]`

First: check P's dependencies. P.origin=A:1 exists ✓. P.right=X:(1,4) exists ✓.
P can be integrated immediately.

YATA scans from after A:1 to X:(1,4), evaluating competitors (same origin):

```
Z: { origin:A:1, right:Y:(1,5) }
Y: { origin:A:1, right:X:(1,4) }
P: { origin:A:1, right:X:(1,4) }   ← incoming

Encounter Z: Z.right=Y, P.right=X.
  Y comes before X in document.
  Z.right (Y) is before P.right (X)  →  Z goes before P, skip Z.

Encounter Y: Y.right=X == P.right=X  →  same right boundary.
  →  truly concurrent (both born into gap A→X without seeing each other)
  →  tiebreak: clientId 1 < clientId 2  →  Y before P.
  Skip Y.

Reach X:  stop.  Insert P here.
```

**Result:** `[A:1][Z:(1,6)][Y:(1,5)][P:(2,5)][X:(1,4)][B:2][C:3]`  →  `"AZYPXBC"` ✓

**Why the `right` field is essential:**

With only `origin`, you cannot distinguish:
- Case 1 — truly concurrent: client 2 did NOT see Y when inserting P  →  `right=X`
- Case 2 — intentional before Y: client 2 SAW Y but chose to go left of it  →  `right=Y`

The `right` value is forensic evidence of what the inserter saw. Same `origin`
AND same `right` = truly concurrent. Different `right` = one client knew about
the other's insert. YATA's ordering rule handles both cases correctly.

---

### What "Concurrent" Formally Means

Two events A and B are **concurrent** (written A ∥ B) if neither happened
before the other in the "happens-before" sense (Lamport 1978):

```
A → B  means B's creator had seen A before creating B
A ∥ B  means neither had seen the other's event
```

**Vector clocks** are the general tool for proving concurrency:
```
V_A = {A:5, B:3}   A has done 5 ops, seen 3 of B's
V_B = {A:3, B:5}   B has done 5 ops, seen 3 of A's

Neither dominates the other  →  A ∥ B  →  concurrent
```

**In Yjs specifically**, you do not need vector clocks because the CRDT
structure encodes causality directly:

- The node ID `(clientId, counter)` is a **per-client sequence number**
  (simpler than Lamport — the local counter never advances on receiving
  messages from others)
- Causality is tracked by direct dependency: if P.origin = X, P cannot be
  applied until X exists locally (buffered until then)
- Concurrency is detected structurally: same `origin` + same `right` = concurrent

---

### Tombstones — Deletion in CRDTs

You cannot truly remove a node because other nodes may reference it as their
`origin`. Instead, mark it as a **tombstone** — still in the structure,
rendered invisibly:

```
Delete "e" (node A2):
  A2.deleted = true

Internal: [H:A1] → [e:A2, deleted] → [X:A4] → [l:A3]
Rendered: "HXlo"
```

Tombstones accumulate forever. A document edited 100,000 times may have
50,000 tombstones for 5,000 live characters. Garbage collection requires
confirming all clients have seen the deletion — hard when clients go offline.

---

### Yjs Data Types

| Type | Structure | Merge rule | Used for |
|---|---|---|---|
| `Y.Text` | YATA linked list | origin+right tiebreak | Plain text, code |
| `Y.Array` | YATA linked list | origin+right tiebreak | Lists of any values |
| `Y.Map` | Hash map | Last Write Wins (by clock) | Attributes, metadata |
| `Y.XmlFragment` | YATA tree | YATA order + LWW attrs | Rich text (TipTap) |

**Y.Map uses Last Write Wins:** two concurrent writes to the same key — the
one with the higher per-client counter wins. The other is discarded. Simple
and correct for most attribute use cases (bold, color, title). Wrong for
numeric increment/decrement — use a dedicated counter CRDT for those.

**Y.XmlFragment composes:**
```
Y.XmlFragment (doc)
  └─ Y.XmlElement (paragraph)       ← ordered by YATA
       ├─ attributes: Y.Map          ← LWW per attribute key
       └─ Y.XmlText                  ← Y.Text (YATA) for the characters
```

---

### Persistence — The Binary Blob

The entire Y.Doc serialises to a **binary blob** (Yjs's own compact encoding):

```javascript
const binary = Y.encodeStateAsUpdate(ydoc)   // Uint8Array
Y.applyUpdate(ydoc, binary)                  // restore from binary
```

This blob contains all nodes, tombstones, and client IDs — everything needed
for future merges. It is the canonical form. HTML, Markdown, PDF are all
**derived projections** — they lose CRDT structure and cannot round-trip back
to the original Y.Doc binary.

---

### Hot and Cold Persistence

```
Redis key "hocuspocus:doc:xyz"   ← hot state (fast, in-memory)
MySQL LONGBLOB column             ← cold state (durable, persistent)

Client joins:
  Check Redis → hit: send blob immediately
  Redis miss → load from MySQL, warm Redis, send to client

Client edits:
  Yjs update → extension-redis updates Redis key instantly
  extension-database saves to MySQL periodically / on last client leaving
```

---

### Garbage Collection of Tombstones

Yjs has a garbage collector but it can only safely delete tombstones when it
can prove no client will ever reference them as an `origin` again — i.e., all
known clients have seen the deletion. With offline clients this is hard to
guarantee. In practice, tombstone accumulation is rarely a problem for
documents that don't have millions of edits.

---

### Undo/Redo in Yjs

```javascript
const undoManager = new Y.UndoManager(ytext)
undoManager.undo()   // reverts only THIS USER's last edit
undoManager.redo()
```

**Key behaviour:** UndoManager only undoes the local user's own edits. If A
types "Hello" and B types " World", A's undo removes "Hello" but leaves
" World" intact. Internally it rebases the undo operation against everything
that happened since — same conceptual problem as OT selective undo.

---

## 4. OT vs CRDT — Side by Side

| | OT (Jupiter) | CRDT (Yjs/YATA) |
|---|---|---|
| Position model | Indices (shift on edit) | Unique IDs (never change) |
| Concurrent inserts | Transform functions + tiebreak | Deterministic ID sort via right field |
| Deletion | Delete op, positions shift | Tombstone, node stays forever |
| Central server | Required (sequencer) | Not required — any topology |
| P2P / offline-first | Hard (TP2 unsolvable for general rich text) | Native — merge updates in any order |
| Revision numbers | Required on every op | Not needed |
| Transform functions | One per op-type pair (hard to write correctly) | None |
| New operation type | Write new transform functions | Define merge rule for that type |
| Memory | Only live characters | Live + all tombstones |
| k8s horizontal scale | Sticky sessions OR Redis fan-out | Redis fan-out (stateless pods) |
| Offline reconnect | Transform pending against full log | Merge binary blobs, any order |
| Who uses it | Google Docs, Etherpad, ShareDB | Figma, Notion, Linear, Yjs ecosystem |

**The fundamental difference in one sentence:**
> OT tracks *where* things are and transforms positions; CRDT tracks *what*
> things are and merges by identity.

---

## 5. Transport — WebSockets

Collaborative editing requires a channel that is:
- **Bidirectional** — client sends ops to server, server fans them to clients
- **Persistent** — stays open; no reconnect overhead per op
- **Low latency** — every keystroke must reach others in ~100ms
- **Ordered** — ops must arrive in send order (TCP guarantees this)

WebSockets satisfy all four. Before WebSockets (pre-2011), systems used
**long polling** — HTTP requests held open until an op arrived, then
immediately re-opened. Much higher overhead; Google Docs migrated away from it.

**Connection lifecycle:**
```
Connect:    client opens WS → sends "join doc-123 at rev=R"
            server sends ops[R..current] to catch client up

Heartbeat:  client pings every ~30s, server pongs
            missed pong → client reconnects

Reconnect:  same join flow; sends pending ops with current rev tag
            server transforms them against everything missed while offline

Disconnect: server removes client from subscriber set after missed heartbeat
```

---

## 6. Remaining Hard Problems

Both OT and CRDTs share these unsolved or partially-solved problems:

**Intent preservation:**
When A bolds "Hello" and B deletes "Hello" concurrently, both systems
converge correctly to an empty document — but A's formatting intent is lost.
No algorithm recovers semantic intent. This is a fundamental limitation.

**Move operations:**
Moving a block of text is delete+insert in both systems. If B edits the
block while A moves it, B's edits apply to the original (now deleted)
location. Yjs has no native move op. Automerge 2.0 has an experimental one.

**Tree reparenting:**
If A moves node N under X and B moves node N under Y simultaneously, naive
CRDT resolution may clone N under both parents — wrong. Requires special
handling not in standard Yjs.

**Large documents:**
Yjs loads the entire Y.Doc into memory on every client. A 10 MB Yjs binary
means 10 MB in every browser tab. At Google Docs scale, documents are chunked
into sections loaded lazily.

**Reconnect storms:**
When a server restarts, all clients reconnect simultaneously. Exponential
backoff with jitter (built into Hocuspocus) mitigates this, as does keeping
Redis as the hot state so reconnects bypass the database.
