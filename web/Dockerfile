FROM babashka/babashka:0.2.10

WORKDIR /app

COPY bin ./bin/

# Pre-download some libs and data
RUN bin/download-pods
RUN bin/download-locations

# Copy the code last so hopefully we can use the prior layers as cached
COPY server.bb ./

EXPOSE 8080

# ENTRYPOINT [ "./server.bb" ]

CMD [ "./server.bb" ]
