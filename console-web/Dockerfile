# syntax=docker/dockerfile:1.6
#
# Dockerfile for sigdep-hub console-web (React + Vite static site,
# served by nginx).
#
# Multi-stage build: a node image compiles the TypeScript and bundles
# the assets, then a slim nginx image serves the result. The runtime
# image is < 50 MB.

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
FROM node:20-alpine AS build

WORKDIR /build

# Copy lockfile first so npm ci is cached when only source changes.
COPY package.json package-lock.json ./
RUN npm ci

# Source + config.
COPY tsconfig.json vite.config.* postcss.config.js tailwind.config.js ./
COPY index.html ./
COPY public ./public
COPY src ./src

# Build-time env vars inlined into the SPA by Vite. VITE_SUPERSET_URL points
# the « Analyses avancées » menu at the Superset instance ; leave empty to
# hide the menu. Must be passed at image build time (it's baked in).
ARG VITE_SUPERSET_URL=
ENV VITE_SUPERSET_URL=${VITE_SUPERSET_URL}

RUN npm run build

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runtime

# Drop the default config — the prod nginx.conf at infra/ does the
# real reverse-proxying ; this image only serves the static SPA on
# port 80. The upstream nginx (infra/nginx/nginx.prod.conf) routes
# /, /api, /analytics etc. to the appropriate backend.
RUN rm /etc/nginx/conf.d/default.conf
COPY console-web.nginx.conf /etc/nginx/conf.d/spa.conf

COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -q -O - http://localhost/index.html >/dev/null || exit 1
