Tunnel Experiments
==================
Code based on the JDI implementation in OpenJDK

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
  > java -javaagent:target/tunnel.jar=address=5015,verbose=warn,logger,mode=code,--packets,--partitions,--programs \
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
  > java -javaagent:target/tunnel.jar=address=5015,verbose=warn,logger,mode=code,--packets,--overlaps \
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

... run the two tunnel configuration (client tunnel and server tunnel):
```sh
  > mvn package > /dev/null && java -javaagent:target/tunnel.jar=verbose=debug,logger,tunnel=server:address=5015,verbose=debug,logger,tunnel=client\
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8001 -cp target/tunnel.jar tunnel.EndlessLoop
```

What is done
------------
- Generation of basic JDWP command classes for all commands of the JDK 17 spec
  - tested using the JDI code as an oracle
- PacketLogger tunnel that logs everything
- Two tunnel configuration works (kind of)

License
-------
GPLv2

Ideas that did not work
-----------------------
... and why, so I don't wonder later

- running the JVM endpoint of the tunnel directly as a javaagent
  - it does not work because stopping the JVM stops the debugging threads too
  - solution: run the tunnel as a separate Java process
- running the tunnel in two separate threads (one for the JVM and one for the client side)
  - it introduces the need for synchronization between two threads which lead to bugs in the
    which where hard to debug
  - solution: use a single thread and poll the two input streams in sequence

TODO
----
- merge on programs should use hash trees to make it independent of variable names
  - the program synthesizer guarantees that the order of the statements depends only on the structure 
    of the dependency graph
- test more
- handle Tunnel requests and events properly in synthesizer
- look into generated programs and add sanity checks
  - i.e. ResumeRequests can never be the cause for something
- implement tester for OpenJDK to find bugs in JDWP error handling
  - the assumption that no valid JDWP packet can make the JVM segfault is false
  - is this really a problem? Find JDI reproduction