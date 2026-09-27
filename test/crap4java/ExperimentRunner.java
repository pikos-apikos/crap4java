package crap4java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.tools.ExecFileLoader;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.reflect.AccessFlag;

/** Runs the source-oracle and bytecode-candidate arms on one fixed evidence set. */
public final class ExperimentRunner {
    private static final Map<String, String> CONSTRUCTS = Map.ofEntries(
            Map.entry("straightLine", "straight-line"),
            Map.entry("ifElse", "if/else"),
            Map.entry("loop", "loops"),
            Map.entry("shortCircuit", "&& / ||"),
            Map.entry("switchCases", "switch"),
            Map.entry("switchWithoutDefault", "switch without explicit default"),
            Map.entry("plainTryCatch", "plain try/catch"),
            Map.entry("multiCatch", "multi-catch"),
            Map.entry("multipleCatchClauses", "multiple catch clauses"),
            Map.entry("nestedCatch", "nested catches"),
            Map.entry("tryFinally", "try/finally"),
            Map.entry("tryWithResources", "try-with-resources"),
            Map.entry("synchronizedBlock", "synchronized block"),
            Map.entry("synchronizedMethod", "synchronized method"),
            Map.entry("overloaded", "overloaded methods"),
            Map.entry("nestedMethod", "nested class"),
            Map.entry("transform", "bridge method"),
            Map.entry("lambdaMethod", "lambda / synthetic method"),
            Map.entry("main", "fixture driver")
    );

    private static final Map<String, String> EXCEPTION_CASES = Map.of(
            "plain try/catch", "plainTryCatch",
            "multi-catch", "multiCatch",
            "multiple catch clauses", "multipleCatchClauses",
            "nested catches", "nestedCatch",
            "try/finally", "tryFinally",
            "try-with-resources", "tryWithResources",
            "synchronized block", "synchronizedBlock",
            "synchronized method", "synchronizedMethod"
    );

