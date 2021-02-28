FROM babashka/babashka:0.2.10

WORKDIR /app

COPY bin ./bin/
COPY server.bb ./

RUN bin/download-pods

ENTRYPOINT [ "./server.bb" ]
