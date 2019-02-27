package com.github.bfg.eureka.dns;

import com.netflix.discovery.EurekaClient;
import io.netty.channel.EventLoopGroup;
import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link EurekaDnsServer} configuration.
 */
@Data
@Accessors(chain = true)
@ConfigurationProperties("eureka.dns.server")
public final class DnsServerConfig implements Cloneable {
    /**
     * UDP listening port.
     */
    private int port = 8553;

    /**
     * List of listening addresses. If empty all discovered DNS addresses will be used.
     */
    private Set<@NonNull String> addresses = new LinkedHashSet<>();

    /**
     * Netty event loop group to use; unless set, new event loop group will be created by the server.
     */
    private EventLoopGroup eventLoopGroup;

    /**
     * Eureka client used to perform service registry lookups.
     */
    private EurekaClient eurekaClient;

    /**
     * DNS response TTL in seconds.
     */
    private int ttl = 5;

    /**
     * Maximum number of host records to return to the client.
     */
    private int maxResponses = 5;

    /**
     * Maximum number of worker threads in newly created netty event loop group if event loop group is not supplied. Set
     * to 0 to detect number of available CPUs.
     *
     * @see #getEventLoopGroup()
     * @see #isPreferNativeTransport()
     */
    private int maxThreads = 1;

    /**
     * Prefer netty native transport if event loop group is not supplied.
     *
     * @see #getEventLoopGroup()
     * @see #getMaxThreads()
     */
    private boolean preferNativeTransport = true;

    /**
     * Eureka top level domain, should <b>NOT</b> contain prefixing/suffixing dot.
     */
    @NonNull
    private String domain = "eureka";

    /**
     * Log all received queries.
     */
    private boolean logQueries = false;

    /**
     * Adds single listening address.
     *
     * @param addr address
     * @return reference to itself.
     */
    public DnsServerConfig withAddress(@NonNull String addr) {
        addresses.add(addr);
        return this;
    }

    /**
     * Adds multiple listening addresses.
     *
     * @param addrs addresses
     * @return reference to itself.
     */
    public DnsServerConfig setAddresses(@NonNull Collection<String> addrs) {
        addresses.clear();
        addresses.addAll(addrs);
        return this;
    }

    /**
     * Adds multiple listening addresses.
     *
     * @param addrs addresses
     * @return reference to itself.
     */
    public DnsServerConfig withAddresses(@NonNull Collection<String> addrs) {
        addresses.addAll(addrs);
        return this;
    }

    /**
     * Validates internal state.
     *
     * @return deep copy of itself.
     * @throws IllegalStateException if internal state is not consistent.
     * @see #clone()
     */
    public DnsServerConfig validate() {
        if (eventLoopGroup != null && eventLoopGroup.isShutdown()) {
            throw new IllegalStateException("Cannot use shut down netty event loop group.");
        }
        if (eurekaClient == null) {
            throw new IllegalStateException("Eureka client is required.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("Invalid listening port: " + port);
        }
        if (maxThreads < 0) {
            throw new IllegalStateException("Invalid number of worker threads: " + maxThreads);
        }
        if (ttl < 0) {
            throw new IllegalStateException("Invalid TTL value: " + ttl);
        }

        return clone();
    }

    @Override
    public DnsServerConfig clone() {
        return new DnsServerConfig()
                .setPort(getPort())
                .withAddresses(getAddresses())
                .setEventLoopGroup(getEventLoopGroup())
                .setEurekaClient(getEurekaClient())
                .setTtl(getTtl())
                .setMaxResponses(getMaxResponses())
                .setMaxThreads(getMaxThreads())
                .setPreferNativeTransport(isPreferNativeTransport())
                .setDomain(getDomain())
                .setLogQueries(isLogQueries());
    }

    /**
     * Creates DNS server from specified configuration.
     *
     * @return dns server
     * @throws RuntimeException if the server cannot be created.
     */
    public EurekaDnsServer create() {
        return new EurekaDnsServer(this);
    }
}