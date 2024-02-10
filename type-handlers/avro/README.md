# Avro Type Handler

This module provides Avro type handling for `kc-repl`.  It assumes availability of Kafka Schema Registry
(where Avro schemas are registered) in addition to Kafka.

# Development

## Building the uberjar
```shell
clojure -T:build uberjar
```

## Testing

There are tests for Avro support under the test root.  However,
they currently require running the Docker composition
under `test/docker-compose.yml`, which will start the following
containers:

- `confluentinc/cp-schema-registry`
- `confluentinc/cp-kafka`
- `confluentinc/cp-zookeeper`

Currently, these have to be started separately, then the Avro
test uses the `kafka-with-schema-registry-docker-compose-manual-fixture`
fixture to access the services.  This is not optimal and needs to be
addressed.

See [issue 12](https://github.com/jeff303/kc-repl/issues/12) for more details.
