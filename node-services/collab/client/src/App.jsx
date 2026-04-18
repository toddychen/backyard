import { BrowserRouter, Routes, Route } from 'react-router-dom'
import DocumentList from './DocumentList.jsx'
import Editor from './Editor.jsx'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<DocumentList />} />
        <Route path="/doc/:id" element={<Editor />} />
      </Routes>
    </BrowserRouter>
  )
}
