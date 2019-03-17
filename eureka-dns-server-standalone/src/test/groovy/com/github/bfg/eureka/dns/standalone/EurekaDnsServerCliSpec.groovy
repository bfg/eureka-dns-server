package com.github.bfg.eureka.dns.standalone

import com.github.bfg.eureka.dns.FakeEurekaClient
import com.github.bfg.eureka.dns.client.DnsClient
import groovy.util.logging.Slf4j
import spock.lang.Specification
import spock.lang.Unroll

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

    def "should actually start dns server"() {
        given:
        def eurekaClient = FakeEurekaClient.defaults()
        def port = 9393

        def client = new DnsClient('localhost', port)
        def args = ['-p', port] as String[]

        and: "start dns server in a separate thread"
        cli.getConfig().setEurekaClient(eurekaClient)
        def thread = new Thread({ cli.run(args) })
        thread.start()
        Thread.sleep(800)

        when: "ask for simple record"
        log.info("asking for dns response")
        def result = client.resolve('corse.service.eureka')

        then:
        result.status == "NOERROR"
        result.answers.size() == 3
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

        int exitStatus = -1

        Throwable exception
        String dieReason

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
