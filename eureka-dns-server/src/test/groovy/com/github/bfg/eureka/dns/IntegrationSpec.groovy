package com.github.bfg.eureka.dns

import com.netflix.appinfo.ApplicationInfoManager
import com.netflix.appinfo.EurekaInstanceConfig
import com.netflix.appinfo.MyDataCenterInstanceConfig
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider
import com.netflix.discovery.DefaultEurekaClientConfig
import com.netflix.discovery.DiscoveryClient
import com.netflix.discovery.EurekaClient
import com.netflix.discovery.EurekaClientConfig
import groovy.util.logging.Slf4j
import spock.lang.Ignore
import spock.lang.Specification

@Ignore
@Slf4j
class IntegrationSpec extends Specification {

    EurekaClient eurekaClient() {
        def client = Mock(EurekaClient)

        client
    }

    DnsServerConfig builder() {
        EurekaDnsServer.builder()
                       .setPort(5353)
                       .setEurekaClient(createEurekaClient())
    }

    // TODO: remove
    def "test run"() {
        given:
        def server = builder().create();

        when:
        server.run()

        then:
        true
    }

    private EurekaClient createEurekaClient() {
        def eurekaUrl = "http://eureka-dev.dev.your.md/eureka/v2"
        log.info("creating eureka client with url: {}", eurekaUrl);
        System.setProperty("eureka.serviceUrl.default", eurekaUrl);

        def eurekaClientConfig = new DefaultEurekaClientConfig();
        def datacenterConfig = new MyDataCenterInstanceConfig();
        def appInfoManager = createAppInfoManager(datacenterConfig);

        createEurekaClient(appInfoManager, eurekaClientConfig);
    }

    /**
     * Creates eureka app info manager.
     *
     * @param instanceConfig instance config
     * @return app info manager.
     */
    private ApplicationInfoManager createAppInfoManager(EurekaInstanceConfig instanceConfig) {
        def instanceInfo = new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get();
        def appInfoManager = new ApplicationInfoManager(instanceConfig, instanceInfo);
        log.debug("created application info manager: {}", appInfoManager);
        return appInfoManager;
    }

    /**
     * Creates eureka client.
     *
     * @param appInfoManager app info manager
     * @param clientConfig client config
     * @return eureka client
     */
    private EurekaClient createEurekaClient(ApplicationInfoManager appInfoManager,
                                            EurekaClientConfig clientConfig) {
        def eurekaClient = new DiscoveryClient(appInfoManager, clientConfig);
        log.debug("created eureka client: {}", eurekaClient);
        return eurekaClient;
    }
}
