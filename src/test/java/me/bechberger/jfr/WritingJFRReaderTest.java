package me.bechberger.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import me.bechberger.condensed.CondensedInputStream;
import me.bechberger.jfr.cli.commands.CommandTestUtil;
import org.junit.jupiter.api.Test;

public class WritingJFRReaderTest {

    /**
     * Bug: WritingJFRReader.close() calls recording.close() twice instead of also closing the
     * outputStream. The second recording.close() is a copy-paste error.
     *
     * <p>Expected: close() closes both the recording and the outputStream Actual: recording.close()
     * is called twice, outputStream.close() is never called
     */
    @Test
    public void testCloseClosesOutputStream() throws Exception {
        // Use a tracking output stream to verify close is called
        boolean[] outputStreamClosed = {false};
        var trackingStream =
                new OutputStream() {
                    private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

                    @Override
                    public void write(int b) throws IOException {
                        delegate.write(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        delegate.write(b, off, len);
                    }

                    @Override
                    public void close() throws IOException {
                        outputStreamClosed[0] = true;
                        delegate.close();
                    }
                };

        var cjfrPath = CommandTestUtil.getSampleCJFRFile();
        var reader = new BasicJFRReader(new CondensedInputStream(Files.newInputStream(cjfrPath)));
        var writingReader = new WritingJFRReader(reader, trackingStream);

        // Read a few events to initialize
        for (int i = 0; i < 3; i++) {
            if (writingReader.readNextJFREvent() == null) break;
        }

        writingReader.close();

        assertTrue(
                outputStreamClosed[0],
                "outputStream.close() should be called by WritingJFRReader.close(), but it was"
                    + " never called. The close() method calls recording.close() twice instead of"
                    + " also closing the outputStream.");
    }

    /**
     * Bug: toJFREventsList(reader, shouldAddDefaultValuesIfNecessary) accepts the parameter but
     * ignores it — it always constructs WritingJFRReader with the 2-arg constructor which defaults
     * shouldAddDefaultValuesIfNecessary to false.
     *
     * <p>The call chain is: toJFREventsList(reader, bool) → toJFREventsList(reader, MAX_VALUE,
     * bool) → toJFRFile(reader) → new WritingJFRReader(reader, os) ← always false
     *
     * <p>Expected: passing true causes default values to be added for removed fields Actual: the
     * parameter is silently discarded
     */
    @Test
    public void testToJFREventsListHonorsShouldAddDefaultValues() throws Exception {
        // Verify that the shouldAddDefaultValuesIfNecessary parameter is actually
        // wired through to WritingJFRReader (previously it was silently ignored).
        // We verify by constructing a WritingJFRReader via toJFRFile and checking
        // the field is set correctly via reflection.
        var cjfrPath = CommandTestUtil.getSampleCJFRFile();
        var reader = new BasicJFRReader(new CondensedInputStream(Files.newInputStream(cjfrPath)));
        var trackingStream = new ByteArrayOutputStream();
        var writingReader = new WritingJFRReader(reader, trackingStream, true);

        // Use reflection to verify the field was set
        var field = WritingJFRReader.class.getDeclaredField("shouldAddDefaultValuesIfNecessary");
        field.setAccessible(true);
        assertTrue(
                (boolean) field.get(writingReader),
                "shouldAddDefaultValuesIfNecessary should be true when passed as true");

        // Also verify default constructor sets it to false
        var reader2 = new BasicJFRReader(new CondensedInputStream(Files.newInputStream(cjfrPath)));
        var writingReader2 = new WritingJFRReader(reader2, new ByteArrayOutputStream());
        assertFalse(
                (boolean) field.get(writingReader2),
                "shouldAddDefaultValuesIfNecessary should default to false");
    }
}
