import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'

const styles = {
  container: { maxWidth: 640, margin: '60px auto', padding: '0 16px' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 },
  title: { fontSize: 24, fontWeight: 700 },
  createBtn: {
    padding: '8px 16px', background: '#2563eb', color: '#fff',
    border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 14,
  },
  list: { listStyle: 'none' },
  item: {
    display: 'flex', justifyContent: 'space-between', alignItems: 'center',
    padding: '12px 16px', background: '#fff', borderRadius: 8,
    marginBottom: 8, border: '1px solid #e5e7eb', cursor: 'pointer',
  },
  docTitle: { fontWeight: 500 },
  docDate: { fontSize: 12, color: '#6b7280' },
  empty: { color: '#6b7280', textAlign: 'center', marginTop: 48 },
}

export default function DocumentList() {
  const navigate = useNavigate()
  const [docs, setDocs] = useState([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch('/api/documents')
      .then((r) => r.json())
      .then((data) => setDocs(data))
      .finally(() => setLoading(false))
  }, [])

  async function handleCreate() {
    const title = prompt('Document title:', 'Untitled') ?? 'Untitled'
    const res = await fetch('/api/documents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    })
    const doc = await res.json()
    navigate(`/doc/${doc.id}`)
  }

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <h1 style={styles.title}>Documents</h1>
        <button style={styles.createBtn} onClick={handleCreate}>+ New Document</button>
      </div>

      {loading && <p style={styles.empty}>Loading…</p>}
      {!loading && docs.length === 0 && (
        <p style={styles.empty}>No documents yet. Create one to get started.</p>
      )}
      <ul style={styles.list}>
        {docs.map((doc) => (
          <li key={doc.id} style={styles.item} onClick={() => navigate(`/doc/${doc.id}`)}>
            <span style={styles.docTitle}>{doc.title}</span>
            <span style={styles.docDate}>
              {new Date(doc.updatedAt).toLocaleDateString()}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
