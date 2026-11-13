package crap4java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverloadedCoverageRegressionTest {
    @TempDir
    Path tempDir;

    @Test
    void assignsEachOverloadItsOwnCoverageRegardlessOfXmlOrder() throws IOException {
        Path fixture = Path.of("test/fixtures/overloads");
        Path source = fixture.resolve("Validator.java");
        var methods = JavaMethodParser.parse("Validator", Files.readString(source));
        assertEquals(23, methods.get(1).startLine());
        assertEquals(Math.abs(20 - methods.get(1).startLine()),
                Math.abs(26 - methods.get(1).startLine()));

        String xml = Files.readString(fixture.resolve("jacoco.xml"));
        int first = xml.indexOf("      <method");
        int second = xml.indexOf("      <method", first + 1);
        int end = xml.indexOf("    </class>", second);
        String reversed = xml.substring(0, first) + xml.substring(second, end)
                + xml.substring(first, second) + xml.substring(end);
        for (String report : List.of(xml, reversed)) {
            Path jacoco = tempDir.resolve("jacoco.xml");
            Files.writeString(jacoco, report);
            var metrics = CrapAnalyzer.analyze(tempDir, List.of(source), jacoco).stream()
                    .sorted(Comparator.comparingInt(MethodMetrics::complexity)).toList();
            assertEquals(2, metrics.size());
            assertEquals(2, metrics.get(0).complexity());
            assertEquals(0.0, metrics.get(0).coveragePercent());
            assertEquals(6.0, metrics.get(0).crapScore());
            assertEquals(4, metrics.get(1).complexity());
            assertEquals(100.0, metrics.get(1).coveragePercent());
            assertEquals(4.0, metrics.get(1).crapScore());
        }
    }
}
