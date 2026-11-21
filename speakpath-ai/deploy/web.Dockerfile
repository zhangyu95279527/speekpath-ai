ARG IMAGE_REGISTRY=public.ecr.aws/docker/library
FROM ${IMAGE_REGISTRY}/node:24-bookworm-slim AS build
WORKDIR /workspace
ENV CI=1 EXPO_NO_TELEMETRY=1 EXPO_PUBLIC_API_URL=/api/v1
RUN npm install --global pnpm@10.15.1
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml .npmrc ./
COPY apps/mobile/ apps/mobile/
COPY packages/ packages/
RUN --mount=type=cache,target=/pnpm/store pnpm install --frozen-lockfile --store-dir /pnpm/store
RUN pnpm build:web

FROM ${IMAGE_REGISTRY}/nginx:1.28-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/mobile/dist /usr/share/nginx/html
