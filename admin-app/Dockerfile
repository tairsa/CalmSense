# CalmSense admin dashboard image.
#
# Multi-stage: build the React app with Node, then serve the static files with
# nginx (which also proxies /api -> the backend container). Runs on amd64 and
# arm64 (Raspberry Pi 5).
#
# Built and run via docker compose alongside the backend (see
# calmsense-backend/docker-compose.yml).

# --- build stage ---
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# No VITE_API_BASE -> the app calls a relative /api, which nginx proxies to the
# backend, so the dashboard and API share one origin (no CORS needed).
RUN npm run build

# --- serve stage ---
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
