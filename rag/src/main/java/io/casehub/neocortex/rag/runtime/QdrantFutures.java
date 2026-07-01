package io.casehub.neocortex.rag.runtime;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.smallrye.mutiny.Uni;

final class QdrantFutures {

    private QdrantFutures() {}

    static <T> Uni<T> toUni(ListenableFuture<T> future) {
        return Uni.createFrom().emitter(em -> {
            em.onTermination(() -> future.cancel(false));
            Futures.addCallback(future, new FutureCallback<>() {
                @Override
                public void onSuccess(T result) {
                    em.complete(result);
                }

                @Override
                public void onFailure(Throwable t) {
                    em.fail(t);
                }
            }, MoreExecutors.directExecutor());
        });
    }
}
