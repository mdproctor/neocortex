package io.casehub.neocortex.corpus;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BlockingReactiveParityTest {

    private static final Map<Class<?>, Class<?>> PAIRS = Map.of(
        CorpusStore.class, ReactiveCorpusStore.class,
        CorpusReader.class, ReactiveCorpusReader.class,
        ChangeSource.class, ReactiveChangeSource.class
    );

    @Test
    void vacuousPassGuard() {
        assertThat(PAIRS).isNotEmpty();
    }

    @Test
    void everyBlockingMethodHasReactiveEquivalent() {
        for (var entry : PAIRS.entrySet()) {
            Class<?> blocking = entry.getKey();
            Class<?> reactive = entry.getValue();
            Map<String, Method> reactiveMethods = Arrays.stream(reactive.getDeclaredMethods())
                .collect(Collectors.toMap(m -> methodSignature(m), m -> m));

            for (Method bm : blocking.getDeclaredMethods()) {
                Method rm = reactiveMethods.get(methodSignature(bm));
                assertThat(rm)
                    .as("Reactive mirror of %s.%s", blocking.getSimpleName(), bm.getName())
                    .isNotNull();

                assertThat(rm.getReturnType())
                    .as("Return type of %s.%s must be Uni", reactive.getSimpleName(), rm.getName())
                    .isEqualTo(Uni.class);

                assertThat(rm.getParameterTypes())
                    .as("Parameters of %s.%s must match %s.%s",
                        reactive.getSimpleName(), rm.getName(),
                        blocking.getSimpleName(), bm.getName())
                    .isEqualTo(bm.getParameterTypes());

                Type uniTypeArg = ((ParameterizedType) rm.getGenericReturnType())
                    .getActualTypeArguments()[0];

                Type expectedType = bm.getGenericReturnType();
                if (bm.getReturnType().isPrimitive()) {
                    expectedType = boxIfPrimitive(bm.getReturnType());
                }

                String expectedTypeName = expectedType.getTypeName();
                String actualTypeName = uniTypeArg.getTypeName();

                assertThat(actualTypeName)
                    .as("Uni<%s> expected for %s.%s",
                        expectedTypeName,
                        reactive.getSimpleName(), rm.getName())
                    .isEqualTo(expectedTypeName);
            }
        }
    }

    @Test
    void everyReactiveMethodHasBlockingEquivalent() {
        for (var entry : PAIRS.entrySet()) {
            Class<?> blocking = entry.getKey();
            Class<?> reactive = entry.getValue();
            Map<String, Method> blockingMethods = Arrays.stream(blocking.getDeclaredMethods())
                .collect(Collectors.toMap(m -> methodSignature(m), m -> m));

            for (Method rm : reactive.getDeclaredMethods()) {
                if (rm.isDefault()) continue;
                assertThat(blockingMethods.get(methodSignature(rm)))
                    .as("Blocking counterpart of %s.%s", reactive.getSimpleName(), rm.getName())
                    .isNotNull();
            }
        }
    }

    private static String methodSignature(Method m) {
        return m.getName() + Arrays.toString(m.getParameterTypes());
    }

    private static Class<?> boxIfPrimitive(Class<?> type) {
        if (type == void.class) return Void.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }
}
