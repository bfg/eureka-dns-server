package com.github.bfg.eureka.dns.client

import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Poor man's DNS client using <a href="https://en.wikipedia.org/wiki/Dig_(command)">dig cli binary</a>.
 */
@Slf4j
class DnsClient {
    private static final String binary = which("dig")

    /**
     * DNS server address
     */
    final String address

    /**
     * DNS server port
     */
    final int port

    /**
     * Query timeout.
     */
    final long timeoutMillis

    /**
     * Creates the client.
     * @param address dns server address
     * @param port dns server port
     */
    DnsClient(String address, int port, long timeoutMillis = 100) {
        this.address = address
        this.port = port
        this.timeoutMillis = timeoutMillis
    }

    /**
     * Resolves DNS query.
     *
     * @param name dns name to query
     * @param type record type, default {@code A}
     * @param dnsClass query class, default {@code IN}
     * @return map containing resolve status and answers
     */
    DnsResponse resolve(String name, String type = "A", String dnsClass = "IN") {
        def ts = System.nanoTime()
        def command = [binary, '+tries=1', '+timeout=2', '-p', port, "@${address}",
                       '-c', dnsClass, '-t', type, name] as String[]
        def process = new ProcessBuilder(command).start()
        log.debug("started command {} as {}", command.toList(), process)

        // read response from stdout/stderr
        def stdout = process.getInputStream().readLines("UTF-8").join("\n")
        log.debug("dig stdout: {}", stdout)

        def stderr = process.getErrorStream().readLines("UTF-8").join("\n")
        if (stderr) {
            log.warn("dig stderr: {}", stderr)
        }

        // wait for process to exit
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            def response = new DnsResponse()
            response.status = "DIG_TIMEOUT_FAILURE"
            response.error = stdout + stderr
            return response
        }

        def exitCode = process.exitValue()
        if (exitCode != 0) {
            def response = new DnsResponse()
            response.status = "DIG_FAILURE"
            response.error = stdout + stderr
            return response
        }

        def durNanos = System.nanoTime() - ts
        def duration = this.sprintf("%.2f", (durNanos / 1_000_000))

        def res = parseResponse(stdout)
        log.info("[{} {} {}] {} msec, {}, {} answers, {} authority, {} additional",
                name, type, dnsClass, duration,
                res.status, res.answers.size(), res.authorities.size(), res.additionals.size())

        res
    }

    DnsResponse parseResponse(String str) {
        def res = new DnsResponse()

        def statusP = ~/status: (\w+),/
        def sectionP = ~/^;; (\w+) SECTION:/

        //log.info("parsing:\n{}", str)
        def curSection = ''
        str.eachLine {
            //println("IT: $it")
            if (!it) return
            if (it.startsWith('; ')) return

            // status?
            def m = it =~ statusP
            if (m.find()) {
                res.status = m.group(1)
                return
            }

            // new section?
            m = it =~ sectionP
            if (m.find()) {
                curSection = m.group(1).toLowerCase()
                return
            }

            if (it.startsWith(';')) return

            def items = it.split('\\s+')
            log.trace("ITEMS: '{}' -> {} -> {}", it, items[1], items)
            def type = items[3]

            def record = null
            if (type == 'SOA') {
                record = new SOARecord()
                record.nameserver = fixName(items[4])
                record.mbox = fixName(items[5])
                record.serial = items[6].toInteger()
                record.refresh = items[7].toInteger()
                record.retry = items[8].toInteger()
                record.expire = items[9].toInteger()
                record.minTtl = items[10].toInteger()
            } else if (type == 'SRV') {
                record = new SRVRecord()
                record.priority = items[4].toInteger()
                record.weight = items[5].toInteger()
                record.port = items[6].toInteger()
                record.target = items[7]
            } else {
                record = new GenericRecord()
                record.answer = fixName(items[4])
            }

            record.name = items[0].replaceAll('\\.$', '')
            record.ttl = items[1].toInteger()
            record.dnsClass = items[2]
            record.type = type
            log.debug("created record: {} {} -> {}", curSection, items[1], record)

            // add record
            if (curSection == 'answer') {
                res.answers << record
            } else if (curSection == 'authority') {
                res.authorities << record
            } else if (curSection == 'additional') {
                res.additionals << record
            }
        }

        res
    }

    String fixName(String s) {
        s.replaceAll('"', '')
         .replaceAll('\\.$', '')
    }

    private static String which(String binary) {
        def path = System.getenv("PATH").split('\\s*[;:]+\\s*')
                         .findAll { Files.isDirectory(Paths.get(it)) }
                         .collect { it + File.separator + binary }
                         .find { Files.isExecutable(Paths.get(it)) }

        if (!path) {
            throw new IllegalStateException("Cannot find zdns in PATH; install it from https://github.com/zmap/zdns")
        }

        path.toString()
    }
}
