package com.astral.sequence.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.dao.mapper.SequenceStatisticsMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseGeneratorBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void batchFinishesAtLargestRepresentableId() throws Exception {
        Path result = directory.resolve("result.txt");
        Process child = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx64m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        DatabaseGeneratorBoundaryTest.class.getName(),
                        result.toString())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("process.log").toFile())
                .start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "batch must terminate at Long.MAX_VALUE");
            assertEquals(0, child.exitValue(), Files.readString(directory.resolve("process.log")));
            assertEquals("9223372036854775806,9223372036854775807", Files.readString(result));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        SequenceStatisticsMapper mapper = mock(SequenceStatisticsMapper.class);
        when(mapper.incrementAndGet("fixture", 2)).thenReturn(Long.MAX_VALUE);
        Files.writeString(Path.of(args[0]), new DatabaseGenerator(mapper).batch("fixture", 2));
    }
}
