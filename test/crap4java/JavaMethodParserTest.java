package crap4java;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaMethodParserTest {

    @Test
    void extractsConcreteMethodsWithLinesAndComplexity() {
        String source = """
                package demo;
                class Sample {
                    int alpha(boolean a, boolean b) {
                        if (a && b) {
                            return 1;
                        }
                        return 0;
                    }

                    int beta(int x) {
                        switch (x) {
                            case 1: return 1;
                            case 2: return 2;
                            default: return 0;
                        }
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("demo.Sample", source);

        assertEquals(List.of(
                new MethodDescriptor("alpha", 3, 8, 3, new MethodIdentity("demo.Sample", "alpha", "(ZZ)I")),
                new MethodDescriptor("beta", 10, 16, 4, new MethodIdentity("demo.Sample", "beta", "(I)I"))
        ), methods);
    }

    @Test
    void ignoresConstructorsAndAbstractMethods() {
        String source = """
                abstract class Sample {
                    Sample() {
                    }

                    abstract int missing();

                    int present() {
                        return 1;
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("Sample", source);

        assertEquals(List.of(new MethodDescriptor("present", 7, 9, 1, new MethodIdentity("Sample", "present", "()I"))), methods);
    }

    @Test
    void ignoresMethodsDeclaredInsideAnonymousClasses() {
        String source = """
                class Sample {
                    int outer() {
                        Runnable runnable = new Runnable() {
                            @Override
                            public void run() {
                                if (true) {
                                }
                            }
                        };
                        return 1;
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("Sample", source);

        assertEquals(List.of(new MethodDescriptor("outer", 2, 11, 1, new MethodIdentity("Sample", "outer", "()I"))), methods);
    }

    @Test
    void parsesMethodsWithoutResolvingSiblingTypes() {
        String source = """
                package demo;

                class Sample {
                    Helper helper() {
                        return new Helper();
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("demo.Sample", source);

        assertEquals(List.of(new MethodDescriptor("helper", 4, 6, 1, new MethodIdentity("demo.Sample", "helper", null))), methods);
    }

    @Test
    void ignoresKeywordsInsideCommentsAndStrings() {
        String source = """
                class Sample {
                    int stable() {
                        String text = "if && || ? case default catch";
                        // if && || ? case default catch
                        /* if && || ? case default catch */
                        return 1;
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("Sample", source);

        assertEquals(List.of(new MethodDescriptor("stable", 2, 7, 1, new MethodIdentity("Sample", "stable", "()I"))), methods);
    }

    @Test
    void countsDecisionNodesFromTheAst() {
        String source = """
                class Sample {
                    int score(boolean a, boolean b, int[] values) {
                        for (int i = 0; i < values.length; i++) {
                        }
                        for (int value : values) {
                        }
                        while (a) {
                            a = false;
                        }
                        do {
                            b = false;
                        } while (b);
                        if (a && b || values.length > 0) {
                        }
                        try {
                            return a ? 1 : 0;
                        } catch (RuntimeException ex) {
                            return 2;
                        }
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("Sample", source);

        assertEquals(List.of(new MethodDescriptor("score", 2, 20, 10, new MethodIdentity("Sample", "score", "(ZZ[I)I"))), methods);
    }

    @Test
    void visitsNestedDecisionNodesInsideOtherDecisionNodes() {
        String source = """
                class Sample {
                    int nested(boolean a, boolean b, int[] values) {
                        for (int i = 0; i < values.length; i++) {
                            if (a) {
                            }
                        }
                        for (int value : values) {
                            if (b) {
                            }
                        }
                        while (a) {
                            if (b) {
                            }
                            a = false;
                        }
                        do {
                            if (a) {
                            }
                            b = false;
                        } while (b);
                        try {
                            return a ? (b ? 1 : 0) : 2;
                        } catch (RuntimeException ex) {
                            if (values.length > 0) {
                                return values[0];
                            }
                            return 3;
                        }
                    }

                    int switched(int value) {
                        switch (value) {
                            case 1:
                                if (value > 0) {
                                    return 1;
                                }
                                return 0;
                            default:
                                return 2;
                        }
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("Sample", source);

        assertEquals(List.of(
                new MethodDescriptor("nested", 2, 29, 13, new MethodIdentity("Sample", "nested", "(ZZ[I)I")),
                new MethodDescriptor("switched", 31, 41, 4, new MethodIdentity("Sample", "switched", "(I)I"))
        ), methods);
    }

    @Test
    void acceptsClassNamesWithJavaSuffix() {
        String source = """
                class Sample {
                    int value() {
                        return 1;
                    }
                }
                """;

        List<MethodDescriptor> methods = JavaMethodParser.parse("demo.Sample.java", source);

        assertEquals(List.of(new MethodDescriptor("value", 2, 4, 1, new MethodIdentity("Sample", "value", "()I"))), methods);
    }

    @Test
    void buildsSourcePathAndUriFromClassNames() {
        assertEquals("demo/Sample.java", JavaMethodParser.sourcePath("demo.Sample"));
        assertEquals("demo/Sample.java", JavaMethodParser.sourcePath("demo.Sample.java"));
        assertEquals(URI.create("string:///demo/Sample.java"), JavaMethodParser.sourceUri("demo.Sample"));
        assertEquals(URI.create("string:///demo/Sample.java"), JavaMethodParser.sourceUri("demo.Sample.java"));
    }

    @Test
    void buildsErasedDescriptorsForPrimitivesArraysVarargsAndBoundedGenerics() {
        String source = """
                package demo;
                import java.util.List;
                class Sample<T extends Number> {
                    @Deprecated
                    void primitives(boolean z, byte b, short s, int i, long j, char c,
                                    float f, double d, String[][] names, int... values) { }
                    <U extends CharSequence> T bounded(U[] values, List<? extends T> items) {
                        return null;
                    }
                    T unbounded(List<Missing> items) { return null; }
                }
                """;

        var methods = JavaMethodParser.parse("demo.Sample", source);
        assertEquals(List.of(
                new MethodIdentity("demo.Sample", "primitives", "(ZBSIJCFD[[Ljava/lang/String;[I)V"),
                new MethodIdentity("demo.Sample", "bounded", "([Ljava/lang/CharSequence;Ljava/util/List;)Ljava/lang/Number;"),
                new MethodIdentity("demo.Sample", "unbounded", "(Ljava/util/List;)Ljava/lang/Number;")
        ), methods.stream().map(MethodDescriptor::identity).toList());
    }

    @Test
    void usesBinaryNamesForNestedOwnersAndNestedParameterTypes() {
        String source = """
                package demo;
                import java.util.Map.Entry;
                class Sample {
                    static class Nested {
                        Nested[] convert(Entry<String, String> entry) { return null; }
                    }
                    Sample convert(Nested value) { return this; }
                }
                """;

        var methods = JavaMethodParser.parse("demo.Sample", source);
        assertEquals(List.of(
                new MethodIdentity("demo.Sample$Nested", "convert", "(Ljava/util/Map$Entry;)[Ldemo/Sample$Nested;"),
                new MethodIdentity("demo.Sample", "convert", "(Ldemo/Sample$Nested;)Ldemo/Sample;")
        ), methods.stream().map(MethodDescriptor::identity).toList());
    }

    @Test
    void retainsMethodsButDoesNotInventDescriptorsForUnresolvedOrAmbiguousTypes() {
        String source = """
                import java.util.*;
                import java.sql.*;
                class Sample {
                    int unresolved(Missing[] value) { return 1; }
                    Date ambiguous() { return null; }
                    <T extends Missing> T bound(T value) { return value; }
                    int bodyError() { return unknown(); }
                }
                """;

        var methods = JavaMethodParser.parse("Sample", source);
        assertEquals(4, methods.size());
        for (int i = 0; i < 3; i++) {
            assertNull(methods.get(i).identity().descriptor());
            assertEquals(1, methods.get(i).complexity());
        }
        assertEquals("()I", methods.get(3).identity().descriptor());
    }
}
