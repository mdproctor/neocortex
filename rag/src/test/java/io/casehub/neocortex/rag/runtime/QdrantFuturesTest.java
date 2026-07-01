package io.casehub.neocortex.rag.runtime;

import com.google.common.util.concurrent.SettableFuture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantFuturesTest {

    @Test
    void successPropagatesItemToUni() {
        SettableFuture<String> future = SettableFuture.create();
        var uni = QdrantFutures.<String>toUni(future);

        future.set("hello");

        String result = uni.await().indefinitely();
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void failurePropagatesExceptionToUni() {
        SettableFuture<String> future = SettableFuture.create();
        var uni = QdrantFutures.<String>toUni(future);

        var cause = new RuntimeException("boom");
        future.setException(cause);

        assertThatThrownBy(() -> uni.await().indefinitely())
            .isInstanceOf(RuntimeException.class)
            .hasMessage("boom");
    }

    @Test
    void cancellingUniCancelsListenableFuture() {
        SettableFuture<String> future = SettableFuture.create();
        var uni = QdrantFutures.<String>toUni(future);

        var cancellable = uni.subscribe().with(item -> {}, failure -> {});
        cancellable.cancel();

        assertThat(future.isCancelled()).isTrue();
    }

    @Test
    void alreadyCompletedFutureDeliversImmediately() {
        SettableFuture<Integer> future = SettableFuture.create();
        future.set(42);

        Integer result = QdrantFutures.<Integer>toUni(future).await().indefinitely();
        assertThat(result).isEqualTo(42);
    }
}
