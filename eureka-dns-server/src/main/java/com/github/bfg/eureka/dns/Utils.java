package com.github.bfg.eureka.dns;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.val;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@UtilityClass
class Utils {
    /**
     * Creates failed future.
     *
     * @param exception exception to fail future with.
     * @param <T>       future type
     * @return completable future
     */
    static <T> CompletableFuture<T> failedFuture(@NonNull Throwable exception) {
        val future = new CompletableFuture<T>();
        future.completeExceptionally(exception);
        return future;
    }

    /**
     * Converts {@link ChannelFuture} to completable future.
     *
     * @param future channel future
     * @return completable future.
     */
    static CompletableFuture<Channel> toCompletableFuture(@NonNull ChannelFuture future) {
        val res = new CompletableFuture<Channel>();
        future.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                res.complete(f.channel());
            } else {
                res.completeExceptionally(f.cause());
            }
        });
        return res;
    }

    /**
     * Converts generic netty {@link Future} to completable future.
     *
     * @param future netty future
     * @return completable future
     */
    @SuppressWarnings("unchecked")
    static <V> CompletableFuture<V> toCompletableFuture(@NonNull Future<V> future) {
        val result = new CompletableFuture<V>();
        future.addListener(f -> {
            if (f.isSuccess()) {
                result.complete((V) f.getNow());
            } else {
                result.completeExceptionally(f.cause());
            }
        });
        return result;
    }

    /**
     * Combines multiple completable futures together.
     *
     * @see CompletableFuture#allOf(CompletableFuture[])
     */
    static <T extends Object> CompletableFuture<Void> allFutures(List<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
