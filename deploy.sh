#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/speakpath-ai"

usage() {
  printf '%s\n' '用法：bash deploy.sh [--prepare]' '  --prepare 仅生成或补齐本地 .env，不启动服务。'
}

case "${1:-}" in
  ''|--prepare) ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

umask 077
if [[ ! -f .env ]]; then
  (set -o noclobber; cat .env.example > .env)
fi
chmod 600 .env

env_temp=$(mktemp .env.deploy.XXXXXX)
trap 'rm -f "$env_temp"' EXIT
cp .env "$env_temp"

for key in POSTGRES_PASSWORD REDIS_PASSWORD S3_SECRET_KEY JWT_SECRET; do
  if grep -Eq "^${key}=[^[:space:]]" "$env_temp"; then
    continue
  fi
  value=$(od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]')
  if [[ ${#value} -ne 64 ]]; then
    printf '无法生成 %s\n' "$key" >&2
    exit 1
  fi
  awk -v key="$key" 'index($0, key "=") != 1' "$env_temp" > "$env_temp.new"
  mv "$env_temp.new" "$env_temp"
  printf '\n%s=%s\n' "$key" "$value" >> "$env_temp"
done

if ! cmp -s .env "$env_temp"; then
  mv "$env_temp" .env
fi

if [[ ${1:-} == --prepare ]]; then
  printf '本地配置已准备：%s/.env\n' "$PWD"
  exit 0
fi

if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  printf '%s\n' '需要 Docker 和 Docker Compose v2 插件。请参照 README 的平台准备步骤。' >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  printf '%s\n' '无法连接 Docker。请启动 Docker Desktop、Colima 或 Docker Engine，并确认 Docker context。' >&2
  exit 1
fi

docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 180
docker compose ps
printf '\nWeb 入口：%s\n' "$(docker compose port web 80 | sed 's/^/http:\/\//; s/0\.0\.0\.0/localhost/')"
