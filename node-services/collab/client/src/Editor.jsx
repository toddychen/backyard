import { useEffect, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useEditor, EditorContent } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import Collaboration from '@tiptap/extension-collaboration'
import CollaborationCursor from '@tiptap/extension-collaboration-cursor'
import * as Y from 'yjs'
import { HocuspocusProvider } from '@hocuspocus/provider'
import NameModal from './NameModal.jsx'

function hashColor(str) {
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  const h = Math.abs(hash) % 360
  return `hsl(${h}, 65%, 50%)`
}

const editorStyles = `
  .collab-editor-wrap {
    max-width: 720px; margin: 40px auto; padding: 0 16px;
  }
  .collab-toolbar {
    display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap;
  }
  .collab-toolbar button {
    padding: 4px 10px; border: 1px solid #d1d5db; border-radius: 4px;
    background: #fff; cursor: pointer; font-size: 13px;
  }
  .collab-toolbar button.is-active {
    background: #2563eb; color: #fff; border-color: #2563eb;
  }
  .collab-toolbar .back-btn {
    margin-left: auto; background: #f3f4f6; border-color: #d1d5db;
  }
  .tiptap {
    min-height: 400px; background: #fff; border: 1px solid #e5e7eb;
    border-radius: 8px; padding: 20px 24px; font-size: 16px;
    line-height: 1.6; outline: none;
  }
  .tiptap h1 { font-size: 2em; font-weight: 700; margin: 0.5em 0; }
  .tiptap h2 { font-size: 1.5em; font-weight: 700; margin: 0.5em 0; }
  .tiptap ul, .tiptap ol { padding-left: 1.5em; }
  .tiptap p { margin: 0.25em 0; }
  /* Collaboration cursor */
  .collaboration-cursor__caret {
    border-left: 2px solid; border-right: 0; margin-left: -1px;
    pointer-events: none; position: relative; word-break: normal;
  }
  .collaboration-cursor__label {
    border-radius: 3px 3px 3px 0; color: #fff; font-size: 12px;
    font-weight: 600; left: -2px; line-height: normal; padding: 0.1rem 0.3rem;
    position: absolute; top: -1.4em; user-select: none; white-space: nowrap;
  }
`

function Toolbar({ editor }) {
  if (!editor) return null
  return (
    <div className="collab-toolbar">
      <button onClick={() => editor.chain().focus().toggleBold().run()}
        className={editor.isActive('bold') ? 'is-active' : ''}>B</button>
      <button onClick={() => editor.chain().focus().toggleItalic().run()}
        className={editor.isActive('italic') ? 'is-active' : ''}><em>I</em></button>
      <button onClick={() => editor.chain().focus().toggleHeading({ level: 1 }).run()}
        className={editor.isActive('heading', { level: 1 }) ? 'is-active' : ''}>H1</button>
      <button onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()}
        className={editor.isActive('heading', { level: 2 }) ? 'is-active' : ''}>H2</button>
      <button onClick={() => editor.chain().focus().toggleBulletList().run()}
        className={editor.isActive('bulletList') ? 'is-active' : ''}>• List</button>
      <button onClick={() => editor.chain().focus().toggleOrderedList().run()}
        className={editor.isActive('orderedList') ? 'is-active' : ''}>1. List</button>
    </div>
  )
}

export default function Editor() {
  const { id } = useParams()
  const navigate = useNavigate()

  const { ydoc, provider } = useMemo(() => {
    const ydoc = new Y.Doc()
    const wsUrl = `ws://${location.host}`
    const provider = new HocuspocusProvider({ url: wsUrl, name: id, document: ydoc })
    return { ydoc, provider }
  }, [id])

  useEffect(() => {
    return () => provider.destroy()
  }, [provider])

  const userName = sessionStorage.getItem('collab_user_name') || 'Anonymous'
  const userColor = hashColor(userName)

  const editor = useEditor({
    extensions: [
      StarterKit.configure({ history: false }),
      Collaboration.configure({ document: ydoc }),
      CollaborationCursor.configure({
        provider,
        user: { name: userName, color: userColor },
      }),
    ],
  })

  return (
    <NameModal>
      <style>{editorStyles}</style>
      <div className="collab-editor-wrap">
        <div className="collab-toolbar">
          <button className="back-btn" onClick={() => navigate('/')}>← Back</button>
        </div>
        <Toolbar editor={editor} />
        <EditorContent editor={editor} />
      </div>
    </NameModal>
  )
}
