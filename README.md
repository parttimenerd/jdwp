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
  > java -javaagent:target/tunnel.jar=address=5015,verbose=debug,logger \
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

What is done
------------
- generation of basic JDWP command classes for all commands of the JDK 17 spec
  - tested using the JDI code as an oracle
- PacketLogger tunnel that logs everything

License
-------
GPLv3

Ideas that did not work
-----------------------
... and why, so I don't wonder later

- running the JVM endpoint of the tunnel directly as a javaagent
  - it does not work because stopping the JVM stops the debugging threads too
  - solution: run the tunnel as a separate Java process