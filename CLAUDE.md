# bplus-engine
A B+Tree storage engine in Java 21, built from scratch as a learning project.
I am building this to LEARN, not only to ship.

## Hard rules
- Test-scope deps only: JUnit 5, AssertJ, jqwik, JMH. No Spring, Lombok, Guava.
- Keys and values are byte[], compared with Arrays.compareUnsigned. Never String.
- Page size is Page.SIZE (4096). Never hardcode 4096 anywhere else.
- Only Pager touches FileChannel. Everything else goes through BufferPool.
- No MappedByteBuffer, no RandomAccessFile.

## Working agreement
- Do NOT write or edit code unless I explicitly say.
- When I ask you to teach, do not write code — prose and worked examples only.
- Run `mvn -q test` before claiming any task is done.
- Small commits, each with a passing build.
- After a feature is complete and green, write its design doc in docs/design/NN-feature.md.
  Follow docs/design/README.md: what the feature is and how it works, byte layout, a worked
  example with real bytes. Not a walkthrough of the code.
- Write docs in my voice: plain, short sentences, no flourishes. Say "I" where I made a choice.