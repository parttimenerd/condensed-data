Summary should print local time, not UTC
--------------


java "-javaagent:target/condensed-data.jar=start recording.cjfr --duration 10s" -jar renaissance.jar all

summary:

Format Version: 1
Generator: condensed jfr agent
Generator Version: 0.1
Generator Configuration: start recording.cjfr --duration 10s
Compression: LZ4FRAMED
Start: 2025-11-19 10:31:12 <---- wrong, this is UTC, not local time which is 11:31
End: 2025-11-19 10:31:19



java "-javaagent:target/condensed-data.jar=start recording.cjfr --duration 0.1s" -version  fails
------
Condensed recording to recording.cjfr finished
Exception in thread "Thread-1" java.lang.IllegalStateException: The stream is already closed


and summary of file is

Format Version: 1
Generator: condensed jfr agent
Generator Version: 0.1
Generator Configuration: start recording.cjfr --duration 0.1s
Compression: LZ4FRAMED
Start: 1969-12-31 23:59:59
End: 1969-12-31 23:59:59


which is broken


this is a direct cause of the out stream being closed before writing the summary info:

```java
    public void close() {
        writeConfigurationAndUniverseIfNeeded(defaultStartTimeNanos); // ensure universe is written
        closed = true;
        eventCombiner.close();
        out.close();
    }
```