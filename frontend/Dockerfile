# ── Stage 1: build ────────────────────────────────────────────────────────────
# Produces the Angular production bundle under /app/dist/hmis-web/browser.
FROM node:22-alpine AS build

WORKDIR /app

# Install dependencies separately so Docker can cache this layer.
COPY package*.json ./
RUN npm ci

# Copy source and build (defaultConfiguration is production per angular.json).
COPY . .
RUN npm run build

# ── Stage 2: serve ────────────────────────────────────────────────────────────
# Minimal nginx image; only the compiled browser assets are copied over.
FROM nginx:alpine

# SPA routing + caching rules (see nginx.conf for details).
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Angular 18 application builder writes the browser bundle to dist/<project>/browser.
COPY --from=build /app/dist/hmis-web/browser /usr/share/nginx/html

EXPOSE 80
# Default nginx CMD inherited from base image.