    private ExperimentRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException("Expected: <sources> <classes> <jacoco.exec> <output> <revision> <maven-version> <warmups> <iterations>");
        }
        Path sourceRoot = Path.of(args[0]);
        Path classRoot = Path.of(args[1]);
        Path executionData = Path.of(args[2]);
        Path outputRoot = Path.of(args[3]);
        String revision = args[4];
        String mavenVersion = args[5];
        int warmups = Integer.parseInt(args[6]);
        int iterations = Integer.parseInt(args[7]);
        Files.createDirectories(outputRoot);

        Timed<SourceArm> source = measure(() -> sourceArm(sourceRoot), warmups, iterations);
        Timed<BytecodeArm> bytecode = measure(
                () -> bytecodeArm(classRoot, executionData), warmups, iterations);
        Comparison comparison = compare(source.value(), bytecode.value());

        String sourceHash = sha256Tree(sourceRoot, ".java");
        String classHash = sha256Tree(classRoot, ".class");
        String executionHash = sha256(executionData);
        writeMethodsCsv(outputRoot.resolve("methods.csv"), source.value(), bytecode.value(), comparison);
        writeComparisonJson(outputRoot.resolve("comparison.json"), revision, warmups, iterations,
                source, bytecode, comparison, sourceHash, classHash, executionHash, mavenVersion);
        writeReport(outputRoot.resolve("report.md"), revision, warmups, iterations,
                source, bytecode, comparison, sourceHash, classHash, executionHash, mavenVersion);

        System.out.printf("Source methods: %d; bytecode methods: %d; unique matches: %d; CC mismatches: %d%n",
                source.value().methods().size(), bytecode.value().methods().size(),
                comparison.uniquePairs(), comparison.ccMismatches().size());
        System.out.printf("Source-aligned bytecode CC: %d exact; %d residual mismatches; %d unavailable%n",
                comparison.sourceAlignedExactMatches().size(),
                comparison.sourceAlignedMismatches().size(), comparison.sourceAlignedUnavailable());
        if (!comparison.sourceAlignedMismatches().isEmpty() || comparison.sourceAlignedUnavailable() != 0) {
            throw new IllegalStateException("Source-aligned bytecode complexity did not reach parity; see experiment/results/report.md");
        }
    }

    private static <T> Timed<T> measure(Callable<T> analysis, int warmups, int iterations) throws Exception {
        for (int i = 0; i < warmups; i++) {
            analysis.call();
        }
        List<Long> samples = new ArrayList<>();
        T last = null;
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            last = analysis.call();
            samples.add(System.nanoTime() - start);
        }
        return new Timed<>(last, samples);
    }

    private static SourceArm sourceArm(Path sourceRoot) throws IOException {
        List<SourceMethod> methods = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(path);
                String file = sourceRoot.relativize(path).toString().replace('\\', '/');
                for (JavaMethodParser.MethodAnalysis method :
                        JavaMethodParser.analyze(path.getFileName().toString(), source)) {
                    methods.add(new SourceMethod(file, method.name(), method.startLine(), method.endLine(),
                            method.complexity(), method.defaultCases(), method.catchClauses(),
                            method.defaultCaseRanges(), method.catchClauseRanges(),
                            construct(method.name())));
                }
            }
        }
        methods.sort(Comparator.comparing(SourceMethod::file)
                .thenComparingInt(SourceMethod::startLine).thenComparing(SourceMethod::name));
        return new SourceArm(methods);
    }

    private static BytecodeArm bytecodeArm(Path classRoot, Path executionData) throws IOException {
        Map<String, JacocoMethod> jacoco = analyzeWithJacoco(classRoot, executionData);
        List<BytecodeMethod> methods = new ArrayList<>();
        ClassFile classFile = ClassFile.of(ClassFile.LineNumbersOption.PASS_LINE_NUMBERS);
        try (Stream<Path> paths = Files.walk(classRoot)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .sorted().toList()) {
                ClassModel model = classFile.parse(path);
                String owner = classRoot.relativize(path).toString().replace('\\', '/');
                owner = owner.substring(0, owner.length() - ".class".length());
                String sourceFile = owner.substring(owner.lastIndexOf('/') + 1).split("\\$")[0] + ".java";
                for (MethodModel method : model.methods()) {
                    String name = method.methodName().stringValue();
                    String descriptor = method.methodType().stringValue();
                    List<Integer> lines = method.code().stream()
                            .flatMap(code -> code.elementStream())
                            .filter(LineNumber.class::isInstance)
                            .map(LineNumber.class::cast)
                            .map(LineNumber::line)
                            .distinct().sorted().toList();
                    CodeFacts codeFacts = method.code().map(ExperimentRunner::codeFacts)
                            .orElseGet(CodeFacts::empty);
                    List<ExceptionCatch> exceptionHandlers = method.code().stream()
                            .flatMap(code -> code.exceptionHandlers().stream()).toList();
                    List<String> handlerTypes = exceptionHandlers.stream()
                            .map(ExperimentRunner::handlerType)
                            .sorted().toList();
                    String key = jacocoKey(owner, name, descriptor);
                    JacocoMethod coverage = jacoco.get(key);
                    methods.add(new BytecodeMethod(sourceFile, owner.replace('/', '.'), name, descriptor,
                            lines, coverage == null ? null : coverage.complexity(),
                            coverage == null ? null : coverage.coveredInstructions(),
                            coverage == null ? null : coverage.totalInstructions(), handlerTypes,
                            codeFacts.switchCount(), codeFacts.switchDefaultTargetLines(),
                            codeFacts.typedHandlerTargetCount(), codeFacts.typedHandlerTargetLines(),
                            codeFacts.catchAllHandlerTargetCount(), codeFacts.catchAllHandlerTargetLines(),
                            method.flags().has(AccessFlag.SYNTHETIC), method.flags().has(AccessFlag.BRIDGE),
                            method.flags().has(AccessFlag.SYNCHRONIZED), method.code().isPresent()));
                }
            }
        }
        methods.sort(Comparator.comparing(BytecodeMethod::owner).thenComparing(BytecodeMethod::name)
                .thenComparing(BytecodeMethod::descriptor));
        return new BytecodeArm(methods);
    }

    private static Map<String, JacocoMethod> analyzeWithJacoco(Path classRoot, Path executionData) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        loader.load(executionData.toFile());
        CoverageBuilder coverage = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), coverage);
        analyzer.analyzeAll(classRoot.toFile());
        Map<String, JacocoMethod> methods = new LinkedHashMap<>();
        for (IClassCoverage classCoverage : coverage.getClasses()) {
            for (IMethodCoverage method : classCoverage.getMethods()) {
                methods.put(jacocoKey(classCoverage.getName(), method.getName(), method.getDesc()),
                        new JacocoMethod(method.getComplexityCounter().getTotalCount(),
                                method.getInstructionCounter().getCoveredCount(),
                                method.getInstructionCounter().getTotalCount()));
            }
        }
        return methods;
    }

    private static String handlerType(ExceptionCatch handler) {
        return handler.catchType().map(type -> type.asInternalName().replace('/', '.')).orElse("catch-all");
    }

    private static CodeFacts codeFacts(CodeModel code) {
        Map<Label, Integer> positions = new LinkedHashMap<>();
        NavigableMap<Integer, Integer> lineNumbers = new TreeMap<>();
        int position = 0;
        for (CodeElement element : code) {
            if (element instanceof LabelTarget target) {
                positions.put(target.label(), position);
            }
            if (element instanceof LineNumber lineNumber) {
                lineNumbers.put(position, lineNumber.line());
            }
            if (element instanceof Instruction instruction) {
                position += instruction.sizeInBytes();
            }
        }

        int switchCount = 0;
        List<Integer> switchDefaultTargetLines = new ArrayList<>();
        for (CodeElement element : code) {
            Label defaultTarget = null;
            if (element instanceof TableSwitchInstruction tableSwitch) {
                defaultTarget = tableSwitch.defaultTarget();
            } else if (element instanceof LookupSwitchInstruction lookupSwitch) {
                defaultTarget = lookupSwitch.defaultTarget();
            }
            if (defaultTarget != null) {
                switchCount++;
                Integer line = targetLine(defaultTarget, positions, lineNumbers);
                if (line != null) switchDefaultTargetLines.add(line);
            }
        }

        Set<Label> typedTargets = new HashSet<>();
        Set<Label> catchAllTargets = new HashSet<>();
        List<Integer> typedHandlerTargetLines = new ArrayList<>();
        List<Integer> catchAllHandlerTargetLines = new ArrayList<>();
        for (ExceptionCatch handler : code.exceptionHandlers()) {
            Set<Label> targets = handler.catchType().isPresent() ? typedTargets : catchAllTargets;
            if (targets.add(handler.handler())) {
                Integer line = targetLine(handler.handler(), positions, lineNumbers);
                if (line != null) {
                    (handler.catchType().isPresent() ? typedHandlerTargetLines : catchAllHandlerTargetLines).add(line);
                }
            }
        }
        return new CodeFacts(switchCount, switchDefaultTargetLines,
                typedTargets.size(), typedHandlerTargetLines,
                catchAllTargets.size(), catchAllHandlerTargetLines);
    }

    private static Integer targetLine(Label target, Map<Label, Integer> positions,
                                      NavigableMap<Integer, Integer> lineNumbers) {
        Integer position = positions.get(target);
        if (position == null) return null;
        Map.Entry<Integer, Integer> line = lineNumbers.floorEntry(position);
        return line == null ? null : line.getValue();
    }

    private static String jacocoKey(String owner, String name, String descriptor) {
        return owner + "#" + name + descriptor;
    }

    private static Comparison compare(SourceArm source, BytecodeArm bytecode) {
        Map<SourceMethod, List<BytecodeMethod>> candidatesBySource = new LinkedHashMap<>();
        Map<BytecodeMethod, List<SourceMethod>> sourcesByCandidate = new LinkedHashMap<>();
        for (SourceMethod sourceMethod : source.methods()) {
            List<BytecodeMethod> candidates = bytecode.methods().stream()
                    .filter(method -> method.sourceFile().equals(sourceMethod.file()))
                    .filter(method -> method.name().equals(sourceMethod.name()))
                    .filter(method -> method.lines().stream().anyMatch(line ->
                            line >= sourceMethod.startLine() && line <= sourceMethod.endLine()))
                    .toList();
            candidatesBySource.put(sourceMethod, candidates);
            for (BytecodeMethod candidate : candidates) {
                sourcesByCandidate.computeIfAbsent(candidate, ignored -> new ArrayList<>()).add(sourceMethod);
            }
        }

        List<MethodPair> pairs = new ArrayList<>();
        List<SourceMethod> sourceOnly = new ArrayList<>();
        List<SourceMethod> ambiguousSources = new ArrayList<>();
        List<BytecodeMethod> bytecodeOnly = new ArrayList<>();
        List<BytecodeMethod> ambiguousBytecode = new ArrayList<>();
        for (Map.Entry<SourceMethod, List<BytecodeMethod>> entry : candidatesBySource.entrySet()) {
            SourceMethod sourceMethod = entry.getKey();
            List<BytecodeMethod> candidates = entry.getValue();
            if (candidates.isEmpty()) {
                sourceOnly.add(sourceMethod);
            } else if (candidates.size() == 1 && sourcesByCandidate.get(candidates.getFirst()).size() == 1) {
                pairs.add(new MethodPair(sourceMethod, candidates.getFirst()));
            } else {
                ambiguousSources.add(sourceMethod);
            }
        }
        for (BytecodeMethod method : bytecode.methods()) {
            List<SourceMethod> matches = sourcesByCandidate.getOrDefault(method, List.of());
            if (matches.isEmpty()) {
                bytecodeOnly.add(method);
            } else if (matches.size() > 1 || candidatesBySource.get(matches.getFirst()).size() > 1) {
                ambiguousBytecode.add(method);
            }
        }

        List<MethodPair> exact = pairs.stream().filter(pair -> pair.bytecode().jacocoComplexity() != null)
                .filter(pair -> pair.source().complexity() == pair.bytecode().jacocoComplexity()).toList();
        List<MethodPair> mismatches = pairs.stream().filter(pair -> pair.bytecode().jacocoComplexity() != null)
                .filter(pair -> pair.source().complexity() != pair.bytecode().jacocoComplexity()).toList();
        long noJacoco = pairs.stream().filter(pair -> pair.bytecode().jacocoComplexity() == null).count();
        List<MethodPair> alignedExact = pairs.stream()
                .filter(pair -> sourceAlignedBytecodeComplexity(pair) != null)
                .filter(pair -> pair.source().complexity() == sourceAlignedBytecodeComplexity(pair)).toList();
        List<MethodPair> alignedMismatches = pairs.stream()
                .filter(pair -> sourceAlignedBytecodeComplexity(pair) != null)
                .filter(pair -> pair.source().complexity() != sourceAlignedBytecodeComplexity(pair)).toList();
        long alignedUnavailable = pairs.stream()
                .filter(pair -> sourceAlignedBytecodeComplexity(pair) == null).count();
        Map<String, Long> mismatchCounts = new TreeMap<>();
        for (MethodPair mismatch : mismatches) {
            mismatchCounts.merge(mismatch.source().construct(), 1L, Long::sum);
        }
        Map<String, Long> bytecodeOnlyReasons = new TreeMap<>();
        for (BytecodeMethod method : bytecodeOnly) {
            bytecodeOnlyReasons.merge(bytecodeOnlyReason(method), 1L, Long::sum);
        }
        return new Comparison(pairs, exact, mismatches, noJacoco, alignedExact, alignedMismatches,
                alignedUnavailable, sourceOnly, ambiguousSources,
                bytecodeOnly, ambiguousBytecode, mismatchCounts, bytecodeOnlyReasons, sourcesByCandidate);
    }

    private static Integer sourceAlignedBytecodeComplexity(MethodPair pair) {
        BytecodeMethod method = pair.bytecode();
        SourceMethod source = pair.source();
        if (method.jacocoComplexity() == null
                || source.defaultCaseCount() != source.defaultCaseRanges().size()
                || source.catchClauseCount() != source.catchClauseRanges().size()) {
            return null;
        }
        int matchedDefaults = matchedSourceRanges(source.defaultCaseRanges(), method.switchDefaultTargetLines());
        int matchedCatches = matchedSourceRanges(source.catchClauseRanges(), method.typedHandlerTargetLines());
        if (matchedDefaults != source.defaultCaseCount() || matchedCatches != source.catchClauseCount()) {
            return null;
        }
        return method.jacocoComplexity() + matchedDefaults + matchedCatches;
    }

    private static int matchedSourceRanges(List<JavaMethodParser.SourceLineRange> ranges,
                                          List<Integer> bytecodeTargetLines) {
        List<Integer> unmatchedLines = new ArrayList<>(bytecodeTargetLines);
        int matched = 0;
        for (JavaMethodParser.SourceLineRange range : ranges) {
            int targetIndex = -1;
            for (int i = 0; i < unmatchedLines.size(); i++) {
                if (range.contains(unmatchedLines.get(i))) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex >= 0) {
                unmatchedLines.remove(targetIndex);
                matched++;
            }
        }
        return matched;
    }

    private static String bytecodeOnlyReason(BytecodeMethod method) {
        if (method.name().equals("<init>")) return "constructor";
        if (method.name().equals("<clinit>")) return "class initializer";
        if (method.bridge()) return "bridge method";
        if (method.synthetic()) return "synthetic/compiler-generated";
        return "no source-method match";
    }

    private static void writeMethodsCsv(Path path, SourceArm source, BytecodeArm bytecode,
                                        Comparison comparison) throws IOException {
        Map<SourceMethod, MethodPair> pairs = new LinkedHashMap<>();
        for (MethodPair pair : comparison.pairs()) {
            pairs.put(pair.source(), pair);
        }
        StringBuilder csv = new StringBuilder("side,source_file,class,method,descriptor,start_line,end_line,source_cc,jacoco_cc,covered_instructions,total_instructions,exception_handler_count,exception_handler_types,synthetic,bridge,synchronized,has_code,match_status,match_candidates,source_default_case_count,source_catch_clause_count,switch_instruction_count,typed_handler_target_count,catch_all_handler_target_count,source_aligned_bytecode_cc,source_aligned_status\n");
        for (SourceMethod method : source.methods()) {
            MethodPair pair = pairs.get(method);
            String status = pair == null ? comparison.ambiguousSources().contains(method) ? "ambiguous" : "source-only" : "unique-match";
            List<BytecodeMethod> candidateMethods = pair == null ? bytecode.methods().stream()
                    .filter(candidate -> candidate.sourceFile().equals(method.file()))
                    .filter(candidate -> candidate.name().equals(method.name()))
                    .filter(candidate -> candidate.lines().stream().anyMatch(line -> line >= method.startLine() && line <= method.endLine()))
                    .toList() : List.of(pair.bytecode());
            String candidates = candidateMethods.stream().map(BytecodeMethod::key).sorted()
                    .reduce((a, b) -> a + " | " + b).orElse("");
            String candidateClasses = candidateMethods.stream().map(BytecodeMethod::owner).distinct().sorted()
                    .reduce((a, b) -> a + " | " + b).orElse("");
            BytecodeMethod candidate = pair == null ? null : pair.bytecode();
            Integer alignedCc = pair == null ? null : sourceAlignedBytecodeComplexity(pair);
            appendCsv(csv, "source", method.file(), candidateClasses, method.name(), "", method.startLine(), method.endLine(),
                    method.complexity(), "", "", "", "", "", "", "", "", status, candidates,
                    method.defaultCaseCount(), method.catchClauseCount(),
                    candidate == null ? "" : candidate.switchCount(),
                    candidate == null ? "" : candidate.typedHandlerTargetCount(),
                    candidate == null ? "" : candidate.catchAllHandlerTargetCount(), alignedCc,
                    alignedStatus(pair, alignedCc));
        }
        for (BytecodeMethod method : bytecode.methods()) {
            String status = comparison.ambiguousBytecode().contains(method) ? "ambiguous"
                    : comparison.pairs().stream().anyMatch(pair -> pair.bytecode().equals(method)) ? "unique-match" : "bytecode-only";
            MethodPair pair = comparison.pairs().stream().filter(candidate -> candidate.bytecode().equals(method))
                    .findFirst().orElse(null);
            String sourceCc = pair == null ? "" : Integer.toString(pair.source().complexity());
            Integer alignedCc = pair == null ? null : sourceAlignedBytecodeComplexity(pair);
            appendCsv(csv, "bytecode", method.sourceFile(), method.owner(), method.name(), method.descriptor(),
                    method.lines().stream().min(Integer::compareTo).orElse(null),
                    method.lines().stream().max(Integer::compareTo).orElse(null), sourceCc,
                    method.jacocoComplexity(), method.coveredInstructions(), method.totalInstructions(),
                    method.handlerTypes().size(), String.join(";", method.handlerTypes()), method.synthetic(),
                    method.bridge(), method.synchronizedMethod(), method.hasCode(), status,
                    comparison.sourceCandidatesFor(method),
                    pair == null ? "" : pair.source().defaultCaseCount(),
                    pair == null ? "" : pair.source().catchClauseCount(), method.switchCount(),
                    method.typedHandlerTargetCount(), method.catchAllHandlerTargetCount(), alignedCc,
                    alignedStatus(pair, alignedCc));
        }
        Files.writeString(path, csv, StandardCharsets.UTF_8);
    }

    private static String alignedStatus(MethodPair pair, Integer alignedCc) {
        if (pair == null) return "unmapped";
        if (alignedCc == null) return "insufficient-bytecode-evidence";
        return alignedCc == pair.source().complexity() ? "exact" : "mismatch";
    }

    private static void appendCsv(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) csv.append(',');
            String value = values[i] == null ? "" : values[i].toString();
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append('\n');
    }

    private static void writeComparisonJson(Path path, String revision, int warmups, int iterations,
                                            Timed<SourceArm> source, Timed<BytecodeArm> bytecode,
                                            Comparison comparison, String sourceHash, String classHash,
                                            String executionHash, String mavenVersion) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schemaVersion\": 1,");
        json.append("\n  \"generatedAtUtc\": ").append(json(Instant.now().toString())).append(',');
        json.append("\n  \"repositoryRevision\": ").append(json(revision)).append(',');
        json.append("\n  \"environment\": {\"javaVersion\": ").append(json(System.getProperty("java.version")))
                .append(", \"javaVendor\": ").append(json(System.getProperty("java.vendor")))
                .append(", \"mavenVersion\": ").append(json(mavenVersion))
                .append(", \"os\": ").append(json(System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")))
                .append("},");
        json.append("\n  \"fixedEvidenceSha256\": {\"sources\": ").append(json(sourceHash))
                .append(", \"classFiles\": ").append(json(classHash))
                .append(", \"jacocoExec\": ").append(json(executionHash)).append("},");
        json.append("\n  \"measurement\": {\"warmupsPerArm\": ").append(warmups)
                .append(", \"iterationsPerArm\": ").append(iterations)
                .append(", \"sourceAnalysisNanos\": ").append(longArray(source.samples()))
                .append(", \"bytecodeAnalysisNanos\": ").append(longArray(bytecode.samples())).append("},");
        json.append("\n  \"counts\": {\"sourceMethods\": ").append(source.value().methods().size())
                .append(", \"bytecodeMethods\": ").append(bytecode.value().methods().size())
                .append(", \"uniqueSourceBytecodePairs\": ").append(comparison.uniquePairs())
                .append(", \"sourceOnly\": ").append(comparison.sourceOnly().size())
                .append(", \"ambiguousSourceMappings\": ").append(comparison.ambiguousSources().size())
                .append(", \"bytecodeOnly\": ").append(comparison.bytecodeOnly().size())
                .append(", \"ambiguousBytecodeMappings\": ").append(comparison.ambiguousBytecode().size())
                .append(", \"exactCcMatches\": ").append(comparison.exactMatches().size())
                .append(", \"ccMismatches\": ").append(comparison.ccMismatches().size())
                .append(", \"ccUnavailable\": ").append(comparison.noJacoco())
                .append(", \"sourceAlignedExactCcMatches\": ").append(comparison.sourceAlignedExactMatches().size())
                .append(", \"sourceAlignedCcMismatches\": ").append(comparison.sourceAlignedMismatches().size())
                .append(", \"sourceAlignedCcUnavailable\": ").append(comparison.sourceAlignedUnavailable()).append("},");
        json.append("\n  \"mismatchesByConstruct\": ").append(longMap(comparison.mismatchCounts())).append(',');
        json.append("\n  \"bytecodeOnlyByReason\": ").append(longMap(comparison.bytecodeOnlyReasons())).append(',');
        json.append("\n  \"ccMismatches\": [");
        for (int i = 0; i < comparison.ccMismatches().size(); i++) {
            MethodPair pair = comparison.ccMismatches().get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"construct\": ").append(json(pair.source().construct()))
                    .append(", \"sourceIdentity\": ").append(json(pair.source().identity()))
                    .append(", \"bytecodeIdentity\": ").append(json(pair.bytecode().key()))
                    .append(", \"sourceCc\": ").append(pair.source().complexity())
                    .append(", \"jacocoCc\": ").append(pair.bytecode().jacocoComplexity())
                    .append(", \"exceptionHandlerTypes\": ").append(stringArray(pair.bytecode().handlerTypes()))
                    .append('}');
        }
        json.append("\n  ],");
        json.append("\n  \"sourceAlignedCcMismatches\": [");
        for (int i = 0; i < comparison.sourceAlignedMismatches().size(); i++) {
            MethodPair pair = comparison.sourceAlignedMismatches().get(i);
            if (i > 0) json.append(',');
            json.append("\n    {\"sourceIdentity\": ").append(json(pair.source().identity()))
                    .append(", \"bytecodeIdentity\": ").append(json(pair.bytecode().key()))
                    .append(", \"sourceCc\": ").append(pair.source().complexity())
                    .append(", \"sourceAlignedBytecodeCc\": ").append(sourceAlignedBytecodeComplexity(pair))
                    .append('}');
        }
        json.append("\n  ],");
        json.append("\n  \"sourceAlignedAdjustments\": [");
        for (int i = 0; i < comparison.pairs().size(); i++) {
            MethodPair pair = comparison.pairs().get(i);
            if (i > 0) json.append(',');
            Integer adjusted = sourceAlignedBytecodeComplexity(pair);
            json.append("\n    {\"sourceIdentity\": ").append(json(pair.source().identity()))
                    .append(", \"bytecodeIdentity\": ").append(json(pair.bytecode().key()))
                    .append(", \"jacocoCc\": ").append(pair.bytecode().jacocoComplexity())
                    .append(", \"sourceDefaultCases\": ").append(pair.source().defaultCaseCount())
                    .append(", \"bytecodeSwitchInstructions\": ").append(pair.bytecode().switchCount())
                    .append(", \"bytecodeSwitchDefaultTargetLines\": ").append(integerArray(pair.bytecode().switchDefaultTargetLines()))
                    .append(", \"sourceCatchClauses\": ").append(pair.source().catchClauseCount())
                    .append(", \"bytecodeTypedHandlerTargets\": ").append(pair.bytecode().typedHandlerTargetCount())
                    .append(", \"bytecodeTypedHandlerTargetLines\": ").append(integerArray(pair.bytecode().typedHandlerTargetLines()))
                    .append(", \"bytecodeCatchAllHandlerTargetLines\": ").append(integerArray(pair.bytecode().catchAllHandlerTargetLines()))
                    .append(", \"sourceAlignedBytecodeCc\": ").append(adjusted == null ? "null" : adjusted)
                    .append('}');
        }
        json.append("\n  ],");
        json.append("\n  \"exceptionHandlerMatrix\": ").append(exceptionMatrix(bytecode.value()));
        json.append("\n}\n");
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private static void writeReport(Path path, String revision, int warmups, int iterations,
                                    Timed<SourceArm> source, Timed<BytecodeArm> bytecode,
                                    Comparison comparison, String sourceHash, String classHash,
                                    String executionHash, String mavenVersion) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("# Java 25 bytecode-native CRAP experiment\n\n")
                .append("Generated at ").append(Instant.now()).append(" from `").append(revision).append("`.\n\n")
                .append("## Environment and fixed evidence\n\n")
                .append("- Runtime: Java ").append(System.getProperty("java.version"))
                .append(" ( ").append(System.getProperty("java.vendor")).append(" ).\n")
                .append("- Maven: ").append(mavenVersion).append(".\n")
                .append("- OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" / ").append(System.getProperty("os.arch")).append(".\n")
                .append("- Warmups per arm: ").append(warmups).append("; measured runs per arm: ").append(iterations).append(".\n")
                .append("- Fixture source SHA-256: `").append(sourceHash).append("`.\n")
                .append("- Compiled class-file SHA-256: `").append(classHash).append("`.\n")
                .append("- JaCoCo execution-data SHA-256: `").append(executionHash).append("`.\n\n")
                .append("Compilation, fixture execution, and coverage generation ran once before either measured arm. Each timed source pass rereads and parses the fixture source with the unchanged `JavaMethodParser`. Each timed bytecode pass rereads the same class files and JaCoCo execution data, then extracts method metadata with `java.lang.classfile` and JaCoCo analysis.\n\n")
                .append("## Method-set and complexity parity\n\n")
                .append("| Measure | Count |\n|---|---:|\n")
                .append("| Source methods | ").append(source.value().methods().size()).append(" |\n")
                .append("| Bytecode methods | ").append(bytecode.value().methods().size()).append(" |\n")
                .append("| Unique source-to-bytecode mappings | ").append(comparison.uniquePairs()).append(" |\n")
                .append("| Source-only methods | ").append(comparison.sourceOnly().size()).append(" |\n")
                .append("| Ambiguous source mappings | ").append(comparison.ambiguousSources().size()).append(" |\n")
                .append("| Bytecode-only methods | ").append(comparison.bytecodeOnly().size()).append(" |\n")
                .append("| Ambiguous bytecode mappings | ").append(comparison.ambiguousBytecode().size()).append(" |\n")
                .append("| Exact CC matches among unique mapped methods | ").append(comparison.exactMatches().size()).append(" |\n")
                .append("| CC mismatches among unique mapped methods | ").append(comparison.ccMismatches().size()).append(" |\n")
                .append("| Mapped methods without JaCoCo CC | ").append(comparison.noJacoco()).append(" |\n")
                .append("| Exact matches after source-alignment adjustment | ").append(comparison.sourceAlignedExactMatches().size()).append(" |\n")
                .append("| Residual mismatches after source-alignment adjustment | ").append(comparison.sourceAlignedMismatches().size()).append(" |\n")
                .append("| Methods without sufficient class-file evidence for adjustment | ").append(comparison.sourceAlignedUnavailable()).append(" |\n\n");
        if (!comparison.bytecodeOnlyReasons().isEmpty()) {
            report.append("Bytecode-only methods by observable category: ");
            report.append(comparison.bytecodeOnlyReasons().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("none")).append(".\n\n");
        }
        report.append("### Analysis-only timings\n\n")
                .append("| Arm | Median | Range | Runs (ms) |\n|---|---:|---:|---|\n")
                .append(timingRow("Source oracle", source.samples())).append('\n')
                .append(timingRow("Bytecode + JaCoCo", bytecode.samples())).append("\n\n")
                .append("Build, test, fixture compilation, and coverage-generation time are excluded.\n\n");

        report.append("### CC mismatches by construct\n\n");
        if (comparison.mismatchCounts().isEmpty()) {
            report.append("No uniquely mapped CC mismatches.\n\n");
        } else {
            report.append("| Construct | Mismatches |\n|---|---:|\n");
            comparison.mismatchCounts().forEach((name, count) -> report.append("| ").append(name).append(" | ").append(count).append(" |\n"));
            report.append('\n');
        }
        report.append("### Individual mismatches\n\n");
        if (comparison.ccMismatches().isEmpty()) {
            report.append("None.\n\n");
        } else {
            report.append("| Construct | Source identity | Bytecode identity | Source CC | JaCoCo CC | Exception table types |\n|---|---|---|---:|---:|---|\n");
            for (MethodPair pair : comparison.ccMismatches()) {
                report.append("| ").append(pair.source().construct()).append(" | `")
                        .append(pair.source().identity()).append("` | `").append(pair.bytecode().key())
                        .append("` | ").append(pair.source().complexity()).append(" | ")
                        .append(pair.bytecode().jacocoComplexity()).append(" | ")
                        .append(pair.bytecode().handlerTypes().isEmpty() ? "none" : String.join(", ", pair.bytecode().handlerTypes()))
                        .append(" |\n");
            }
            report.append('\n');
        }

        report.append("### Source-aligned bytecode complexity\n\n")
                .append("The normalized value is JaCoCo CC plus explicit source `default` labels and source `catch` clauses whose line ranges match the corresponding bytecode switch default target or typed handler target. A multi-catch contributes one source clause even when its handler table has multiple type entries. Catch-all cleanup handlers are not added. This aligns the experiment's bytecode value to the existing source parser; it is a calibration result, not an independent bytecode-only metric.\n\n")
                .append("| Method | Source CC | JaCoCo CC | `default` adjustment | `catch` adjustment | Aligned bytecode CC |\n|---|---:|---:|---:|---:|---:|");
        for (MethodPair pair : comparison.pairs()) {
            Integer aligned = sourceAlignedBytecodeComplexity(pair);
            if (aligned == null || aligned.equals(pair.bytecode().jacocoComplexity())) continue;
            report.append("\n| `").append(pair.source().name()).append("`")
                    .append(" | ").append(pair.source().complexity())
                    .append(" | ").append(pair.bytecode().jacocoComplexity())
                    .append(" | ").append(pair.source().defaultCaseCount())
                    .append(" | ").append(pair.source().catchClauseCount())
                    .append(" | ").append(aligned).append(" |");
        }
        if (comparison.sourceAlignedMismatches().isEmpty()) {
            report.append("\n\nEvery uniquely mapped method with sufficient class-file evidence matches the source parser after alignment.\n\n");
        } else {
            report.append("\n\nResidual source-aligned mismatches remain; inspect `sourceAlignedCcMismatches` in `comparison.json`.\n\n");
        }

        report.append("## Exception-handler observations\n\n")
                .append("The JDK Class-File API reports exception-table entries and groups typed entries by handler target, so multi-catch entries sharing a handler are not mistaken for separate source clauses. Catch-all entries remain separate because they may represent `finally`, synchronized cleanup, or try-with-resources scaffolding.\n\n")
                .append("| Source construct | Bytecode methods found | Exception-table entries | Handler types | Synchronized flag |\n|---|---|---:|---|---|");
        for (Map.Entry<String, String> entry : EXCEPTION_CASES.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            List<BytecodeMethod> related = bytecode.value().methods().stream().filter(m -> m.name().equals(entry.getValue())).toList();
            int handlers = related.stream().mapToInt(m -> m.handlerTypes().size()).sum();
            List<String> types = related.stream().flatMap(m -> m.handlerTypes().stream()).distinct().sorted().toList();
            report.append("\n| ").append(entry.getKey()).append(" | ").append(related.size()).append(" | ")
                    .append(handlers).append(" | ").append(types.isEmpty() ? "none" : String.join(", ", types))
                    .append(" | ").append(related.stream().anyMatch(BytecodeMethod::synchronizedMethod) ? "yes" : "no").append(" |");
        }
        report.append("\n\n## Reproduction commands\n\n")
                .append("```bash\n")
                .append("bash experiment/run.sh\n")
                .append("# The script runs: mvn -B clean test\n")
                .append("# Then: javac --release 25 -g -d target/experiment/classes <sorted fixture sources>\n")
                .append("# Then runs FixtureMatrix once with the JaCoCo 0.8.14 runtime agent\n")
                .append("# Finally runs crap4java.ExperimentRunner against those fixed files\n")
                .append("```\n\n")
                .append("## Evidence-limited conclusion\n\n")
                .append("This fixture run measures parity and cost only for the listed Java constructs on this JDK. Source labels are needed to distinguish explicit `default`/`catch` constructs from implicit switch targets and compiler-generated handlers; class-file evidence confirms the corresponding branch or handler exists. The normalized result therefore does not establish a source-independent replacement for the production parser.\n\n")
                .append("### Next experiment\n\n")
                .append("Repeat the same fixed-evidence comparison on one larger real-world Java repository, if a local checkout is available, while retaining the same source oracle and mismatch-preservation rules.\n");
        Files.writeString(path, report, StandardCharsets.UTF_8);
    }

    private static String timingRow(String label, List<Long> samples) {
        List<Double> millis = samples.stream().map(value -> value / 1_000_000.0).sorted().toList();
        double median = millis.get(millis.size() / 2);
        String range = String.format("%.3f–%.3f", millis.getFirst(), millis.getLast());
        String runs = millis.stream().map(value -> String.format("%.3f", value)).reduce((a, b) -> a + ", " + b).orElse("");
        return "| " + label + " | " + String.format("%.3f ms", median) + " | " + range + " ms | " + runs + " |";
    }

    private static String exceptionMatrix(BytecodeArm bytecode) {
        StringBuilder json = new StringBuilder("{");
        boolean firstCase = true;
        for (Map.Entry<String, String> entry : EXCEPTION_CASES.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (!firstCase) json.append(',');
            firstCase = false;
            List<BytecodeMethod> methods = bytecode.methods().stream().filter(m -> m.name().equals(entry.getValue())).toList();
            int count = methods.stream().mapToInt(m -> m.handlerTypes().size()).sum();
            List<String> types = methods.stream().flatMap(m -> m.handlerTypes().stream()).distinct().sorted().toList();
            json.append("\n    ").append(json(entry.getKey())).append(": {\"methodCount\": ").append(methods.size())
                    .append(", \"exceptionHandlerCount\": ").append(count)
                    .append(", \"handlerTypes\": ").append(stringArray(types))
                    .append(", \"synchronizedMethodFlag\": ")
                    .append(methods.stream().anyMatch(BytecodeMethod::synchronizedMethod)).append('}');
        }
        return json.append("\n  }").toString();
    }

    private static String construct(String method) {
        return CONSTRUCTS.getOrDefault(method, "other");
    }

    private static String sha256(Path file) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String sha256Tree(Path root, String suffix) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(suffix)).sorted().toList()) {
                digest.update(root.relativize(path).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(path));
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) escaped.append(String.format("\\u%04x", (int) ch));
                    else escaped.append(ch);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String longArray(Collection<Long> values) {
        return "[" + values.stream().map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("") + "]";
    }

    private static String integerArray(Collection<Integer> values) {
        return "[" + values.stream().map(Object::toString).reduce((a, b) -> a + ", " + b).orElse("") + "]";
    }

    private static String stringArray(Collection<String> values) {
        return "[" + values.stream().map(ExperimentRunner::json).reduce((a, b) -> a + ", " + b).orElse("") + "]";
    }

    private static String longMap(Map<String, Long> values) {
        return "{" + values.entrySet().stream().map(entry -> json(entry.getKey()) + ": " + entry.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("") + "}";
    }

    private static String timingJson(List<Long> values) {
        return longArray(values);
    }

    private record SourceMethod(String file, String name, int startLine, int endLine, int complexity,
                                int defaultCaseCount, int catchClauseCount,
                                List<JavaMethodParser.SourceLineRange> defaultCaseRanges,
                                List<JavaMethodParser.SourceLineRange> catchClauseRanges,
                                String construct) {
        String identity() {
            return file + "#" + name + "@" + startLine + "-" + endLine;
        }
    }

    private record SourceArm(List<SourceMethod> methods) {
    }

    private record JacocoMethod(int complexity, int coveredInstructions, int totalInstructions) {
    }

    private record BytecodeMethod(String sourceFile, String owner, String name, String descriptor,
                                  List<Integer> lines, Integer jacocoComplexity, Integer coveredInstructions,
                                  Integer totalInstructions, List<String> handlerTypes, int switchCount,
                                  List<Integer> switchDefaultTargetLines, int typedHandlerTargetCount,
                                  List<Integer> typedHandlerTargetLines, int catchAllHandlerTargetCount,
                                  List<Integer> catchAllHandlerTargetLines,
                                  boolean synthetic, boolean bridge, boolean synchronizedMethod, boolean hasCode) {
        String key() {
            return owner + "#" + name + descriptor;
        }
    }

    private record BytecodeArm(List<BytecodeMethod> methods) {
    }

    private record CodeFacts(int switchCount, List<Integer> switchDefaultTargetLines,
                             int typedHandlerTargetCount, List<Integer> typedHandlerTargetLines,
                             int catchAllHandlerTargetCount, List<Integer> catchAllHandlerTargetLines) {
        static CodeFacts empty() {
            return new CodeFacts(0, List.of(), 0, List.of(), 0, List.of());
        }
    }

    private record MethodPair(SourceMethod source, BytecodeMethod bytecode) {
    }

    private record Comparison(List<MethodPair> pairs, List<MethodPair> exactMatches,
                              List<MethodPair> ccMismatches, long noJacoco,
                              List<MethodPair> sourceAlignedExactMatches,
                              List<MethodPair> sourceAlignedMismatches, long sourceAlignedUnavailable,
                              List<SourceMethod> sourceOnly, List<SourceMethod> ambiguousSources,
                              List<BytecodeMethod> bytecodeOnly, List<BytecodeMethod> ambiguousBytecode,
                              Map<String, Long> mismatchCounts, Map<String, Long> bytecodeOnlyReasons,
                              Map<BytecodeMethod, List<SourceMethod>> sourcesByCandidate) {
        int uniquePairs() {
            return pairs.size();
        }

        String sourceCandidatesFor(BytecodeMethod method) {
            return sourcesByCandidate.getOrDefault(method, List.of()).stream()
                    .map(SourceMethod::identity).sorted().reduce((a, b) -> a + " | " + b).orElse("");
        }
    }

    private record Timed<T>(T value, List<Long> samples) {
    }
}
