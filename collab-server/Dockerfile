FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY index.js ./

ENV COLLAB_PORT=1234
EXPOSE 1234

CMD ["node", "index.js"]
