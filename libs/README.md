## `testcontainers-kafka-avro-1.0-SNAPSHOT-tests.jar`

This exists *solely* to gain access to
[these classes](https://github.com/findinpath/testcontainers-kafka-avro/tree/master/src/test/java/com/findinpath/testcontainers).
For some reason, I can't get the GitHub deps.edn coordinate for the project to work, so I had to clone this project,
manually add the Maven plugin invocation to build the test-jar, then copy that test jar into this source repo.