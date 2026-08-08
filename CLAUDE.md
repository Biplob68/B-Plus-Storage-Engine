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
- Do NOT write or edit code under src/main/java/dev/bplus/tree/ unless I explicitly
  say "write the tree code". In that package, explain concepts and review my code.
- When I ask you to teach, do not write code — prose and worked examples only.
- Run `mvn -q test` before claiming any task is done.
- Small commits, each with a passing build.