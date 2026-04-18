# Stage 1: build the React SPA (client has its own package.json and dev dependencies)
# This stage is discarded after — Vite and React source never reach the final image
FROM node:24-alpine AS client-builder
WORKDIR /build/client
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

# Stage 2: production server — only server deps + the compiled client bundle
FROM node:24-alpine AS production
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY prisma/ ./prisma/
# Generate Prisma client into node_modules at build time (reads schema.prisma)
RUN npx prisma generate
COPY src/ ./src/
# Rescue the compiled React bundle from stage 1 into public/
COPY --from=client-builder /build/client/dist ./public
EXPOSE 2070
# Push schema to DB (idempotent) then start the server
CMD ["sh", "-c", "npx prisma db push && node src/server.js"]
