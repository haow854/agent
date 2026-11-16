FROM node:22-alpine

# 在国内服务器上构建时可指定 npm 源，留空则用官方源
ARG NPM_REGISTRY=

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev ${NPM_REGISTRY:+--registry "$NPM_REGISTRY"}

# 全部源码文件，测试文件由 .dockerignore 排除
COPY *.js ./

EXPOSE 1234

CMD ["node", "index.js"]
