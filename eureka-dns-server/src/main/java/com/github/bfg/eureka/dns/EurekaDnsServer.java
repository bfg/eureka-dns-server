package com.github.bfg.eureka.dns;

import com.google.common.net.InetAddresses;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import javax.annotation.PreDestroy;
import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.bfg.eureka.dns.Utils.*;

/**
 * Eureka DNS server.
 */
@Slf4j
public final class EurekaDnsServer implements Closeable {
    private final DnsServerConfig config;
    private final EventLoopGroup elg;
    private final boolean shutdownElg;
    private final DnsQueryHandler handler;
    private final CompletableFuture<EurekaDnsServer> completedFuture = CompletableFuture.completedFuture(this);

    private volatile CompletableFuture<EurekaDnsServer> shutdownFuture = new CompletableFuture<>();
    private volatile List<Channel> channels;

    /**
     * Creates new instance.
     *
     * @param config server configuration.
     */
    public EurekaDnsServer(@NonNull DnsServerConfig config) {
        this.config = config.validate();
        this.elg = createEventLoopGroup(config.getEventLoopGroup());
        this.shutdownElg = (config.getEventLoopGroup() == null);
        this.handler = new DnsQueryHandler(this.config);
    }

    private EventLoopGroup createEventLoopGroup(EventLoopGroup elg) {
        return (elg == null) ? new NioEventLoopGroup(1) : elg;
    }

    /**
     * Creates new config builder.
     *
     * @return config builder.
     */
    public static DnsServerConfig builder() {
        return new DnsServerConfig();
    }

    /**
     * Starts eureka dns server.
     *
     * @return completion stage that is completed when all addresses are bound.
     */
    public CompletionStage<EurekaDnsServer> start() {
        checkRunning(false);
        try {
            return doStart();
        } catch (Exception e) {
            return failedFuture(e);
        }
    }

    private CompletionStage<EurekaDnsServer> doStart() {
        log.info("starting eureka DNS server");

        val result = new CompletableFuture<EurekaDnsServer>();
        val bootstrap = new Bootstrap()
                .group(this.elg)
                .channel(getChannelClass(elg))
                .handler(createChannelHandler())
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_REUSEADDR, true)
                .validate();

        final List<CompletableFuture<Channel>> channelFutures = getListeningAddresses().stream()
                .map(addr -> bindAddress(bootstrap, addr))
                .collect(Collectors.toList());

        // start listening
        allFutures(channelFutures)
                .thenRun(() -> {
                    log.info("started eureka DNS server [{} listening socket(s)]", channelFutures.size());
                    this.channels = toChannels(channelFutures);
                    result.complete(this);
                })
                .exceptionally(t -> {
                    result.completeExceptionally(t);
                    return null;
                });

