package experiment.fixtures;

import java.io.IOException;
import java.io.StringReader;
import java.util.function.IntSupplier;

/** Deterministic source constructs used by the bytecode-parity experiment. */
public class FixtureMatrix {
    static int straightLine() {
        return 7;
    }

    static int ifElse(boolean value) {
        if (value) {
            return 1;
        }
        return 0;
    }

    static int loop(int count) {
        int total = 0;
        for (int index = 0; index < count; index++) {
            total += index;
        }
        while (total < 0) {
            total++;
        }
        return total;
    }

    static int shortCircuit(boolean left, boolean right) {
        return left && right || !left ? 1 : 0;
    }

    static int switchCases(int value) {
        return switch (value) {
            case 1 -> 10;
            case 2 -> 20;
            case 3 -> 30;
            default -> 0;
        };
    }

    static int switchWithoutDefault(int value) {
        switch (value) {
            case 1:
                return 10;
            case 2:
                return 20;
        }
        return 0;
    }

    static int plainTryCatch(boolean fail) {
        try {
            if (fail) {
                throw new IllegalStateException("fixture");
            }
            return 1;
        } catch (IllegalStateException expected) {
            return 0;
        }
    }

    static int multiCatch(int kind) {
        try {
            if (kind == 1) {
                throw new IllegalArgumentException("one");
            }
            if (kind == 2) {
                throw new IllegalStateException("two");
            }
            return kind;
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return -1;
        }
    }

    static int multipleCatchClauses(int kind) {
        try {
            if (kind == 1) {
                throw new IllegalArgumentException("one");
            }
            if (kind == 2) {
                throw new IndexOutOfBoundsException("two");
            }
            return kind;
        } catch (IllegalArgumentException expected) {
            return -1;
        } catch (IndexOutOfBoundsException expected) {
            return -2;
        }
    }

    static int nestedCatch(boolean fail) {
        try {
            try {
                if (fail) {
                    throw new IllegalArgumentException("inner");
                }
            } catch (IllegalArgumentException expected) {
                return 1;
            }
        } catch (RuntimeException unexpected) {
            return -1;
        }
        return 0;
    }

    static int tryFinally(boolean fail) {
        int result = 0;
        try {
            if (fail) {
                throw new IllegalArgumentException("finally");
            }
            result = 4;
        } finally {
            result++;
        }
        return result;
    }

    static int tryWithResources() {
        try (StringReader reader = new StringReader("x")) {
            return reader.read();
        } catch (IOException unexpected) {
            return -1;
        }
    }

    static int synchronizedBlock(Object monitor) {
        synchronized (monitor) {
            return 5;
        }
    }

    static synchronized int synchronizedMethod() {
        return 6;
    }

    static int overloaded() {
        return 1;
    }

    static int overloaded(int value) {
        return value;
    }

    static IntSupplier lambdaMethod() {
        return () -> 42;
    }

    static class Nested {
        int nestedMethod(boolean value) {
            if (value) {
                return 9;
            }
            return 8;
        }
    }

    static class GenericBase<T> {
        T transform(T value) {
            return value;
        }
    }

    static class StringTransformer extends GenericBase<String> {
        @Override
        String transform(String value) {
            return value;
        }
    }

    public static void main(String[] args) {
        straightLine();
        ifElse(true);
        ifElse(false);
        loop(4);
        shortCircuit(true, true);
        shortCircuit(false, true);
        switchCases(1);
        switchCases(8);
        switchWithoutDefault(1);
        switchWithoutDefault(8);
        plainTryCatch(false);
        plainTryCatch(true);
        multiCatch(0);
        multiCatch(1);
        multiCatch(2);
        multipleCatchClauses(0);
        multipleCatchClauses(1);
        multipleCatchClauses(2);
        nestedCatch(false);
        nestedCatch(true);
        tryFinally(false);
        tryWithResources();
        synchronizedBlock(new Object());
        synchronizedMethod();
        overloaded();
        overloaded(2);
        new Nested().nestedMethod(true);
        new StringTransformer().transform("value");
        lambdaMethod().getAsInt();
    }
}
