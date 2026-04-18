import { PrismaClient } from '@prisma/client'

const prisma = new PrismaClient()

export async function listDocuments() {
  return prisma.collabDocument.findMany({
    select: { id: true, title: true, createdAt: true, updatedAt: true },
    orderBy: { updatedAt: 'desc' },
  })
}

export async function createDocument(title) {
  return prisma.collabDocument.create({
    data: { title },
    select: { id: true, title: true },
  })
}

export async function fetchDocument(id) {
  const doc = await prisma.collabDocument.findUnique({
    where: { id },
    select: { content: true },
  })
  return doc?.content ?? null
}

export async function storeDocument(id, state) {
  await prisma.collabDocument.upsert({
    where: { id },
    update: { content: Buffer.from(state) },
    create: { id, content: Buffer.from(state) },
  })
}