        return result;
    }

    /**
     * Collects channels from futures.
     *
     * @param futures channel futures
     * @return list of channels
     */
    private List<Channel> toChannels(List<CompletableFuture<Channel>> futures) {
        return futures.stream()
                .map(e -> e.getNow(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private CompletableFuture<Channel> bindAddress(Bootstrap bootstrap, InetSocketAddress addr) {
        log.debug("binding address [{}]:{}", addr.getAddress(), addr.getPort());
        return toCompletableFuture(bootstrap.bind(addr.getAddress(), addr.getPort()))
                .thenApply(ch -> {
                    log.info("listening on [{}]:{}", addr.getAddress().getHostAddress(), addr.getPort());
                    return ch;
                });
    }

    private List<InetSocketAddress> getListeningAddresses() {
        val fromConfig = config.getAddresses().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        if (fromConfig.isEmpty()) {
            return discoverListeningAddresses().stream()
                    .map(addr -> new InetSocketAddress(addr, config.getPort()))
                    .collect(Collectors.toList());
        } else {
            return fromConfig.stream()
                    .map(InetAddresses::forString)
                    .map(e -> new InetSocketAddress(e, config.getPort()))
                    .collect(Collectors.toList());
        }
    }

    @SneakyThrows
    private List<InetAddress> discoverListeningAddresses() {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
                .stream()
                .filter(this::isValidInterface)
                .flatMap(this::getInterfaceAddresses)
                .collect(Collectors.toList());
    }

    private Stream<InetAddress> getInterfaceAddresses(@NonNull NetworkInterface iface) {
        return Collections.list(iface.getInetAddresses())
                .stream()
                .filter(e -> !e.isLinkLocalAddress())
                .filter(e -> !e.isMulticastAddress())
                .filter(e -> !e.isAnyLocalAddress());
    }

    @SneakyThrows
    private boolean isValidInterface(@NonNull NetworkInterface iface) {
        return iface.isUp();
    }

    /**
     * Shuts down the server.
     *
     * @return completion stage which is completed when all listeners are shut down.
     */
    public CompletionStage<EurekaDnsServer> stop() {
        checkRunning(true);
        checkHasBeenShutdown();

        log.info("stopping eureka DNS server.");
        val result = new CompletableFuture<EurekaDnsServer>();

        val closeFutures = channels.stream()
                .map(this::closeChannel)
                .collect(Collectors.toList());

        allFutures(closeFutures)
                .thenCompose(e -> shutdownEvenLoopGroup())
                .thenRun(() -> {
                    log.info("eureka DNS server stopped.");
                    shutdownFuture().complete(this);
                    result.complete(this);
                })
                .exceptionally(t -> {
                    shutdownFuture().completeExceptionally(t);
                    result.completeExceptionally(t);
                    return null;
                })
                .whenComplete((x, y) -> this.channels.clear());
        return result;
    }

    @Override
    @PreDestroy
    @SneakyThrows
    public void close() {
        stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Tells whether server is running;
     *
     * @return true/false
     */
    private boolean isRunning() {
        val channels = this.channels;
        return (channels != null && !channels.isEmpty());
    }

    /**
     * Checks whether server is running or not.
     *
     * @param mustBeRunning flag to check against whether server is running.
     * @throws IllegalStateException if {@code mustBeStarted == true} and the server is not running and in opposite
     *                               case.
     */
    private void checkRunning(boolean mustBeRunning) {
        val isRunning = isRunning();
        if (mustBeRunning && !isRunning) {
            throw new IllegalStateException("Eureka DNS server is not running.");
        } else if (!mustBeRunning && isRunning) {
            throw new IllegalStateException("Eureka DNS server is running.");
        }
    }

    /**
     * Checks whether server is currently in shutdown procedure.
     */
    private void checkHasBeenShutdown() {
        if (shutdownFuture.isDone()) {
            throw new IllegalStateException("Server has already been shut down.");
        }
    }

    private CompletableFuture<Channel> closeChannel(@NonNull Channel channel) {
        log.debug("closing channel: {}", channel);
        return toCompletableFuture(channel.close());
    }

    /**
     * Starts the server and block until it's shut down.
     *
     * @throws RuntimeException if server cannot be started.
     */
    @SneakyThrows
    public void run() {
        start().thenCompose(EurekaDnsServer::shutdownFuture)
                .toCompletableFuture()
                .get();
    }

    private CompletableFuture<EurekaDnsServer> shutdownFuture() {
        return this.shutdownFuture;
    }

    private ChannelInitializer<DatagramChannel> createChannelHandler() {
        val me = this;
        return new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(DatagramChannel ch) {
                ch.pipeline()
                        .addLast(new DatagramDnsQueryDecoder())
                        .addLast(new DatagramDnsResponseEncoder())
                        .addLast(me.handler);
                log.debug("initialized netty channel: {}", ch);
            }
        };
    }

    private Class<? extends Channel> getChannelClass(EventLoopGroup elg) {
        if (elg instanceof NioEventLoopGroup) {
            return NioDatagramChannel.class;
        }
        // this is so ugly...
        if (elg.getClass().getName().toLowerCase().contains("epoll")) {
            return initChannelClass("io.netty.channel.epoll.EpollDatagramChannel");
        }
        throw new IllegalArgumentException("Unknown event loop group type: " + elg.getClass().getName());
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    private Class<? extends Channel> initChannelClass(String fqcn) {
        return ((Class<? extends Channel>) Class.forName(fqcn));
    }

    @SneakyThrows
    private CompletableFuture<EurekaDnsServer> shutdownEvenLoopGroup() {
        if (shutdownElg) {
            log.debug("shutting down event loop group: {}", elg);
            return toCompletableFuture(elg.shutdownGracefully(0, 1, TimeUnit.SECONDS))
                    .thenCompose(e -> completedFuture);
        }
        return completedFuture;
    }
}
