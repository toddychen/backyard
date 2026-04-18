import { Server } from '@hocuspocus/server'
import { Redis } from '@hocuspocus/extension-redis'
import { Database } from '@hocuspocus/extension-database'
import express from 'express'
import { createServer } from 'http'
import { WebSocketServer } from 'ws'
import { listDocuments, createDocument, fetchDocument, storeDocument } from './db.js'

const REDIS_HOST = process.env.REDIS_HOST || ''
const REDIS_PORT = parseInt(process.env.REDIS_PORT || '6379', 10)
const PORT = parseInt(process.env.PORT || '2070', 10)

const extensions = [
  new Database({
    fetch: async ({ documentName: id }) => fetchDocument(id),
    store: async ({ documentName: id, state }) => storeDocument(id, state),
  }),
]

if (REDIS_HOST) {
  extensions.unshift(
    new Redis({ host: REDIS_HOST, port: REDIS_PORT })
  )
}

const hocuspocus = Server.configure({
  extensions,
  async onConnect({ documentName, socketId }) {
    console.log(`[ws] connect    doc=${documentName} socket=${socketId}`)
  },
  async onDisconnect({ documentName, socketId }) {
    console.log(`[ws] disconnect doc=${documentName} socket=${socketId}`)
  },
})

const app = express()
app.use(express.json())

app.get('/health', (_req, res) => {
  res.json({ status: 'ok' })
})

app.get('/api/documents', async (_req, res) => {
  try {
    const docs = await listDocuments()
    res.json(docs)
  } catch (err) {
    console.error('listDocuments error', err)
    res.status(500).json({ error: 'internal server error' })
  }
})

app.post('/api/documents', async (req, res) => {
  try {
    const title = req.body?.title || 'Untitled'
    const doc = await createDocument(title)
    res.status(201).json(doc)
  } catch (err) {
    console.error('createDocument error', err)
    res.status(500).json({ error: 'internal server error' })
  }
})

app.use(express.static('public'))
app.get('*', (_req, res) => {
  res.sendFile('index.html', { root: 'public' })
})

const httpServer = createServer(app)

const wss = new WebSocketServer({ noServer: true })
httpServer.on('upgrade', (request, socket, head) => {
  wss.handleUpgrade(request, socket, head, (ws) => {
    hocuspocus.handleConnection(ws, request)
  })
})

httpServer.listen(PORT, () => {
  console.log(`collab service listening on :${PORT}`)
})
