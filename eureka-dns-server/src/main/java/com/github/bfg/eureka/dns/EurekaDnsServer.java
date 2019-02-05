package com.github.bfg.eureka.dns;

import com.google.common.net.InetAddresses;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultithreadEventLoopGroup;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.bfg.eureka.dns.Utils.allFutures;
import static com.github.bfg.eureka.dns.Utils.toCompletableFuture;

/**
 * Eureka DNS server.
 */
@Slf4j
public final class EurekaDnsServer implements Closeable {
    /**
     * Native event loop class -> channel class name mapping.
     */
    private static final Map<String, String> NATIVE_ELG_CLASS_NAME_MAPPING = createChannelClassStringMapping();

    /**
     * Native EventLoopGroup class -> channel class mapping.
     */
    private static final Map<Class<? extends EventLoopGroup>, Class<? extends DatagramChannel>> NATIVE_ELG_CLASS_MAPPING
            = createChannelClassMapping();

    private final CompletableFuture<EurekaDnsServer> completedFuture = CompletableFuture.completedFuture(this);
    private final CompletableFuture<EurekaDnsServer> startupFuture = new CompletableFuture<>();
    private final CompletableFuture<EurekaDnsServer> shutdownFuture = new CompletableFuture<>();

    private final DnsServerConfig config;
    private final EventLoopGroup eventLoopGroup;
    private final boolean shutdownElg;
    private final DnsQueryHandler dnsQueryHandler;
    private final AtomicBoolean wasStarted = new AtomicBoolean();
    private final AtomicBoolean wasStopped = new AtomicBoolean();

    /**
     * List of bound UDP channels, volatile because it can't be created on instance instantiation.
     */
    private volatile List<Channel> channels;

    /**
     * Creates new instance.
     *
     * @param config server configuration.
     */
    EurekaDnsServer(@NonNull DnsServerConfig config) {
        this(config, new DnsQueryHandler(config));
    }

    /**
     * Creates new instance.
     *
     * @param config          server configuration.
     * @param dnsQueryHandler dns query handler
     */
    EurekaDnsServer(@NonNull DnsServerConfig config, @NonNull DnsQueryHandler dnsQueryHandler) {
        this.config = config.validate();
        this.eventLoopGroup = getOrCreateEventLoopGroup(config);
        this.shutdownElg = (config.getEventLoopGroup() == null);
        this.dnsQueryHandler = dnsQueryHandler;
    }

    /**
     * Retrieves event loop group from configuration or creates new one.
     *
     * @param config config
     * @return event loop group
     */
    private EventLoopGroup getOrCreateEventLoopGroup(@NonNull DnsServerConfig config) {
        return Optional.ofNullable(config.getEventLoopGroup())
                .orElseGet(() -> createEventLoopGroup(config));
    }

    /**
     * Creates event loop group according to configuration.
     *
     * @param config config
     * @return event loop group
     */
    private EventLoopGroup createEventLoopGroup(@NonNull DnsServerConfig config) {
        val numThreads = getNumThreads(config);
        val elg = (config.isPreferNativeTransport()) ?
                createNativeEventLoopGroup(numThreads) : createGenericEventLoopGroup(numThreads);
        log.info("created new event loop group (workers: {}): {}", numThreads, elg);
        return elg;
    }

    /**
     * Creates generic NIO event loop group.
     *
     * @param numThreads number of worker threads.
     * @return event loop group
     */
    private EventLoopGroup createGenericEventLoopGroup(int numThreads) {
        return new NioEventLoopGroup(numThreads);
    }

