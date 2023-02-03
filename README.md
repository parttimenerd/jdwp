Tunnel
======
This code has been previously created to implement
a JDWP proxy to improve debugging performance.
But this turned out to be too difficult to implement properly, especially regarding edge cases.

This code contains a well tested and working parser and
generator for JDWP packages. This might be useful in the future.
It also contains code to log the JDWP traffic, which I
used in the blog post "[A short primer on Java debugging internals](https://mostlynerdless.de/blog/2022/12/27/a-short-primer-on-java-debugging-internals/)".
Please write an issue if you want to use specific functionality as a library,
I package it properly then.

The code is tested by using the OpenJDK implementation as an oracle. This is reason why large parts of the
OpenJDK implementation are included in the tests of this project.

The packet specification in `data/jdwp.spec` is taken from
[OpenJDK](https://github.com/openjdk/jdk/blob/master/src/java.se/share/data/jdwp/jdwp.spec) and extended with side effects,
see the `jdwpgen` README for more information.

Tested with JDK 11, JDK 17 and JDK 20.

Features
--------
- Request, Reply and Event packet classes that can parse and generate JDWP packets
  - this is really well tested and can be used as a base for future projects
  - using a highly modified jdwpgen from OpenJDK to generate the classes
- These packets
  - have a basic side-effect model (e.g. influenced by and modifying what state), see the `StateProperty` enum for details
  - have a basic cost model (e.g. how much time does it take to execute the command), based on `data/costs.csv`
  - can be accessed as a generic data structure, not dissimilar from typical JSON libraries
  - have pretty-printers for debugging
  - all this is well tested
- The packets can then be heuristically partitioned into groups
  - only the first command (the cause) might have a side effect
  - this works okayish
- These groups can then be used to synthesize debugging programs, written in a small custom language
  - still a long way to go, but the general concept works
- It contains an immature implementation of JDWP packet caching
  - this is a very early prototype and does only work for small examples

Build
-----
`mvn package` generates code and runs all unit tests

Usage
-----
Logger (print all packets):
```sh
  > java -javaagent:target/tunnel.jar=address=5015,verbose=warn,logger,--packets \
       -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8001 \
       -cp target/tunnel.jar tunnel.EndlessLoop
       
  ...
  1652282122,104:    Request[146524]: FrameCountRequest(thread=ThreadReference(1))
  1652282122,105:    Request[146525]: NameRequest(thread=ThreadReference(1))
  1652282122,105:      Reply[146524]: FrameCountReply(frameCount=IntValue(1))
  1652282122,105:    Request[146526]: StatusRequest(thread=ThreadReference(1))
  1652282122,105:    Request[146527]: FramesRequest(thread=ThreadReference(1), startFrame=IntValue(0), length=IntValue(1))
  1652282122,105:      Reply[146525]: NameReply(threadName=StringValue(main))
  ...
```

... print all packets with the resulting partitions and synthesized programs:
```sh
  > java -javaagent:target/tunnel.jar=address=5015,verbose=warn,logger,packet-mode=code,,--packets,--partitions,--programs \
       -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8001 \
       -cp target/tunnel.jar tunnel.EndlessLoop

  1653386904,463:      Event[      0]: new jdwp.EventCmds.Events(0, PrimitiveValue.wrap((byte)2), new ListValue<>(Type.LIST, List.of(new EventCmds.Events.VMStart(PrimitiveValue.wrap(0), new ThreadRefere... (-26 more)
  1653386904,472:    Request[  16056]: new jdwp.VirtualMachineCmds.IDSizesRequest(16056)
  1653386904,475:      Reply[  16056]: new ReplyOrError<>(16056, new jdwp.VirtualMachineCmds.IDSizesReply(16056, PrimitiveValue.wrap(8), PrimitiveValue.wrap(8), PrimitiveValue.wrap(8), PrimitiveValue.wr... (-6 more)
  
  Partition:
  new Partition(null, List.of(
          p(new jdwp.VirtualMachineCmds.IDSizesRequest(16056), new jdwp.VirtualMachineCmds.IDSizesReply(16056, PrimitiveValue.wrap(8), PrimitiveValue.wrap(8), PrimitiveValue.wrap(8), PrimitiveValue.wrap(8), PrimitiveValue.wrap(8)))))
  
  
  Program:
  ((= cause (events Event Composite ("suspendPolicy")=(wrap "byte" 2) ("events" 0 "requestID")=(wrap "int" 0) ("events" 0 "thread")=(wrap "thread" 1)))
    (= var0 (request VirtualMachine IDSizes)))
```

... print all programs for which we already created a previous program with the same cause 
and at least 70% matching statements:
```sh
  > java -javaagent:target/tunnel.jar=address=5015,verbose=warn,logger,--packets,--overlaps \
       -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8001 \
       -cp target/tunnel.jar tunnel.EndlessLoop
       
  Overlap:
  ----- first ----
  ((= cause (events Event Composite ("suspendPolicy")=(wrap "byte" 2) ("events" 0 "requestID")=(wrap "int" 56) ("events" 0 "thread")=(wrap "thread" 1) ("events" 0 "location" "codeIndex")=(wrap "long" 2) ("events" 0 "location" "declaringType")=(wrap "class-type" 1055) ("events" 0 "location" "methodRef")=(wrap "method" 105553122640072)))
    (= var0 (request ThreadReference FrameCount ("thread")=(get cause "events" 0 "thread")))
    (= var1 (request ThreadReference Name ("thread")=(get cause "events" 0 "thread")))
    (= var2 (request ThreadReference Status ("thread")=(get cause "events" 0 "thread")))
    (= var3 (request ThreadReference Frames ("length")=(get var0 "frameCount") ("startFrame")=(wrap "int" 0) ("thread")=(get cause "events" 0 "thread")))
    (= var4 (request StackFrame GetValues ("frame")=(get var3 "frames" 0 "frameID") ("thread")=(get cause "events" 0 "thread") ("slots" 0 "sigbyte")=(wrap "byte" 91) ("slots" 0 "slot")=(wrap "int" 0) ("slots" 1 "sigbyte")=(wrap "byte" 73) ("slots" 1 "slot")=(get var0 "frameCount"))))
  ----- second ----
  ((= cause (events Event Composite ("suspendPolicy")=(wrap "byte" 2) ("events" 0 "requestID")=(wrap "int" 56) ("events" 0 "thread")=(wrap "thread" 1) ("events" 0 "location" "codeIndex")=(wrap "long" 2) ("events" 0 "location" "declaringType")=(wrap "class-type" 1055) ("events" 0 "location" "methodRef")=(wrap "method" 105553122640072)))
    (= var0 (request ThreadReference FrameCount ("thread")=(get cause "events" 0 "thread")))
    (= var1 (request ThreadReference Name ("thread")=(get cause "events" 0 "thread")))
    (= var2 (request ThreadReference Status ("thread")=(get cause "events" 0 "thread")))
    (= var3 (request ThreadReference Frames ("length")=(get var0 "frameCount") ("startFrame")=(wrap "int" 0) ("thread")=(get cause "events" 0 "thread")))
    (= var4 (request StackFrame GetValues ("frame")=(get var3 "frames" 0 "frameID") ("thread")=(get cause "events" 0 "thread") ("slots" 0 "sigbyte")=(wrap "byte" 91) ("slots" 0 "slot")=(wrap "int" 0) ("slots" 1 "sigbyte")=(wrap "byte" 73) ("slots" 1 "slot")=(get var0 "frameCount"))))
  ----- overlap: 1,00 ----
  ----- #programs =  2531  #(> 1 stmt)programs =  1054  #overlaps =  1039 (98,58%) 
```

... run the two tunnel configuration (client tunnel and server tunnel), this might not work properly
```sh
  > mvn package > /dev/null && java -javaagent:target/tunnel.jar=verbose=debug,logger,tunnel=server:address=5015,verbose=debug,logger,tunnel=client\
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8001 -cp target/tunnel.jar tunnel.EndlessLoop
```

... run it standalone via the main CLI interface (helpful for debugging):
```sh
mvn package -Dmaven.test.skip=true -Dmaven.source.skip=true > /dev/null && \
java -jar target/tunnel.jar demo --run "-cp target/tunnel.jar tunnel.EndlessLoop" --own 5015 \
  --tunnel "logger --tunnel=server --packet-mode=short" \
  --tunnel "logger --tunnel=client --packet-mode=short --cache-file=client.txt --log-columns=none"
```

License
-------
GPLv2

Ideas
-----

- look into generated programs and add sanity checks
  - i.e. ResumeRequests can never be the cause for something
- implement tests with real OpenJDK
- implement tester for OpenJDK to find bugs in JDWP error handling
  - the assumption that no valid JDWP packet can make the JVM segfault is false
  - is this really a problem? Find JDI reproduction