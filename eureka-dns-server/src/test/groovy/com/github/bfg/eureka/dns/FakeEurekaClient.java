package com.github.bfg.eureka.dns;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.EurekaEventListener;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Fake eureka client, meant for testing.
 */
@Slf4j
@SuppressWarnings("deprecated")
public final class FakeEurekaClient implements EurekaClient {
    private static final Applications EMPTY_APPS = new Applications();
    /**
     * Classpath filename of source data file (value: <b>{@value}</b>)
     */
    private static final ObjectMapper mapper = createObjectMapper();

    /**
     * Prefix for applications json files on classpath
     */
    public static final String EUREKA_APPS_FNAME_PREFIX = "eureka-apps-";

    private final Map<String, Applications> appsMap = new ConcurrentHashMap<>();

    private static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .configure(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .findAndRegisterModules();
    }

    /**
     * Creates fake instance by reading {@link #EUREKA_APPS_FNAME_PREFIX} files from classpath.
     *
     * @return eureka client instance.
     */
    public static FakeEurekaClient defaults() {
        return new FakeEurekaClient().loadFromClasspath();
    }

    @SneakyThrows
    public FakeEurekaClient loadFromClasspath() {
        val cwd = new File(".").getCanonicalFile();
        val resources = cwd + "/src/test/resources";

        try (val stream = Files.list(Paths.get(resources))) {
            stream
                    .filter(e -> e.toString().contains(EUREKA_APPS_FNAME_PREFIX))
                    .filter(e -> Files.isReadable(e))
                    .filter(e -> Files.isRegularFile(e))
                    .forEach(path -> read(path.toString()));
        }

        return this;
    }

    public FakeEurekaClient read(@NonNull String filename) {
        val name = Paths.get(filename).getFileName().toString()
                .replace(EUREKA_APPS_FNAME_PREFIX, "")
                .replace(".json", "");

        return read(openIs(filename), name);
    }

    private InputStream openIs(String filename) {
        try {
            return Files.newInputStream(Paths.get(filename));
        } catch (IOException e) {
            return Optional.ofNullable(getClass().getResourceAsStream(filename))
                    .orElseThrow(() -> new IllegalArgumentException("Cannot read " + filename));
        }
    }

    @SneakyThrows
    public FakeEurekaClient read(@NonNull InputStream inputStream, @NonNull String name) {
        try (val is = inputStream) {
            val apps = mapper.readValue(is, Applications.class);
            log.info("read {} applications for region {}", apps.size(), name);
            appsMap.put(name.toLowerCase(), apps);
        }
        return this;
    }

    @Override
    public Applications getApplicationsForARegion(String region) {
        return appsMap.get(region);
    }

    @Override
    public Applications getApplications(String serviceUrl) {
        return notImplemented();
    }

    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure) {
        return notImplemented();
    }

    @Override
    public List<InstanceInfo> getInstancesByVipAddress(String vipAddress, boolean secure, String region) {
        return notImplemented();
    }

    @Override
    public List<InstanceInfo> getInstancesByVipAddressAndAppName(String vipAddress, String appName, boolean secure) {
        return notImplemented();
    }

    @Override
    public Set<String> getAllKnownRegions() {
        return appsMap.keySet();
    }

    @Override
    public InstanceInfo.InstanceStatus getInstanceRemoteStatus() {
        return notImplemented();
    }

    @Override
    public List<String> getDiscoveryServiceUrls(String zone) {
        return notImplemented();
    }

    @Override
    public List<String> getServiceUrlsFromConfig(String instanceZone, boolean preferSameZone) {
        return notImplemented();
    }

    @Override
    public List<String> getServiceUrlsFromDNS(String instanceZone, boolean preferSameZone) {
        return notImplemented();
    }

    @Override
    @SuppressWarnings("deprecated")
    public void registerHealthCheckCallback(HealthCheckCallback callback) {
        notImplemented();
    }

    @Override
    public void registerHealthCheck(HealthCheckHandler healthCheckHandler) {
        notImplemented();
    }

    @Override
    public void registerEventListener(EurekaEventListener eventListener) {
        notImplemented();
    }

    @Override
    public boolean unregisterEventListener(EurekaEventListener eventListener) {
        return notImplemented();
    }

    @Override
    public HealthCheckHandler getHealthCheckHandler() {
        return notImplemented();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public EurekaClientConfig getEurekaClientConfig() {
        return notImplemented();
    }

    @Override
    public ApplicationInfoManager getApplicationInfoManager() {
        return notImplemented();
    }

    @Override
    public Application getApplication(String appName) {
        return getApplications().getRegisteredApplications(appName);
    }

    @Override
    public Applications getApplications() {
        return Optional.ofNullable(appsMap.get("default")).orElse(EMPTY_APPS);
    }

    @Override
    public List<InstanceInfo> getInstancesById(String id) {
        return notImplemented();
    }

    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        return notImplemented();
    }

    @Override
    public String toString() {
        val regions = appsMap.size();
        val apps = getApplications();
        val sb = new StringBuilder(getClass().getSimpleName() + "(regions=" + regions + ", size=");
        sb.append(apps.size() + ") ");
        val appStrs = apps.getRegisteredApplications().stream()
                .map(app -> {
                    val name = app.getName().toLowerCase();
                    val num = app.getInstances().size();
                    return name + "=" + num;
                })
                .collect(Collectors.toList());
        return sb.append(appStrs).append(")").toString();
    }

    private <T> T notImplemented() throws RuntimeException {
        throw new UnsupportedOperationException("This method is not implemented in class " + getClass().getName());
    }
}
