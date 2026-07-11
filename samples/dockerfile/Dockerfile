# A multi-stage Dockerfile — open the 3-mode preview for a per-stage digest
# syntax=docker/dockerfile:1
ARG NODE_VERSION=20

FROM node:${NODE_VERSION} AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.25-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
ENV TZ=UTC
EXPOSE 80
USER nginx
HEALTHCHECK --interval=30s CMD wget -q --spider http://localhost/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
