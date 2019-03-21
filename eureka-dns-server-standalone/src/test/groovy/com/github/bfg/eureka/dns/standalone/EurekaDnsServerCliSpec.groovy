package com.github.bfg.eureka.dns.standalone

import com.github.bfg.eureka.dns.EurekaDnsServer
import com.github.bfg.eureka.dns.FakeEurekaClient
import com.github.bfg.eureka.dns.client.DnsClient
import com.netflix.discovery.DiscoveryClient
import groovy.util.logging.Slf4j
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.environment.RestoreSystemProperties

import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@Slf4j
@Unroll
class EurekaDnsServerCliSpec extends Specification {
    def cli = new MyCli()

    def "foo test"() {
        given:
        log.info("this is dummy test.")

        expect:
        1 == 1
    }

    def "should complain about bad cli args"() {
        when:
        def status = cli.run("--non-existing-arg", "10")

        then:
        status == 255
        cli.exitStatus == 255
        cli.exception == null
        cli.dieReason.contains("Error parsing command line")
    }

    def "should display usage if invoked with: #arg"() {
        when:
        def status = cli.run(arg)

        then:
        status == 0
        cli.exitStatus == 0

        cli.stdoutOs.toString().contains("Usage: <main class> ")

        where:
        arg << ['-h', '--help']
    }

    def "should display version if invoked with: #arg"() {
        when:
        def status = cli.run(arg)

        then:
        status == 0
        cli.exitStatus == 0

        cli.stdoutOs.toString() == '0.9.9\n'

        where:
        arg << ['-V', '--version']
    }

    def "should actually start dns server start listening for UDP redirects"() {
        given:
        def eurekaClient = FakeEurekaClient.defaults()
        def port = 9393

        def client = new DnsClient('localhost', port)
        def args = ['-p', port] as String[]

        and: "start dns server in a separate thread"
        cli.getConfig().setEurekaClient(eurekaClient)
        cli.serverConsumer = { it.start().toCompletableFuture().get(1, TimeUnit.SECONDS) }
        cli.run(args)

        when: "ask for simple record"
        log.info("asking for dns response")
        def result = client.resolve('corse.service.eureka')

        then:
        result.status == "NOERROR"
        result.answers.size() == 3

        cleanup:
        cli?.server?.close()
    }

    def "should actually start dns server and initialize eureka client"() {
        given:
        cli.serverConsumer = { it.start().toCompletableFuture().get(1, TimeUnit.SECONDS) }

        when: "ask for simple record"
        cli.run('-p', '3940')

        then:
        def eurekaClient = cli.server.config.eurekaClient
        eurekaClient != null
        eurekaClient instanceof DiscoveryClient

        cleanup:
        cli?.server?.close()
    }

    @RestoreSystemProperties
    def "loadEurekaConfig([filename]) should load expected properties and set system properties"() {
        given:
        def filename = getClass().getResource('/eureka-client-test.properties').getFile()
        def cli = new MyCli()

        expect:
        System.getProperty('eureka.instance.appname') == null
        System.getProperty('eureka.registration.enabled') == null
        System.getProperty('eureka.client.registration.enabled') == null

        when:
        cli.run('-c', filename)

        then:
        System.getProperty('eureka.instance.appname') == 'eureka-dns-server-test'
        System.getProperty('eureka.registration.enabled') == 'false'
        System.getProperty('eureka.client.registration.enabled') == 'false'
    }

    @RestoreSystemProperties
    def "should set eureka urls"() {
        given:
        def urlA = 'http://foo/eureka'
        def urlB = 'http://baz/eureka'

        def cli = new MyCli()

        when:
        cli.run('-e', urlA, '-e', urlB)

        then:
        System.getProperty('eureka.serviceUrl.default') == urlA + ',' + urlB
    }

    /**
     * Cli used for testing
     */
    @Slf4j
    static class MyCli extends EurekaDnsServerCli {
        def stdoutOs = new ByteArrayOutputStream()
        def stdout = new PrintStream(stdoutOs)

        def stderrOs = new ByteArrayOutputStream()
        def stderr = new PrintStream(stderrOs)
        Consumer<EurekaDnsServer> serverConsumer

        int exitStatus = -1

        Throwable exception
        String dieReason
        EurekaDnsServer server

        @Override
        protected int runServer(EurekaDnsServer server) {
            if (serverConsumer) {
                serverConsumer.accept(server)
            }
            this.server = server
            return 0
        }

        @Override
        protected PrintStream getStdout() {
            stdout
        }

        @Override
        protected PrintStream getStderr() {
            stderr
        }

        @Override
        protected int die(String reason, Throwable cause) {
            this.dieReason = reason
            this.exception = cause
            log.error("dying because of: {}", reason, cause)
            return super.die(reason, cause)
        }

        @Override
        protected int exit(int exitStatus) {
            this.exitStatus = exitStatus
            exitStatus
        }

        @Override
        String toString() {
            return getClass().getSimpleName() + "@" + hashCode()
        }
    }
}
