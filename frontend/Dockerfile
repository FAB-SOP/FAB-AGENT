FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html tsconfig.json vite.config.ts ./
COPY src ./src
COPY tests ./tests
RUN npm test && VITE_API_MODE=real npm run build && rm -rf dist/fixtures dist/mockServiceWorker.js && find dist -name '*.map' -delete
FROM nginx:stable-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