    /**
     * Creates generic native event loop group.
     *
     * @param numThreads number of worker threads.
     * @return event loop group, may return generic event loop group if native initialization failed.
     */
    private EventLoopGroup createNativeEventLoopGroup(int numThreads) {
        return NATIVE_ELG_CLASS_MAPPING.keySet().stream()
                .map(clazz -> initEventLoopGroup(clazz, numThreads))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("error initializing native event loop group, fallling back to nio event loop group.");
                    return createGenericEventLoopGroup(numThreads);
                });
    }

    private Optional<EventLoopGroup> initEventLoopGroup(Class<? extends EventLoopGroup> clazz, int numThreads) {
        try {
            val constructor = clazz.getConstructor(int.class);
            return Optional.of(constructor.newInstance(numThreads));
        } catch (Exception e) {
            log.debug("exception initializing event loop group {}: {}", clazz.getName(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private int getNumThreads(@NonNull DnsServerConfig config) {
        val maxThreads = config.getMaxThreads();
        return (maxThreads < 1) ? Runtime.getRuntime().availableProcessors() : maxThreads;
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
     * @throws IllegalStateException if this method is invoked more than once on the same instance.
     */
    public CompletionStage<EurekaDnsServer> start() {
        if (!wasStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("Eureka DNS server start attempt was already done.");
        }

        try {
            return doStart();
        } catch (Exception e) {
            startupFuture.completeExceptionally(e);
            return startupFuture;
        }
    }

    private CompletionStage<EurekaDnsServer> doStart() {
        log.info("starting eureka DNS server");

        val bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(getChannelClass(eventLoopGroup))
                .handler(createChannelHandler())
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_REUSEADDR, true);

        // SO_REUSEPORT improves performance because multiple event loops are tied to different CPU cores,
        // but it requires native transport
        //
        // make sure that you check bindAddress()
        if (isNativeTransport(eventLoopGroup)) {
            Optional.ofNullable(ChannelOption.valueOf("io.netty.channel.unix.UnixChannelOption#SO_REUSEPORT"))
                    .ifPresent(option -> {
                        log.debug("setting channel option on a bootstrap: {}", option);
                        bootstrap.option(option, true);
                    });
        }

        // validate bootstrap early.
        bootstrap.validate();

        // bind all listening addresses
        val boundChannelFutures = getListeningAddresses().stream()
                .flatMap(addr -> bindAddress(bootstrap, addr))
                .collect(Collectors.toList());

        allFutures(boundChannelFutures)
                .thenRun(() -> {
                    channels = toChannels(boundChannelFutures);
                    logBoundChannels(channels);
                    log.info("started eureka DNS server [{} channel(s)]", boundChannelFutures.size());
                    startupFuture.complete(this);
                })
                .exceptionally(t -> {
                    startupFuture.completeExceptionally(t);
                    shutdownFuture.completeExceptionally(t);
                    return null;
                });

        return startupFuture;
    }

    /**
     * Logs bound channels.
     *
     * @param channels channels
     */
    private void logBoundChannels(List<Channel> channels) {
        channels.stream()
                .filter(e -> e instanceof DatagramChannel)
                .map(e -> (DatagramChannel) e)
                .map(DatagramChannel::localAddress)
                .distinct()
                .forEach(e -> log.info("listening on {}", e));
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

    /**
     * Binds given address on a bootstrap. This method binds given address as many times as there are worker threads in
     * event loop group if it's using native transport which leads to per-cpu to almost linear performance scaling.
     *
     * @param bootstrap bootstrap
     * @param addr      address to bind
     * @return stream of futures of bound channels.
     * @see #isNativeTransport(EventLoopGroup)
     * @see #getEventLoopGroupThreads(EventLoopGroup)
     */
    private Stream<CompletableFuture<Channel>> bindAddress(Bootstrap bootstrap, InetSocketAddress addr) {
        val numTimes = isNativeTransport(eventLoopGroup) ? getEventLoopGroupThreads(eventLoopGroup) : 1;
        log.debug("binding address ({} times) [{}]:{}", numTimes, addr.getAddress(), addr.getPort());

        return IntStream.range(0, numTimes)
                .mapToObj(idx -> toCompletableFuture(bootstrap.bind(addr.getAddress(), addr.getPort())));
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
     * @throws IllegalStateException if server is not running or has been already stopped.
     */
    public CompletionStage<EurekaDnsServer> stop() {
        if (!isRunning()) {
            throw new IllegalStateException("Eureka DNS server is not running.");
        }
        if (!wasStopped.compareAndSet(false, true)) {
            throw new IllegalStateException("Instance can't be stopped more than once.");
        }

        log.info("stopping eureka DNS server.");
        val result = new CompletableFuture<EurekaDnsServer>();

        val closeFutures = channels.stream()
                .map(this::closeChannel)
                .collect(Collectors.toList());

        allFutures(closeFutures)
                .thenCompose(e -> shutdownEvenLoopGroup())
                .thenRun(() -> {
                    log.info("eureka DNS server stopped.");
                    shutdownFuture.complete(this);
                    result.complete(this);
                })
                .exceptionally(t -> {
                    shutdownFuture.completeExceptionally(t);
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
        return startupFuture.isDone() &&
                !startupFuture.isCancelled() &&
                !startupFuture.isCompletedExceptionally() &&
                !shutdownFuture.isDone();
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
        start().thenCompose(e -> shutdownFuture)
                .toCompletableFuture()
                .get();
    }

    private ChannelInitializer<DatagramChannel> createChannelHandler() {
        val me = this;
        return new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(DatagramChannel ch) {
                ch.pipeline()
                        .addLast(new DatagramDnsQueryDecoder())
                        .addLast(new DatagramDnsResponseEncoder())
                        .addLast(me.dnsQueryHandler);
                log.debug("initialized netty channel: {}", ch);
            }
        };
    }

    /**
     * Returns channel class for given event loop group.
     *
     * @param elg event loop group
     * @return channel class
     * @throws IllegalArgumentException if channel class cannot be obtained.
     */
    private Class<? extends Channel> getChannelClass(EventLoopGroup elg) {
        if (elg instanceof NioEventLoopGroup) {
            return NioDatagramChannel.class;
        }

        return Optional.ofNullable(NATIVE_ELG_CLASS_MAPPING.get(elg.getClass()))
                .orElseThrow(() ->
                        new IllegalArgumentException("Unknown event loop group type: " + elg.getClass().getName()));
    }

    /**
     * Returns number of worker threads in a given event loop group.
     *
     * @param elg event loop group
     * @return number of worker threads
     */
    private int getEventLoopGroupThreads(@NonNull EventLoopGroup elg) {
        if (elg instanceof MultithreadEventLoopGroup) {
            return ((MultithreadEventLoopGroup) elg).executorCount();
        }
        return 1;
    }

    /**
     * Tells whether given event loop group uses native transport
     *
     * @param elg event loop group
     * @return true/false
     */
    private boolean isNativeTransport(@NonNull EventLoopGroup elg) {
        return !(elg instanceof NioEventLoopGroup);
    }

    @SneakyThrows
    private CompletableFuture<EurekaDnsServer> shutdownEvenLoopGroup() {
        if (shutdownElg) {
            log.debug("shutting down event loop group: {}", eventLoopGroup);
            return toCompletableFuture(eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS))
                    .thenCompose(e -> completedFuture);
        }
        return completedFuture;
    }

    private static Map<String, String> createChannelClassStringMapping() {
        val map = new LinkedHashMap<String, String>();
        map.put("io.netty.channel.epoll.EpollEventLoopGroup", "io.netty.channel.epoll.EpollDatagramChannel");
        map.put("io.netty.channel.kqueue.KQueueEventLoopGroup", "io.netty.channel.kqueue.KQueueDatagramChannel");
        return Collections.unmodifiableMap(map);
    }

    private static Map<Class<? extends EventLoopGroup>, Class<? extends DatagramChannel>> createChannelClassMapping() {
        val map = new LinkedHashMap<Class<? extends EventLoopGroup>, Class<? extends DatagramChannel>>();
        NATIVE_ELG_CLASS_NAME_MAPPING.forEach((elgName, chName) -> {
            final Optional<Class<EventLoopGroup>> eventLoopGroupClassOpt = loadClass(elgName);
            final Optional<Class<DatagramChannel>> channelClassOpt = loadClass(chName);
            if (eventLoopGroupClassOpt.isPresent() && channelClassOpt.isPresent()) {
                map.put(eventLoopGroupClassOpt.get(), channelClassOpt.get());
            }
        });
        return Collections.unmodifiableMap(map);
    }

    /**
     * Loads some class.
     *
     * @param fqcn java fqcn
     * @return optional of loaded class.
     */
    @SuppressWarnings("unchecked")
    private static <T> Optional<Class<T>> loadClass(@NonNull String fqcn) {
        try {
            return Optional.ofNullable((Class<T>) Class.forName(fqcn));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }
}
