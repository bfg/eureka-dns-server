package com.github.bfg.eureka.dns.standalone;

import lombok.SneakyThrows;
import lombok.val;
import picocli.CommandLine;

import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Version provider for {@link EurekaDnsServerCli}.
 */
public final class VersionProvider implements CommandLine.IVersionProvider {
    private static final String UNKNOWN_VERSION = "unknown-version";
    private static final String BUILD_VERSION_PROPERTY = "git.build.version";

    @Override
    public String[] getVersion() {
        return new String[]{getVersionString()};
    }

    private String getVersionString() {
        return Optional.ofNullable(getClass().getResourceAsStream("/git.properties"))
                .map(this::readVersion)
                .orElse(UNKNOWN_VERSION);
    }

    @SneakyThrows
    private String readVersion(InputStream inputStream) {
        try (val is = inputStream) {
            val props = new Properties();
            props.load(is);
            return props.getProperty(BUILD_VERSION_PROPERTY);
        }
    }
}
