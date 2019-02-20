package com.github.bfg.eureka.dns.standalone;

import com.github.bfg.eureka.dns.DnsServerConfig;
import com.github.bfg.eureka.dns.EurekaDnsServer;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Command line starter for {@link EurekaDnsServer}.
 */
@Slf4j
@Command(mixinStandardHelpOptions = true, versionProvider = VersionProvider.class)
public class EurekaDnsServerCli implements Callable<Integer> {
    /**
     * Default server config.
     */
    @Getter(AccessLevel.PROTECTED)
    private DnsServerConfig config = new DnsServerConfig();

    @Option(names = {"-c", "--config"}, description = "Path to eureka properties file.")
    private String eurekaPropertiesFile = "";

    @Option(names = {"-e", "--eureka-url"}, description = "Override eureka urls")
    private List<String> eurekaUrls = new ArrayList<>();

    @Option(names = {"-p", "--port"}, description = "DNS server listening port")
    private int port = config.getPort();

    @Option(names = {"-t", "--threads"}, description = "Number of working threads, set only if native transport is available.")
    private int threads = config.getMaxThreads();

    /**
     * Stdout stream.
     */
    @Getter(AccessLevel.PROTECTED)
    private PrintStream stdout = System.out;

    /**
     * Stderr stream.
     */
    @Getter(AccessLevel.PROTECTED)
    private PrintStream stderr = System.err;

    /**
     * Application entry point.
     *
     * @param args command line args
     */
    public static void main(String... args) {
        new EurekaDnsServerCli().run(args);
    }

    /**
     * Runs the program.
     *
     * @param args command line args.
     * @return exit status
     */
    @SneakyThrows
    protected final int run(String... args) {
        val cmdLine = new CommandLine(this);

        try {
            val result = cmdLine.parseArgs(args);
            if (result.isVersionHelpRequested()) {
                cmdLine.printVersionHelp(getStdout());
                return exit(0);
            } else if (result.isUsageHelpRequested()) {
                cmdLine.usage(getStdout());
                return exit(0);
            }

            return result.asCommandLineList().stream()
                    .map(CommandLine::getCommand)
                    .filter(e -> e instanceof Callable)
                    .map(e -> (Callable<Integer>) e)
                    .findFirst()
                    .orElseGet(() -> () -> die("Cannot extract runtime method."))
                    .call();
        } catch (ParameterException e) {
            return die(e.getMessage() + "\nError parsing command line. Run with --help for instructions.");
        }
    }

    @Override
    public Integer call() {
        // create eureka client if needed.
        val eurekaClient = Optional.ofNullable(config.getEurekaClient())
                .orElseGet(() -> createEurekaClient());

        val server = config
                .setPort(port)
                .setMaxThreads(threads)
                .setEurekaClient(eurekaClient)
                .setLogQueries(true)
                .create();

        server.run();
        return 0;
    }

    /**
     * Terminates JVM with error message being written to {@link #getStderr()}.
     *
     * @param reason reason for termination
     * @return exit status of 255
     */
    protected final int die(@NonNull String reason) {
        return die(reason, null);
    }

    /**
     * Terminates JVM with error message being written to {@link #getStderr()}
     *
     * @param reason reason for termination
     * @param cause  exception, may be null
     * @return exit status of 255
     */
    protected int die(@NonNull String reason, Throwable cause) {
        val sb = new StringBuilder(reason);
        if (cause != null) {
            val message = (cause.getMessage() == null) ? cause.toString() : cause.getMessage();
            sb.append(": " + message);
        }

        getStderr().println("FATAL: " + sb.toString());
        return exit(255);
    }

    /**
     * Terminates JVM with given exit status.
     *
     * @param exitStatus termination status
     * @return given {@code exitStatus}
     */
    protected int exit(int exitStatus) {
        System.exit(exitStatus);
        return exitStatus;
    }

    private EurekaClient createEurekaClient() {
        loadEurekaConfig();
        setCustomEurekaUrl();

        val eurekaClientConfig = new DefaultEurekaClientConfig();
        val datacenterConfig = new MyDataCenterInstanceConfig();
        val appInfoManager = createAppInfoManager(datacenterConfig);
        return createEurekaClient(appInfoManager, eurekaClientConfig);
    }

    /**
     * Creates eureka app info manager.
     *
     * @param instanceConfig instance config
     * @return app info manager.
     */
    private ApplicationInfoManager createAppInfoManager(@NonNull EurekaInstanceConfig instanceConfig) {
        val instanceInfo = new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get();
        val appInfoManager = new ApplicationInfoManager(instanceConfig, instanceInfo);
        log.debug("created application info manager: {}", appInfoManager);
        return appInfoManager;
    }

    /**
     * Creates eureka client.
     *
     * @param appInfoManager app info manager
     * @param clientConfig   client config
     * @return eureka client
     */
    private EurekaClient createEurekaClient(@NonNull ApplicationInfoManager appInfoManager,
                                            @NonNull EurekaClientConfig clientConfig) {
        val eurekaClient = new DiscoveryClient(appInfoManager, clientConfig);
        log.info("created eureka client: {}", eurekaClient);
        return eurekaClient;
    }

    private void setCustomEurekaUrl() {
        val urlList = eurekaUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .collect(Collectors.joining(","));
        if (!urlList.isEmpty()) {
            System.setProperty("eureka.serviceUrl.default", urlList);
            log.info("set eureka url list to: {}", urlList);
        }
    }

    private void loadEurekaConfig() {
        Optional.ofNullable(eurekaPropertiesFile)
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .ifPresent(this::loadEurekaConfig);
    }

    private void loadEurekaConfig(@NonNull String filename) {
        val path = Paths.get(filename);
        val properties = new Properties();
        try (val reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            die("Error loading eureka config: " + filename, e);
        }
        log.info("successfully loaded eureka properties: {}", filename);

        properties.keySet().stream()
                .map(Object::toString)
                .forEach(key -> System.setProperty(key, properties.getProperty(key)));
    }
}