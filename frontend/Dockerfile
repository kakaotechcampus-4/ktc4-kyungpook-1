# 운영 이미지: 빌드 산출물을 Caddy 가 서빙하고 /api 를 Spring 으로 넘긴다.
# Caddyfile 은 deploy/Caddyfile 을 compose 가 마운트한다.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 목 API 를 끄고 같은 오리진의 /api 로 요청한다 (R-10).
ENV VITE_API_MOCK=false
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /app/dist /srv
