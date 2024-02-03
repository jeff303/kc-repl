11/18/2023

Said "fuck it" to working on day job today.  Wifi at GRC isn't working but I still
managed to make progress, even from an out of date version of the code (had assumed
personal laptop was up to date, but it was not)

Added ->clj to the type handler multi, which I've known I would need for a while
but now actually do.  Had to implement a dummy impl for text and json, but can
probably get rid of that later.
 
Now, the main issue is that the consumer is only sporadically reading the data in
the test for unknown reasons.  Sometimes, it reads no data from the test topic/offset,
but if I remove the docker-compose containers and bring them back up, then it works
again sometimes.  Probably a race condition in my client code somewhere.  Maybe
the active assignment isn't working right, but for Avro only?

2/2/2024

Finally some progress.

Got the Avro test passing when using the docker-compose.yml file that is now included
under tests.  But for some reason, it failed when invoked via the testcontainers
ComposeContainer, which is explained
[here](https://java.testcontainers.org/modules/docker_compose/#compose-v2).

If I run `docker-compose up` outside of the JVM, then use the
`kafka-with-schema-registry-docker-compose-fixture-manual` fixture, that works.

May just give up and fall back on manually running the compose outside of the JVM
just to make forward progress.

Also figured out why the Avro test was initially not fetching the record from offset
0, when that was clearly available.  Well, not really *why* that happens, but at least
how to work around it.  I turned on trace level logs and eventually noticed this:

```text
2024-02-02 20:27:37 INFO  Fetcher:1400 - [Consumer clientId=consumer-kc-repl-test-1, groupId=kc-repl-test] Fetch position FetchPosition{offset=0, offsetEpoch=Optional.empty, currentLeader=LeaderAndEpoch{leader=Optional[localhost:19092 (id: 1 rack: null)], epoch=0}} is out of range for partition sensor-readings-0, resetting offset
```

So, on a whim, I tried adding `auto.offset.reset=earliest`, and that worked (able to read
from offset 0 now).  This shouldn't be needed but it does at least make the test pass.

Still need to figure out how the "load extra type handlers" at runtime thing
would work outside a test.  Would need to require the "extension" namespaces
from main, and probably also Java main.