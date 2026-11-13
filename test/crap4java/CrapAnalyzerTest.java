package crap4java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CrapAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void computesScoresForChangedFiles() throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java/demo");
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve("Sample.java");
        Files.writeString(source, """
                package demo;
                class Sample {
                    int alpha(boolean a) {
                        if (a) {
                            return 1;
                        }
                        return 0;
                    }
                }
                """);

        Path jacoco = tempDir.resolve("jacoco.xml");
        Files.writeString(jacoco, """
                <report>
                  <package name=\"demo\">
                    <class name=\"demo/Sample\" sourcefilename=\"Sample.java\">
                      <method name=\"alpha\" desc=\"(Z)I\" line=\"3\">
                        <counter type=\"INSTRUCTION\" missed=\"1\" covered=\"3\"/>
                      </method>
                    </class>
                  </package>
                </report>
                """);

        List<MethodMetrics> result = CrapAnalyzer.analyze(
                tempDir,
                List.of(source),
                jacoco
        );

        assertEquals(1, result.size());
        MethodMetrics metric = result.get(0);
        assertEquals("alpha", metric.methodName());
        assertEquals("demo.Sample", metric.className());
        assertEquals(2, metric.complexity());
        assertEquals(75.0, metric.coveragePercent(), 0.001);
        assertEquals(2.0625, metric.crapScore(), 0.00001);
    }

    @Test
    void usesSimpleClassNameWhenSourceHasNoPackage() {
        String className = CrapAnalyzer.classNameFromSource(
                Path.of("src/main/java/Sample.java"),
                """
                class Sample {
                }
                """
        );

        assertEquals("Sample", className);
    }

    @Test
    void lookupCoverageUsesTheFullDescriptorIncludingReturnType() {
        MethodIdentity integer = new MethodIdentity("demo.Sample", "alpha", "()Ljava/lang/Integer;");
        MethodIdentity bridge = new MethodIdentity("demo.Sample", "alpha", "()Ljava/lang/Object;");
        var coverageMap = Map.of(integer, new CoverageData(1, 3), bridge, new CoverageData(0, 8));

        assertEquals(75.0, CrapAnalyzer.lookupCoverage(coverageMap, integer));
        assertEquals(100.0, CrapAnalyzer.lookupCoverage(coverageMap, bridge));
        assertNull(CrapAnalyzer.lookupCoverage(coverageMap,
                new MethodIdentity("demo.Other", "alpha", integer.descriptor())));
    }

    @Test
    void lookupCoverageReturnsNullWhenIdentityOrEvidenceIsMissing() {
        MethodIdentity identity = new MethodIdentity("demo.Sample", "alpha", "()I");
        assertNull(CrapAnalyzer.lookupCoverage(Map.of(), identity));
        assertNull(CrapAnalyzer.lookupCoverage(Map.of(identity, new CoverageData(0, 8)), null));
        assertNull(CrapAnalyzer.lookupCoverage(Map.of(identity, new CoverageData(0, 8)),
                new MethodIdentity("demo.Sample", "alpha", null)));
    }

    @Test
    void doesNotBorrowCoverageFromAnotherSignatureEvenOnAnExactLine() throws IOException {
        Path source = tempDir.resolve("Sample.java");
        Files.writeString(source, """
                class Sample {
                    int alpha(int value) { return value; }
                    int beta(Missing value) { return 0; }
                }
                """);
        Path jacoco = tempDir.resolve("jacoco.xml");
        Files.writeString(jacoco, """
                <report><class name="Sample">
                  <method name="alpha" desc="()I" line="2">
                    <counter type="INSTRUCTION" missed="0" covered="8"/>
                  </method>
                  <method name="beta" desc="(Ljava/lang/String;)I" line="3">
                    <counter type="INSTRUCTION" missed="0" covered="8"/>
                  </method>
                </class></report>
                """);

        var metrics = CrapAnalyzer.analyze(tempDir, List.of(source), jacoco);
        assertEquals(2, metrics.size());
        for (var metric : metrics) {
            assertNull(metric.coveragePercent());
            assertNull(metric.crapScore());
        }
    }

    @Test
    void distinguishesSameArityOverloadsOnTheSameSourceLine() throws IOException {
        Path source = tempDir.resolve("Sample.java");
        Files.writeString(source,
                "class Sample { int alpha(int x) { return x; } int alpha(long x) { return 0; } }");
        Path jacoco = tempDir.resolve("jacoco.xml");
        Files.writeString(jacoco, """
                <report><class name="Sample">
                  <method name="alpha" desc="(I)I" line="1">
                    <counter type="INSTRUCTION" missed="3" covered="1"/>
                  </method>
                  <method name="alpha" desc="(J)I" line="1">
                    <counter type="INSTRUCTION" missed="1" covered="3"/>
                  </method>
                </class></report>
                """);

        var metrics = CrapAnalyzer.analyze(tempDir, List.of(source), jacoco);
        assertEquals(List.of(25.0, 75.0), metrics.stream().map(MethodMetrics::coveragePercent).toList());
    }

    @Test
    void attributesNestedClassCoverageToItsBinaryOwner() throws IOException {
        Path source = tempDir.resolve("Sample.java");
        Files.writeString(source, """
                class Sample {
                    int alpha() { return 1; }
                    static class Nested { int alpha() { return 2; } }
                }
                """);
        Path jacoco = tempDir.resolve("jacoco.xml");
        Files.writeString(jacoco, """
                <report>
                  <class name="Sample"><method name="alpha" desc="()I" line="2">
                    <counter type="INSTRUCTION" missed="4" covered="0"/>
                  </method></class>
                  <class name="Sample$Nested"><method name="alpha" desc="()I" line="3">
                    <counter type="INSTRUCTION" missed="0" covered="4"/>
                  </method></class>
                </report>
                """);

        var metrics = CrapAnalyzer.analyze(tempDir, List.of(source), jacoco);
        assertEquals(List.of("Sample", "Sample$Nested"), metrics.stream().map(MethodMetrics::className).toList());
        assertEquals(List.of(0.0, 100.0), metrics.stream().map(MethodMetrics::coveragePercent).toList());
    }

    @Test
    void resolvesSiblingSignatureTypesFromTheModulesCompiledClasses() throws IOException {
        Path classes = Files.createDirectories(tempDir.resolve("target/classes"));
        Path helper = tempDir.resolve("Helper.java");
        Files.writeString(helper, "package demo; public class Helper { }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-proc:none", "-d", classes.toString(), helper.toString()));
        Path source = tempDir.resolve("Sample.java");
        Files.writeString(source, "package demo; class Sample { Helper alpha(Helper x) { return x; } }");
        Path jacoco = tempDir.resolve("jacoco.xml");
        Files.writeString(jacoco, """
                <report><class name="demo/Sample">
                  <method name="alpha" desc="(Ldemo/Helper;)Ldemo/Helper;" line="1">
                    <counter type="INSTRUCTION" missed="1" covered="3"/>
                  </method>
                </class></report>
                """);

        var metrics = CrapAnalyzer.analyze(tempDir, List.of(source), jacoco);
        assertEquals(1, metrics.size());
        assertEquals(75.0, metrics.get(0).coveragePercent());
    }

    @Test
    void sortsMetricsByScoreDescendingWithNullsLast() throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java/demo");
        Files.createDirectories(sourceRoot);
        Path source = sourceRoot.resolve("Sample.java");
        Files.writeString(source, """
                package demo;

                class Sample {
                    int alpha(boolean a) {
                        if (a) {
                            return 1;
                        }
                        return 0;
                    }

                    int beta() {
                        return 1;
                    }

                    int gamma() {
                        return 2;
                    }
                }
                """);

        Path jacoco = tempDir.resolve("jacoco.xml");
        Files.writeString(jacoco, """
                <report>
                  <package name="demo">
                    <class name="demo/Sample" sourcefilename="Sample.java">
                      <method name="alpha" desc="(Z)I" line="4">
                        <counter type="INSTRUCTION" missed="4" covered="0"/>
                      </method>
                      <method name="beta" desc="()I" line="10">
                        <counter type="INSTRUCTION" missed="0" covered="4"/>
                      </method>
                    </class>
                  </package>
                </report>
                """);

        List<MethodMetrics> result = CrapAnalyzer.analyze(tempDir, List.of(source), jacoco);

        assertEquals(List.of("alpha", "beta", "gamma"),
                result.stream().map(MethodMetrics::methodName).toList());
    }
}
