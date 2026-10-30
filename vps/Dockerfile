FROM node:24.21.0-bookworm-slim
ENV NODE_ENV=production DATA_DIR=/data PORT=3000
WORKDIR /app
COPY --chown=node:node cloudflare/src ./cloudflare/src
COPY --chown=node:node cloudflare/migrations ./cloudflare/migrations
COPY --chown=node:node app/src/main/res/font/vazirmatn_regular.ttf ./app/src/main/res/font/vazirmatn_regular.ttf
COPY --chown=node:node app/src/main/assets/licenses/Vazirmatn-OFL.txt ./licenses/Vazirmatn-OFL.txt
COPY --chown=node:node vps ./vps
RUN npm ci --omit=dev --prefix vps --ignore-scripts && mkdir -p /data /backups && chown node:node /data /backups
USER node
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD node -e "fetch('http://127.0.0.1:3000/health').then(r=>{if(!r.ok)process.exit(1)}).catch(()=>process.exit(1))"
CMD ["node","vps/src/main.mjs"]
