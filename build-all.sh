#!/bin/bash

for s in "avro" "protobuf"; do
  pushd type-handlers/$s
  clojure -J-Dclojure.core.async.pool-size=1 -T:build ci '{:aliases [:test-dependencies :type-handlers]}'
  popd
done

clojure -J-Dclojure.core.async.pool-size=1 -T:build ci '{:aliases [:test-dependencies :type-handlers]}'

