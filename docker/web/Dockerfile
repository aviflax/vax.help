FROM clojure:openjdk-11-tools-deps-1.10.2.796-buster

WORKDIR /web

# Download deps
COPY deps.edn ./
RUN clojure -Stree

COPY resources ./resources/

# Copy the code last so hopefully we can use the prior layers as cached
COPY src ./src/

EXPOSE 8080

CMD [ "clojure", "-X", "vax.help.web.server/start" ]
