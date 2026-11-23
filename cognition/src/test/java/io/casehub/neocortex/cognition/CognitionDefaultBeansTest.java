package io.casehub.neocortex.cognition;

import io.casehub.neocortex.cognition.appraisal.SalienceStrategy;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.casehub.neocortex.cognition.salience.EntityAwareSalienceStrategy;

class CognitionDefaultBeansTest {

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> resolvable() {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(null);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> resolvable(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> unresolvable() {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    @Test
    void salienceStrategyWrapsWithEntityAwareWhenMindMapStoreAvailable() {
        var              beans    = new CognitionDefaultBeans();
        SalienceStrategy strategy = beans.salienceStrategy(resolvable());
        assertThat(strategy).isInstanceOf(EntityAwareSalienceStrategy.class);
    }

    @Test
    void salienceStrategyWrapsNoOpWhenMindMapStoreUnavailable() {
        var              beans    = new CognitionDefaultBeans();
        SalienceStrategy strategy = beans.salienceStrategy(unresolvable());
        assertThat(strategy).isInstanceOf(EntityAwareSalienceStrategy.class);
    }
}
