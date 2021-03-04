FROM clojure:openjdk-11-tools-deps-1.10.2.796-buster

WORKDIR /tasks/

COPY deps.edn ./

# has the side-effect of Clojure downloading deps listed in deps.edn
RUN clojure -Stree

COPY src ./src/

CMD ["echo" "(You should override this command.)"]
