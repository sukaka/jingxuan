FROM alpine:3.21

RUN apk add --no-cache socat \
    && adduser -S -D -H -u 10001 proxy
USER proxy
ENTRYPOINT ["socat", "TCP4-LISTEN:3310,bind=127.0.0.1,fork,reuseaddr", "TCP4:clamav:3310"]
