#!/bin/bash

for s in "avro" "protobuf"; do
  pushd type-handlers/$s
  echo "running tests and building uberjar for $s type handler"
  clj -T:build:test:test-dependencies run-tests && clj -T:build uberjar
  popd
done

echo "running tests and building uberjar for kc-repl main"
clj -T:build:test:type-handlers:test-dependencies run-tests && clj -T:build:type-handlers uberjar

