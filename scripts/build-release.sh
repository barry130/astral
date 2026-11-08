#!/usr/bin/env bash
# A deployable tag always contains backend, frontend and blog. Build everything before publishing.
set -euo pipefail
: "${IMAGE_PREFIX:?IMAGE_PREFIX is required}"
: "${RELEASE_TAG:?RELEASE_TAG is required}"
: "${BLOG_SITE_URL:?BLOG_SITE_URL is required at build time}"
: "${BLOG_ADMIN_URL:?BLOG_ADMIN_URL is required at build time}"
cd "$(dirname "$0")/.."
docker build -f astral-server/Dockerfile -t "$IMAGE_PREFIX/astral-backend:$RELEASE_TAG" .
docker build -f astral-front/Dockerfile -t "$IMAGE_PREFIX/astral-frontend:$RELEASE_TAG" astral-front
docker build -f astral-blog/Dockerfile --build-arg CMS_API_BASE=http://backend:27000 \
  --build-arg "NEXT_PUBLIC_SITE_URL=$BLOG_SITE_URL" --build-arg "NEXT_PUBLIC_ADMIN_URL=$BLOG_ADMIN_URL" \
  -t "$IMAGE_PREFIX/astral-blog:$RELEASE_TAG" astral-blog
for service in backend frontend blog; do
  docker push "$IMAGE_PREFIX/astral-$service:$RELEASE_TAG"
done
# Advance mutable aliases only after all immutable images have been published successfully.
for service in backend frontend blog; do
  docker tag "$IMAGE_PREFIX/astral-$service:$RELEASE_TAG" "$IMAGE_PREFIX/astral-$service:latest"
  docker push "$IMAGE_PREFIX/astral-$service:latest"
done
printf 'Published complete release: %s\n' "$RELEASE_TAG"
