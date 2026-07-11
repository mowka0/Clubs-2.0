# Этап 1: сборка
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --legacy-peer-deps
COPY . .
# Публичные фронтовые ключи Яндекс.Карт (event-geo): Vite инлайнит VITE_* в бандл на этапе
# сборки, поэтому передаются build args'ами из docker-compose (Coolify берёт из env приложения).
# Это НЕ секреты (уходят в браузер по определению), защита ключей — referer-ограничение в кабинете Яндекса.
ARG VITE_YANDEX_MAPS_API_KEY
ARG VITE_YANDEX_STATIC_API_KEY
ARG VITE_YANDEX_GEOCODER_API_KEY
ENV VITE_YANDEX_MAPS_API_KEY=$VITE_YANDEX_MAPS_API_KEY \
    VITE_YANDEX_STATIC_API_KEY=$VITE_YANDEX_STATIC_API_KEY \
    VITE_YANDEX_GEOCODER_API_KEY=$VITE_YANDEX_GEOCODER_API_KEY
RUN npm run build

# Этап 2: раздача
FROM nginx:alpine
RUN rm -f /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN touch /var/run/nginx.pid && \
    chown -R nginx:nginx /var/run/nginx.pid && \
    chown -R nginx:nginx /var/cache/nginx && \
    chown -R nginx:nginx /usr/share/nginx/html
USER nginx
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q --spider http://localhost/ || exit 1
