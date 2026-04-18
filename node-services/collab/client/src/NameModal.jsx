import { useState } from 'react'

const overlay = {
  position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
  display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999,
}
const modal = {
  background: '#fff', borderRadius: 10, padding: '32px 28px',
  minWidth: 320, boxShadow: '0 10px 40px rgba(0,0,0,0.2)',
}
const inputStyle = {
  width: '100%', padding: '8px 12px', fontSize: 15,
  border: '1px solid #d1d5db', borderRadius: 6, marginTop: 12, marginBottom: 16,
}
const btnStyle = {
  width: '100%', padding: '10px', background: '#2563eb', color: '#fff',
  border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 15, fontWeight: 600,
}

export default function NameModal({ children }) {
  const stored = sessionStorage.getItem('collab_user_name')
  const [name, setName] = useState('')
  const [confirmed, setConfirmed] = useState(!!stored)

  function handleSubmit(e) {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return
    sessionStorage.setItem('collab_user_name', trimmed)
    setConfirmed(true)
  }

  if (confirmed) return children

  return (
    <>
      <div style={overlay}>
        <div style={modal}>
          <h2 style={{ fontWeight: 700, fontSize: 18 }}>Enter your name</h2>
          <p style={{ color: '#6b7280', fontSize: 14, marginTop: 6 }}>
            Your name will appear as a cursor label to other users.
          </p>
          <form onSubmit={handleSubmit}>
            <input
              style={inputStyle}
              autoFocus
              placeholder="Your name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <button style={btnStyle} type="submit">Join</button>
          </form>
        </div>
      </div>
    </>
  )
}
