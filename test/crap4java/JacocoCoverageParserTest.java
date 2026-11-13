package crap4java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JacocoCoverageParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesCoverageByClassAndMethod() throws IOException {
        Path xml = tempDir.resolve("jacoco.xml");
        Files.writeString(xml, """
                <report name=\"demo\">
                  <package name=\"demo\">
                    <class name=\"demo/Sample\" sourcefilename=\"Sample.java\">
                      <method name=\"alpha\" desc=\"()V\" line=\"10\">
                        <counter type=\"INSTRUCTION\" missed=\"1\" covered=\"9\"/>
                      </method>
                      <method name=\"beta\" desc=\"()V\" line=\"20\">
                        <counter type=\"INSTRUCTION\" missed=\"0\" covered=\"0\"/>
                      </method>
                    </class>
                  </package>
                </report>
                """);

        Map<MethodIdentity, CoverageData> result = JacocoCoverageParser.parse(xml);

        assertEquals(90.0, result.get(new MethodIdentity("demo.Sample", "alpha", "()V")).coveragePercent(), 0.001);
        assertEquals(0.0, result.get(new MethodIdentity("demo.Sample", "beta", "()V")).coveragePercent(), 0.001);
    }

    @Test
    void parsesXmlWithDoctypeWithoutRequiringLocalDtdFile() throws IOException {
        Path xml = tempDir.resolve("jacoco-with-doctype.xml");
        Files.writeString(xml, """
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="demo">
                  <package name="demo">
                    <class name="demo/Sample" sourcefilename="Sample.java">
                      <method name="alpha" desc="()V" line="10">
                        <counter type="INSTRUCTION" missed="1" covered="9"/>
                      </method>
                    </class>
                  </package>
                </report>
                """);

        Map<MethodIdentity, CoverageData> result = JacocoCoverageParser.parse(xml);

        assertEquals(90.0, result.get(new MethodIdentity("demo.Sample", "alpha", "()V")).coveragePercent(), 0.001);
    }

    @Test
    void matchesByDescriptorEvenWhenLineNumberIsInvalid() throws IOException {
        Path xml = tempDir.resolve("jacoco-invalid-line.xml");
        Files.writeString(xml, """
                <report name="demo">
                  <package name="demo">
                    <class name="demo/Sample" sourcefilename="Sample.java">
                      <method name="alpha" desc="()V" line="oops">
                        <counter type="INSTRUCTION" missed="1" covered="9"/>
                      </method>
                    </class>
                  </package>
                </report>
                """);

        Map<MethodIdentity, CoverageData> result = JacocoCoverageParser.parse(xml);

        assertEquals(90.0, result.get(new MethodIdentity("demo.Sample", "alpha", "()V")).coveragePercent(), 0.001);
    }

    @Test
    void configuresSecureFactoryFeatures() throws Exception {
        var factory = JacocoCoverageParser.newSecureFactory();

        assertTrue(factory.getFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING));
        assertFalse(factory.getFeature("http://apache.org/xml/features/disallow-doctype-decl"));
        assertFalse(factory.getFeature("http://xml.org/sax/features/external-general-entities"));
        assertFalse(factory.getFeature("http://xml.org/sax/features/external-parameter-entities"));
        assertTrue(factory.getFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd"));
        assertFalse(factory.isXIncludeAware());
        assertFalse(factory.isExpandEntityReferences());
    }

    @Test
    void retainsOverloadsOnTheSameLineAndUsesOnlyInstructionCounters() throws IOException {
        Path xml = tempDir.resolve("same-line.xml");
        Files.writeString(xml, """
                <report><class name="demo/Sample">
                  <method name="alpha" desc="(I)I" line="10">
                    <counter type="BRANCH" missed="0" covered="8"/>
                    <counter type="INSTRUCTION" missed="3" covered="1"/>
                  </method>
                  <method name="alpha" desc="(J)I" line="10">
                    <counter type="INSTRUCTION" missed="1" covered="3"/>
                  </method>
                  <method name="alpha" desc="(D)I" line="10">
                    <counter type="BRANCH" missed="0" covered="8"/>
                  </method>
                  <method name="alpha" line="10">
                    <counter type="INSTRUCTION" missed="0" covered="8"/>
                  </method>
                </class></report>
                """);

        var result = JacocoCoverageParser.parse(xml);
        assertEquals(2, result.size());
        assertEquals(25.0, result.get(new MethodIdentity("demo.Sample", "alpha", "(I)I")).coveragePercent());
        assertEquals(75.0, result.get(new MethodIdentity("demo.Sample", "alpha", "(J)I")).coveragePercent());
    }

    @Test
    void rejectsDuplicateIdentitiesRatherThanSelectingByXmlOrder() throws IOException {
        Path xml = tempDir.resolve("duplicate.xml");
        Files.writeString(xml, """
                <report><class name="Sample">
                  <method name="alpha" desc="()I" line="10">
                    <counter type="INSTRUCTION" missed="0" covered="8"/>
                  </method>
                  <method name="alpha" desc="()I" line="20">
                    <counter type="INSTRUCTION" missed="8" covered="0"/>
                  </method>
                </class></report>
                """);

        assertThrows(IllegalStateException.class, () -> JacocoCoverageParser.parse(xml));
    }
}
