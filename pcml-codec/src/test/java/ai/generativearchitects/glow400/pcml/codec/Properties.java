package ai.generativearchitects.glow400.pcml.codec;

import java.util.function.Consumer;

/**
 * Runs a property as an ordinary JUnit test: a fixed number of cases (500, or {@code -Dglow400.cases=n}), each with its
 * own {@link Gen}. The seed is {@code -Dglow400.seed=n} when given and random otherwise. When a case fails, the error
 * names the seed and the case index, which is all it takes to reproduce the run.
 */
final class Properties {

    static final int DEFAULT_CASES = 500;
    static final String SEED_PROPERTY = "glow400.seed";
    static final String CASES_PROPERTY = "glow400.cases";

    private Properties() {
    }

    static void check(String name, Consumer<Gen> property) {
        check(name, Long.getLong(SEED_PROPERTY, new java.util.Random().nextLong()), Integer.getInteger(CASES_PROPERTY, DEFAULT_CASES), property);
    }

    static void check(String name, long seed, int cases, Consumer<Gen> property) {
        for (int i = 0; i < cases; i++) {
            try {
                property.accept(new Gen(seed, i));
            } catch (Throwable failure) {
                throw new AssertionError("Property '" + name + "' failed with seed " + seed + " at case " + i
                        + " (run again with -D" + SEED_PROPERTY + "=" + seed + "): " + failure.getMessage(), failure);
            }
        }
    }
}
