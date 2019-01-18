package com.github.bfg.eureka.dns

import com.netflix.discovery.EurekaClient
import groovy.util.logging.Slf4j
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

@Slf4j
class EurekaDnsServerSpec extends Specification {
    static def PORT = new ServerSocket(0).getLocalPort()
    //static Resolver resolver = resolver("localhost")

    @Shared
    EventLoopGroup elg = new NioEventLoopGroup(1)

    EurekaClient eurekaClient = eurekaClient()

    def cleanupSpec() {
        elg.shutdownGracefully(1, 1, TimeUnit.SECONDS).get()
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

        then: "method shut throw"
        thrown(IllegalStateException)
        future == null

        when: "stop the server"
        res = server.stop().get()

        then:
        res.is(server)
        server.elg.isShutdown()
        !elg.isShutdown()

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


        !elg.isShutdown() // shared event loop should not be shut down
        !server.elg.isShutdown()
    }

    @Timeout(2)
    def "run() should block until server stops"() {
        given: "create server"
        def server = builder().create()

        and: "setup shutdown thread"
        def shutdownThread = new Thread({
            Thread.sleep(1000)
            log.info("stopping server instance: {}", server)
            server.stop()
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

    EurekaClient eurekaClient() {
        def client = Mock(EurekaClient)

        client
    }

    DnsServerConfig builder() {
        EurekaDnsServer.builder()
                       .setPort(PORT)
                       .setEurekaClient(eurekaClient)
                       .setEventLoopGroup(elg)
    }
    
//    static Resolver resolver(String host) {
//        def r = new SimpleResolver(new InetSocketAddress(host, PORT))
//        r.setTimeout(0, 50)
//        r.setIgnoreTruncation(false)
//        r
//    }
//
//    static Lookup lookup(args) {
//        def l = new Lookup(args)
//        l.setResolver(resolver)
//        l
//    }
}
