package io.casehub.neocortex.memory.cbr;

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
        CbrCaseMemoryStore.class, ReactiveCbrCaseMemoryStore.class
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
                .filter(m -> !m.isDefault())
                .collect(Collectors.toMap(Method::getName, m -> m));
            for (Method bm : blocking.getDeclaredMethods()) {
                if (bm.isDefault()) continue;
                Method rm = reactiveMethods.get(bm.getName());
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
                Class<?> expectedWrapped = bm.getReturnType() == void.class
                    ? Void.class : bm.getReturnType();
                if (expectedWrapped == Void.class) {
                    assertThat(uniTypeArg).isEqualTo(Void.class);
                } else {
                    assertThat(uniTypeArg.getTypeName())
                        .as("Uni<%s> expected for %s.%s",
                            bm.getGenericReturnType().getTypeName(),
                            reactive.getSimpleName(), rm.getName())
                        .isEqualTo(bm.getGenericReturnType().getTypeName());
                }
            }
        }
    }

    @Test
    void everyReactiveMethodHasBlockingEquivalent() {
        for (var entry : PAIRS.entrySet()) {
            Class<?> blocking = entry.getKey();
            Class<?> reactive = entry.getValue();
            Map<String, Method> blockingMethods = Arrays.stream(blocking.getDeclaredMethods())
                .filter(m -> !m.isDefault())
                .collect(Collectors.toMap(Method::getName, m -> m));
            for (Method rm : reactive.getDeclaredMethods()) {
                if (rm.isDefault()) continue;
                assertThat(blockingMethods.get(rm.getName()))
                    .as("Blocking counterpart of %s.%s", reactive.getSimpleName(), rm.getName())
                    .isNotNull();
            }
        }
    }
}
