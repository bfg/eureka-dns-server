package com.github.bfg.eureka.dns.standalone;

import com.github.bfg.eureka.dns.EurekaDnsServer;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import static java.lang.System.exit;

/**
 * Command line starter for {@link EurekaDnsServer}.
 */
@Slf4j
@Command(mixinStandardHelpOptions = true)
public final class EurekaDnsServerCli implements Runnable {
    @Option(names = {"-c", "--config"}, description = "Path to eureka properties file.")
    private String eurekaPropertiesFile = "";

    @Option(names = {"-e", "--eureka-url"}, description = "Override eureka urls")
    private List<String> eurekaUrls = new ArrayList<>();

    public static void main(String... args) {
        val cmdLine = new CommandLine(new EurekaDnsServerCli());

        try {
            val result = cmdLine.parseArgs(args);
            if (result.isVersionHelpRequested()) {
                cmdLine.printVersionHelp(System.out);
                exit(0);
            } else if (result.isUsageHelpRequested()) {
                cmdLine.usage(System.out);
                exit(0);
            }

            result.asCommandLineList().stream()
                    .map(CommandLine::getCommand)
                    .forEach(e -> ((Runnable) e).run());
        } catch (ParameterException e) {
            die(e.getMessage() + "\nError parsing command line. Run with --help for instructions.");
        }
    }

    @Override
    public void run() {
        val eurekaClient = createEurekaClient();
        val server = EurekaDnsServer.builder()
                .setEurekaClient(eurekaClient)
                .create();
        server.run();
    }

    /**
     * Terminates JVM.
     *
     * @param reason reason for termination
     */
    private static void die(@NonNull String reason) {
        die(reason, null);
    }

    private static void die(@NonNull String reason, Throwable cause) {
        val sb = new StringBuilder(reason);
        if (cause != null) {
            val message = (cause.getMessage() == null) ? cause.toString() : cause.getMessage();
            sb.append(": " + message);
        }

        System.err.println("FATAL: " + sb.toString());
        exit(255);
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
