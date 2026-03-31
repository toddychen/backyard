# Dory Reminders API

Migrated from the Node.js dory-server. A personal reminders CRUD API backed
by an H2 file-based database via JPA.

## API Endpoints

| Method | Path | Query Params | Body | Response |
|--------|------|-------------|------|----------|
| GET | `/api/{version}/dory/reminders` | `owner` (required), `status` (default: `live`) | — | `200` array |
| POST | `/api/{version}/dory/reminders` | `owner` (required) | Reminder (no id) | `201` created |
| PUT | `/api/{version}/dory/reminders/{id}` | — | Full reminder fields | `200` updated, `404` |
| PATCH | `/api/{version}/dory/reminders/{id}/done` | — | — | `200` updated, `404` |
| PATCH | `/api/{version}/dory/reminders/{id}/snooze` | — | `{ "minutes": N }` | `200` updated, `404` |
| DELETE | `/api/{version}/dory/reminders/{id}` | — | — | `204`, `404` |

## Reminder JSON Shape

```json
{
  "id": "uuid",
  "owner": "string",
  "title": "string",
  "type": "one-off | recurring",
  "recurrenceType": "daily | weekly | monthly | yearly | null",
  "nextOccurrence": "ISO 8601 UTC",
  "snoozeUntil": "ISO 8601 UTC | null",
  "description": "string | null",
  "status": "live | done",
  "completedAt": "ISO 8601 UTC | null"
}
```

## Endpoint Behavior

### GET `/reminders`
- `?status` defaults to `live` — omit to get active reminders
- Pass `?status=done` to fetch past (completed) reminders

### POST `/reminders`
- Server generates UUID and sets `status = "live"`
- `owner` taken from query param

### PUT `/reminders/{id}`
- Updates content fields: title, type, recurrenceType, nextOccurrence,
  snoozeUntil, description
- Does not change `status` or `completedAt`

### PATCH `/reminders/{id}/done`
- **one-off**: sets `status = "done"`, `completedAt = now()`
- **recurring**: computes `nextOccurrence = current nextOccurrence + 1
  recurrenceType interval`, clears `snoozeUntil`; status unchanged

### PATCH `/reminders/{id}/snooze` `{ "minutes": N }`
- Base time = `snoozeUntil` if non-null, otherwise `nextOccurrence`
- Sets `snoozeUntil = base + N minutes`
- Snooze stacks — repeated snoozes push further from last snoozed time

### DELETE `/reminders/{id}`
- Hard delete; returns `204`

## Database Schema

Single table `reminder` with composite index on `(owner, status)`.

| Column | Type | Nullable | Notes |
|--------|------|----------|-------|
| `id` | UUID | NO | Primary key |
| `owner` | VARCHAR(100) | NO | |
| `title` | VARCHAR(255) | NO | |
| `type` | VARCHAR(20) | NO | `one-off` or `recurring` |
| `recurrence_type` | VARCHAR(20) | YES | null for one-off |
| `next_occurrence` | TIMESTAMP WITH TIME ZONE | NO | |
| `snooze_until` | TIMESTAMP WITH TIME ZONE | YES | |
| `description` | VARCHAR(1000) | YES | |
| `status` | VARCHAR(20) | NO | default `live` |
| `completed_at` | TIMESTAMP WITH TIME ZONE | YES | |

## Design Notes

- Recurrence calculation is server-side — client never computes nextOccurrence
- Owner is a plain string passed as query param; no auth system yet
- H2 file stored at `./data/dory` relative to the running jar
- Easy upgrade path to PostgreSQL: change JDBC URL + swap driver dependency
