package com.github.bfg.eureka.dns

import com.netflix.discovery.EurekaClient
import groovy.util.logging.Slf4j
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@Slf4j
class EurekaDnsServerSpec extends Specification {
    static def PORT = new ServerSocket(0).getLocalPort()

    @Shared
    EventLoopGroup eventLoopGroup = new NioEventLoopGroup(1)

    EurekaClient eurekaClient = eurekaClient()

    def cleanupSpec() {
        eventLoopGroup.shutdownGracefully(1, 1, TimeUnit.SECONDS).get()
    }

    def "should create server without event loop group and correctly maintain start/stop functionality"() {
        given:
        def server = builder().setEventLoopGroup(null).create()

        when: "try to shut down non-running instance"
        def future = server.stop()

        then:
        thrown(IllegalStateException)
        future == null

        when: "start the server"
        def res = server.start().get()

        then:
        res.is(server)
        server.isRunning()
        when: "try to start it again"
        future = server.start()

        then: "method should throw"
        thrown(IllegalStateException)
        future == null

        when: "stop the server"
        res = server.stop().get()

        then:
        res.is(server)
        server.eventLoopGroup.isShutdown()
        !eventLoopGroup.isShutdown()

        when: "try to stop it again"
        future = server.stop()

        then:
        thrown(IllegalStateException)
        future == null
    }

    def "should create server with external event loop and not shut it down on server shutdown"() {
        given:
        def server = builder().create()

        when: "start and stop the server"
        def res = server.start().thenCompose({ it.stop() }).get()

        then:
        res.is(server)


        !eventLoopGroup.isShutdown() // shared event loop should not be shut down
        !server.eventLoopGroup.isShutdown()
    }

    @Timeout(2)
    def "run() should block until server stops"() {
        given: "create server"
        def server = builder().create()

        and: "setup shutdown thread"
        def shutdownThread = new Thread({
            Thread.sleep(1000)
            log.info("stopping server instance: {}", server)
            server.close()
        })
        shutdownThread.start()

        when: "run server"
        server.run()
        log.info("foo")

        then:
        !server.isRunning()
    }

    def "startup should fail for privileged port and instance state should be consistent"() {
        given:
        def server = builder().setPort(100).create()

        when:
        def result = server.start().get()

        then:
        def thrown = thrown(ExecutionException)
        result == null

        when: "inspect cause"
        def cause = thrown.cause
        log.info("failure cause:", cause)

        then:
        cause instanceof SocketException
        cause.getMessage() == "Permission denied"

        // inspect instance state
        !server.isRunning()
        server.channels == null
    }

    def "startup should fail for non-owned listening address  instance state should be consistent"() {
        given:
        def server = builder().withAddress("1.1.1.1").create()

        when:
        def result = server.start().get()

        then:
        def thrown = thrown(ExecutionException)
        result == null

        when: "inspect cause"
        def cause = thrown.cause
        log.info("failure cause:", cause)

        then:
        cause instanceof BindException
        cause.getMessage() == "Cannot assign requested address"

        // inspect instance state
        !server.isRunning()
        server.channels == null
    }

    def "starting server twice should result in a error"() {
        given:
        def server = builder().create()

        expect:
        server.start() != null

        when:
        server.start()

        then:
        thrown(IllegalStateException)
    }

    def "stopping already stopped server should throw an exception"() {
        given:
        def server = builder().create()

        when: "start/stop server"
        def result = server.start()
                           .thenCompose({ it.stop() })
                           .get()

        then:
        result.is(server)

        when: "stop it again"
        server.stop()

        then:
        thrown(IllegalStateException)
    }

    def "stopping server with provided event loop group should not stop event loop group"() {
        given:
        def server = builder().create()

        when: "start and stop server"
        server.start().get().close()

        then:
        !eventLoopGroup.isShutdown()
        !eventLoopGroup.isTerminated()
    }

    def "should create server with generic event loop group and specified number of threads"() {
        given:
        def threads = 7
        def server = builder()
                .setEventLoopGroup(null)
                .setPreferNativeTransport(false)
                .setMaxThreads(threads)
                .create()

        when:
        def elg = server.eventLoopGroup

        then:
        elg instanceof NioEventLoopGroup
        elg.executorCount() == threads

        when: "start/stop server"
        server.start().get().close()

        then: "event loop group should be shut down"
        elg.isShutdown()
    }

    def "should create server with generic event loop group and undefined of threads"() {
        given:
        def server = builder()
                .setEventLoopGroup(null)
                .setPreferNativeTransport(false)
                .setMaxThreads(0)
                .create()

        when:
        def elg = server.eventLoopGroup

        then:
        elg instanceof NioEventLoopGroup
        elg.executorCount() > 1

        when: "start/stop server"
        server.start().get().close()

        then: "event loop group should be shut down"
        elg.isShutdown()
    }

    def "should create server with native event loop group and specified number of threads"() {
        given:
        def threads = 7
        def server = builder()
                .setEventLoopGroup(null)
                .setPreferNativeTransport(true)
                .setMaxThreads(threads)
                .create()

        when:
        def elg = server.eventLoopGroup

        then:
        elg instanceof EpollEventLoopGroup
        elg.executorCount() == threads

        when: "start/stop server"
        server.start().get().close()

        then: "event loop group should be shut down"
        elg.isShutdown()
    }

    def "should create server with native event loop group and undefined of threads"() {
        given:
        def server = builder()
                .setEventLoopGroup(null)
                .setPreferNativeTransport(true)
                .setMaxThreads(0)
                .create()

        when:
        def elg = server.eventLoopGroup

        then:
        elg instanceof EpollEventLoopGroup
        elg.executorCount() > 1

        when: "start/stop server"
        server.start().get().close()

        then: "event loop group should be shut down"
        elg.isShutdown()
    }

    EurekaClient eurekaClient() {
        def client = Mock(EurekaClient)

        client
    }

    DnsServerConfig builder() {
        EurekaDnsServer.builder()
                       .setPort(PORT)
                       .setEurekaClient(eurekaClient)
                       .setEventLoopGroup(eventLoopGroup)
    }
}
