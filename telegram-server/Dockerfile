FROM node:24-alpine
WORKDIR /app
# Standalone build context; this folder is sufficient, no Astral source or npm required.
COPY --chown=node:node worker.mjs server.mjs ./
USER node
ENV HOST=0.0.0.0 PORT=8787 NODE_OPTIONS=--max-old-space-size=256
EXPOSE 8787
CMD ["node", "server.mjs"]
