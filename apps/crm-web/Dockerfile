FROM nginx:1.28.0-alpine

COPY apps/crm-web/dist /usr/share/nginx/html
COPY apps/crm-web/nginx.conf /etc/nginx/nginx.conf
COPY apps/crm-web/nginx-default.conf /etc/nginx/conf.d/default.conf
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=12 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
