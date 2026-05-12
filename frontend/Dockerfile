ARG REGISTRY=docker.io/library/
# Build stage
# 构建阶段
FROM node:20 as build-stage
WORKDIR /app
COPY package*.json ./
RUN rm -rf node_modules && npm cache clean --force && npm install --force
RUN npm config set registry https://registry.npmmirror.com
COPY . .
RUN NODE_OPTIONS=--max-old-space-size=4096 ESBUILD_MEMORY_LIMIT=4096 node_modules/.bin/vite build --debug

# 生产阶段
FROM docker.io/library/nginx:stable-alpine as production-stage
COPY --from=build-stage /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]