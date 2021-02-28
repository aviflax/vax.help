FROM babashka/babashka:0.2.10

WORKDIR /app

COPY bin ./bin/
COPY server.bb ./

# Pre-download some libs and data
RUN bin/download-pods
RUN bin/download-locations

ENTRYPOINT [ "./server.bb" ]
