ARG BUILD_FROM
FROM ${BUILD_FROM}
RUN apk add --no-cache python3 ffmpeg tzdata
COPY hub.py /app/hub.py
CMD ["python3", "-u", "/app/hub.py"]
