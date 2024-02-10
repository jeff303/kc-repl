## Why This is Here

**tl;dr**: to provide [this class](https://github.com/confluentinc/kafka-streams-examples/blob/master/src/test/java/io/confluent/examples/streams/kafka/EmbeddedSingleNodeKafkaCluster.java).

## The Struggle so Far

There is a class with the same base name, at `io.confluent.ksql.test.util.EmbeddedSingleNodeKafkaCluster`,
and this is available from Maven central, via:

`io.confluent.ksql/ksqldb-test-util {:mvn/version "7.2.1"}}`

However, that version of the class *does not* incorporate Schema Registry, which defeats the purpose.

I also tried adding a dependency to the actual class we need (linked at the beginning), via a deps git
coordinate

`io.github.confluentinc/kafka-streams-examples {:git/tag "v7.1.6-18" :git/sha "d1311bc"}}}`

But that didn't seem to work (it hangs forever when trying to resolve the dependency).

## Other Possibilities

The `embeddedkafka` GitHub org has [a project](https://github.com/embeddedkafka/embedded-kafka-schema-registry) that
might be useful.  However, it's written in Scala and incorporating that might be a pain.