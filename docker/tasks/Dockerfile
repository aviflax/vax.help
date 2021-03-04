FROM clojure:openjdk-11-tools-deps-1.10.2.796-buster

WORKDIR /tasks/

# Download deps
COPY deps.edn ./
RUN clojure -Stree

COPY src ./src/

CMD ["echo" "(You should override this command.)"]
