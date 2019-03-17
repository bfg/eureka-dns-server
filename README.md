# Eureka DNS server

[![CircleCI](https://circleci.com/gh/bfg/eureka-dns-server.svg?style=svg)](https://circleci.com/gh/bfg/eureka-dns-server)
[![codecov](https://codecov.io/gh/bfg/eureka-dns-server/branch/master/graph/badge.svg)](https://codecov.io/gh/bfg/eureka-dns-server)
[![latest release](https://img.shields.io/nexus/r/https/oss.sonatype.org/com.github.bfg.eureka/eureka-dns-server.svg)](https://mvnrepository.com/artifact/com.github.bfg.eureka/eureka-dns-server)
[![latest snapshot](https://img.shields.io/nexus/s/https/oss.sonatype.org/com.github.bfg.eureka/eureka-dns-server.svg)](https://oss.sonatype.org/content/groups/public/com/github/bfg/eureka/eureka-dns-server/)
[![Javadocs](http://javadoc.io/badge/com.github.bfg.eureka/eureka-dns-server.svg?color=blue)](http://javadoc.io/doc/com.github.bfg.eureka/eureka-dns-server)

`eureka-dns-server` is RFC1035/2782 compatible DNS server interface to [Netflix eureka](https://github.com/Netflix/eureka)
service registry which can be ran as a standalone daemon or embedded in any java application including eureka server 
itself.

Services registered in eureka registry are being exposed as DNS names without need to contact eureka registry via it's 
REST interface, which can be a cumbersome task in languages without native `eureka-client` support (javascript, ruby, 
python). Accessing your service can be as easy as:

```
curl http://my-service.service.eureka/
```

# IPv6 support

Server supports automatic discovery of available IP addresses and will bind all of them by default, including IPv6 ones.
`AAAA` records are being returned for service instances that are registered to eureka service registry with IPv6 address.

# DNS interface

DNS interface is minimal and all queries should be in the following form:

* `<name>.service.<domain>` for queries in default eureka region, where `name` is registered service name and `domain`
is eureka dns server top level domain name
* `<name>.service.<region>.<domain>` where `region` is some configured eureka region and where `default` is an alias for
default eureka client configured region. 

### A/AAAA lookups

```
$ dig @localhost -p 8553  myapp.service.eureka 

;; QUESTION SECTION:
;myapp.service.eureka.       IN      A

;; ANSWER SECTION:
myapp.service.eureka. 5      IN      A       10.11.3.142
```

IPv6 records are being returned as well if there are services registered to eureka using IPv6 address:

```
$ dig @localhost -p 8553  myapp.service.eureka AAAA

;; QUESTION SECTION:
;myapp.service.eureka.       IN      AAAA

;; ANSWER SECTION:
myapp.service.eureka. 5      IN      A       ::1
```

### TXT lookups

Asking for service TXT record returns list of active urls for given service:

```
$ dig @localhost -p 8553  myapp.service.eureka TXT

;; QUESTION SECTION:
;myapp.service.eureka.       IN      TXT

;; ANSWER SECTION:
myapp.service.eureka. 5      IN      TXT     "http://10.11.3.142:32769/"
``` 

### SRV lookups

#### normal SRV lookup

```
$ dig @localhost -p 8553  other-app.service.eureka SRV

;; QUESTION SECTION:
;other-app.service.eureka.      IN      SRV

;; ANSWER SECTION:
other-app.service.eureka. 5     IN      SRV     1 10 8080 ip-10-11-4-219.us-west-2.compute.internal.
other-app.service.eureka. 5     IN      SRV     1 10 8080 v6-host.us-west-2.compute.internal.

;; ADDITIONAL SECTION:
ip-10-11-4-219.us-west-2.compute.internal. 5 IN A 10.11.4.219
v6-host.us-west-2.compute.internal.        5 IN A ::1
```

#### RFC2782 SRV lookup
[RFC2782](https://www.ietf.org/rfc/rfc2782.txt) are supported as well:

```
$ dig @localhost -p 8553 _other-app._tcp.service.eureka SRV

;; QUESTION SECTION:
;_other-app._tcp.service.eureka.      IN      SRV

;; ANSWER SECTION:
_other-app._tcp.service.eureka. 5     IN      SRV     1 10 8080 ip-10-11-4-219.us-west-2.compute.internal.
_other-app._tcp.service.eureka. 5     IN      SRV     1 10 8080 v6-host.us-west-2.compute.internal.

;; ADDITIONAL SECTION:
ip-10-11-4-219.us-west-2.compute.internal. 5 IN A 10.11.4.219
v6-host.us-west-2.compute.internal.        5 IN A ::1
```

## Limitations

Eureka dns server doesn't allow DNS lookups over TCP, therefore clients can easily hit infamous
[DNS 512 byte payload size limit](https://tools.ietf.org/id/draft-madi-dnsop-udp4dns-00.html) or may receive truncated
response without being aware of it. This should not be a problem if eureka-dns-server is just a delegate resolver
for a single domain for an existing DNS server (bind, unbound, dnsmasq), because these services speak EDNS0 and are
commonly able to process UDP packets with payload size up to 4096 bytes.

# Usage

To use eureka-dns-server, you need at least java 8 runtime, java 11 should work as well.

##### maven

```xml
<dependency>
    <groupId>com.github.bfg.eureka</groupId>
    <artifactId>eureka-dns-server</artifactId>
    <version>latest-version</version>
</dependency>
```

##### gradle   

```gradle
compile     "com.github.bfg.eureka:eureka-dns-server:<latest-version>"
```

# Running

This project is designed to be easy to run and embed to existing apps. It can be ran as a standalone daemon, as a 
docker container or embedded as a completely standalone component in any java based application including eureka server
itself. 

## Embedded in eureka server

Eureka dns server can run embedded in any [spring-cloud-netflix](https://spring.io/projects/spring-cloud-netflix) enabled
application by annotating application starter class with `@EnableEurekaDnsServer`. You can customize it's settings in
`application.yml` file:

```yaml
eureka:
  dns:
    server:
      # server listening port, default: 8553
      port: 8553

      # comma separated list of listening addresses, by default all bound listening addresses are used
      # addresses: 127.0.0.1
      
      # top-level domain to use, default: eureka
      domain: eureka

      # DNS record TTL in seconds, default: 5
      ttl: 5
      
      # maximum number of A/AAAA/SRV/TXT records to return in response to a DNS query, default: 5
      max-responses: 5
      
      # maximum number of worker threads to use, default: 1
      # set to 0 to automatically size eventloop according to number of available cpu cores.
      max-threads: 1
      
      # prefer to use native (epoll/kqueue) backed netty event loop to achieve maximum performance, default: true
      prefer-native-transport: false
            
      # log dns queries?
      log-queries: true
``` 

## Standalone daemon

[eureka-dns-server-standalone](eureka-dns-server-standalone) subproject contains eureka-dns-server packaged as runnable 
uberjar with simple command line interface.

```
Usage: <main class> [-hlV] [-c=<eurekaPropertiesFile>] [-p=<port>]
                    [-t=<threads>] [-e=<eurekaUrls>]...
  -c, --config=<eurekaPropertiesFile>
                            Path to eureka properties file.
  -e, --eureka-url=<eurekaUrls>
                            Comma separated list of eureka server URLs.
  -p, --port=<port>         DNS server listening port.
  -t, --threads=<threads>   Number of working threads, set only if native transport
                              is available; setting this number to 0 sets number of
                              workers to number of available CPU cores.
  -l, --log-queries         Log received queries.
  -h, --help                Show this help message and exit.
  -V, --version             Print version information and exit.
```

### Running as docker image

[eureka-dns-server][docker image] is [standalone module] packaged as docker container; it's usage is exactly the same
as standalone daemon, just don't forget to expose listening port:

```
docker run -m 384m -it --rm -p 8553:8553/udp gracnar/eureka-dns-server -e http://eureka.example.com/eureka
``` 

## Embedding in your application

Embedding this project in your application is straightforward:

```java
val server = EurekaDnsServer.builder()
  .setPort(9553)
  .setEurekaClient(eurekaClient)
  .build();

server.start();
```

If you want to achieve maximum performance you should [include netty native transport](https://netty.io/wiki/native-transports.html)
dependencies to classpath.

# DNS zone forwarding

Eureka DNS server is meant to be just a delegated resolver for a single domain; this means that you need to set up your
current DNS server in your network to delegate DNS queries for `.eureka` domain to eureka dns server. Please read
excellent [consul DNS zone forwarding guide](https://www.consul.io/docs/guides/forwarding.html) how to configure
different DNS servers.

# Building

To build this project you need at least JDK 8 installed. You also need `dig` installed because it's being used by 
integration tests.

```bash
./gradlew clean build
```

# Performance

Eureka DNS server is built on top of [netty](https://netty.io/) and is able to utilize epoll/kqueue based transports
if they are available on classpath; [eureka dns server standalone][standalone module] submodule and 
[docker image] already include epoll dependencies by default which enable `SO_REUSEPORT` functionality
leading to almost linear scaling across cpu cores.

Project contains `scripts/performance-test.sh` script, which allows you to test actual performance of eureka dns server
on your setup (requires [dnsperf] installed).

Server was started as docker container with host networking to reduce NAT overhead. Script was invoked like this: 

```
./scripts/performance-test.sh {existing-a,existing-b,non-existing}.service.eureka
```

Here are figures on my laptop with i7-8550u CPU on linux:

* 1 worker thread: `docker run -it --rm -m384m --network host gracnar/eureka-dns-server -e http://eureka.example.com/eureka`
```
[Status] Command line: dnsperf -s 127.0.0.1 -p 8553 -l 20 -c 100
[Status] Sending queries (to 127.0.0.1)
[Status] Started at: Mon Feb 25 02:14:23 2019
[Status] Stopping after 20.000000 seconds
[Status] Testing complete (time limit)

Statistics:

  Queries sent:         1484420u
  Queries completed:    1484420u (100.00%)
  Queries lost:         0u (0.00%)

  Response codes:       NOERROR 990513u (66.73%), NXDOMAIN 493907u (33.27%)
  Average packet size:  request 42, response 69
  Run time (s):         20.001198
  Queries per second:   74216.554428

  Average Latency (s):  0.001285 (min 0.000030, max 0.017320)
  Latency StdDev (s):   0.000369
```
* all available worker threads: `docker run -it --rm -m384m --network host gracnar/eureka-dns-server -e http://eureka.example.com/eureka -t 0`
```
[Status] Command line: dnsperf -s 127.0.0.1 -p 8553 -l 20 -c 100
[Status] Sending queries (to 127.0.0.1)
[Status] Started at: Wed Feb 27 00:52:33 2019
[Status] Stopping after 20.000000 seconds
[Status] Testing complete (time limit)

Statistics:

  Queries sent:         3578467u
  Queries completed:    3578467u (100.00%)
  Queries lost:         0u (0.00%)

  Response codes:       NOERROR 2147785u (60.02%), NXDOMAIN 1430682u (39.98%)
  Average packet size:  request 41, response 65
  Run time (s):         20.000170
  Queries per second:   178921.829164

  Average Latency (s):  0.000157 (min 0.000013, max 0.068128)
  Latency StdDev (s):   0.000571
```

[docker image]: https://cloud.docker.com/repository/docker/gracnar/eureka-dns-server/
[standalone module]: eureka-dns-server-standalone
[dnsperf]: https://github.com/DNS-OARC/dnsperf