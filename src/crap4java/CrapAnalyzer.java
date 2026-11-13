package crap4java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CrapAnalyzer {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w.]*)\\s*;");

    private CrapAnalyzer() {
    }

    static List<MethodMetrics> analyze(Path projectRoot, List<Path> changedFiles, Path jacocoXml) throws IOException {
        Map<MethodIdentity, CoverageData> coverageMap = JacocoCoverageParser.parse(jacocoXml);
        List<MethodMetrics> metrics = new ArrayList<>();
        List<Path> classPath = List.of(projectRoot.resolve("target/classes"),
                projectRoot.resolve("target/test-classes")).stream().filter(Files::isDirectory).toList();

        for (Path file : changedFiles) {
            if (!Files.exists(file)) {
                continue;
            }
            String source = Files.readString(file);
            String className = classNameFromSource(file, source);
            List<MethodDescriptor> methods = JavaMethodParser.parse(className, source, classPath);
            for (MethodDescriptor method : methods) {
                Double coverage = lookupCoverage(coverageMap, method.identity());
                Double crap = CrapScore.calculate(method.complexity(), coverage);
                String owner = method.identity() == null ? className : method.identity().className();
                metrics.add(new MethodMetrics(method.name(), owner, method.complexity(), coverage, crap));
            }
        }

        metrics.sort(Comparator.comparing(MethodMetrics::crapScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return metrics;
    }

    static String classNameFromSource(Path file, String source) {
        String simpleName = file.getFileName().toString().replaceFirst("\\.java$", "");
        Matcher matcher = PACKAGE_PATTERN.matcher(source);
        if (!matcher.find()) {
            return simpleName;
        }
        return matcher.group(1) + "." + simpleName;
    }

    static Double lookupCoverage(Map<MethodIdentity, CoverageData> coverageMap,
                                 MethodIdentity identity) {
        if (identity == null || identity.descriptor() == null) {
            return null;
        }
        CoverageData coverage = coverageMap.get(identity);
        return coverage == null ? null : coverage.coveragePercent();
    }
}

/* mutate4java-manifest
version=1
moduleHash=46c52126077b440bdeb3a9d7e9e96d62ba5ba39f6d5a8e52c37a9611fb4e5472
scope.0.id=Y2xhc3M6Q3JhcEFuYWx5emVyI0NyYXBBbmFseXplcjoxMw
scope.0.kind=class
scope.0.startLine=13
scope.0.endLine=117
scope.0.semanticHash=6250b01e658e7385c7cd736192b5b7750c307fef9bf76a5b82f2e68f40e351cc
scope.1.id=ZmllbGQ6Q3JhcEFuYWx5emVyI1BBQ0tBR0VfUEFUVEVSTjoxNQ
scope.1.kind=field
scope.1.startLine=15
scope.1.endLine=15
scope.1.semanticHash=f68e81784bda450afae9841592611ed5ab70438167019556d7b43228d096ff2e
scope.2.id=bWV0aG9kOkNyYXBBbmFseXplciNhbmFseXplKDMpOjIw
scope.2.kind=method
scope.2.startLine=20
scope.2.endLine=41
scope.2.semanticHash=9817894754035fbaa337b9d73b70bee319da584e850b8efa44c672c8d27a25c0
scope.3.id=bWV0aG9kOkNyYXBBbmFseXplciNjbGFzc05hbWVGcm9tU291cmNlKDIpOjQz
scope.3.kind=method
scope.3.startLine=43
scope.3.endLine=50
scope.3.semanticHash=815b935247b214866078b33c912be7c1d85f580bae95101447b397e5a162ab57
scope.4.id=bWV0aG9kOkNyYXBBbmFseXplciNjdG9yKDApOjE3
scope.4.kind=method
scope.4.startLine=17
scope.4.endLine=18
scope.4.semanticHash=4239165039ff734e78d5721158cba4fb480bd63519b0c9f393841dc6d397f477
scope.5.id=bWV0aG9kOkNyYXBBbmFseXplciNleGFjdENvdmVyYWdlKDQpOjY4
scope.5.kind=method
scope.5.startLine=68
scope.5.endLine=78
scope.5.semanticHash=14eb33c9a59b00104d7d7437435c2543411b16e5d6b0543501547f1df6d2ac17
scope.6.id=bWV0aG9kOkNyYXBBbmFseXplciNsb29rdXBDb3ZlcmFnZSg0KTo1Mg
scope.6.kind=method
scope.6.startLine=52
scope.6.endLine=66
scope.6.semanticHash=e76e3b4434ed4eb1dcd67ed1abe792a6aeac52d1e2bee7f3ce9fb474860c9f33
scope.7.id=bWV0aG9kOkNyYXBBbmFseXplciNuZWFyZXN0Q292ZXJhZ2UoNCk6ODA
scope.7.kind=method
scope.7.startLine=80
scope.7.endLine=100
scope.7.semanticHash=5605b5c1db815b66f699088064572a6d3d612461b0e8f12f43daaf60e0ed77e6
scope.8.id=bWV0aG9kOkNyYXBBbmFseXplciNwYXJzZVRyYWlsaW5nTGluZSgxKToxMDI
scope.8.kind=method
scope.8.startLine=102
scope.8.endLine=116
scope.8.semanticHash=8b3365a8721f95688d6e1b9e66ee9b2bfe2a00905ebb8acc307492ca26e19588
*/
