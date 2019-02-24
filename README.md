# Eureka DNS server

[![CircleCI](https://circleci.com/gh/bfg/eureka-dns-server.svg?style=svg)](https://circleci.com/gh/bfg/eureka-dns-server)
[![codecov](https://codecov.io/gh/bfg/eureka-dns-server/branch/master/graph/badge.svg)](https://codecov.io/gh/bfg/eureka-dns-server)
[![latest release](https://img.shields.io/nexus/r/https/oss.sonatype.org/com.github.bfg.eureka/eureka-dns-server.svg)](https://mvnrepository.com/artifact/com.github.bfg.eureka/eureka-dns-server)
[![latest snapshot](https://img.shields.io/nexus/s/https/oss.sonatype.org/com.github.bfg.eureka/eureka-dns-server.svg)](https://oss.sonatype.org/content/groups/public/com/github/bfg/eureka/eureka-dns-server/)

`eureka-dns-server` is RFC1035/2782 compatible DNS server interface to [Netflix eureka](https://github.com/Netflix/eureka)
service registry which be run as a standalone daemon or embedded in any java application including eureka server itself.

Services registered in eureka registry are being exposed as DNS names without need to contact eureka registry via it's 
REST interface, which can be cumbersome task in languages without native `eureka-client` support. Accessing your service
can be as easy as:

```
curl http://microservice.service.eureka/
```

# DNS interface

### A/AAAA lookups

### TXT lookups

### SRV lookups

## Limitations

Eureka dns server doesn't allow DNS lookups over TCP therefore clients can easily hit infamous
[DNS 512 byte payload size limit](https://tools.ietf.org/id/draft-madi-dnsop-udp4dns-00.html); this should not be a problem
if eureka-dns-server is just a delegate resolver for a single domain for existing DNS server (bind, unbound, dnsmasq), 
because these services speak EDNS0 and are commonly able to process UDP packets up to 4096 bytes.   

# Running

This project is designed to be easily to run and embed to existing apps. It can be ran as a standalone daemon, as a 
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
Usage: <main class> [-hV] [-c=<eurekaPropertiesFile>] [-p=<port>]
                    [-t=<threads>] [-e=<eurekaUrls>]...
  -c, --config=<eurekaPropertiesFile>
                            Path to eureka properties file.
  -e, --eureka-url=<eurekaUrls>
                            Override eureka urls
  -h, --help                Show this help message and exit.
  -p, --port=<port>         DNS server listening port
  -t, --threads=<threads>   Number of working threads, set only if native transport
                              is available.
  -V, --version             Print version information and exit.
```

### Running as docker image

[eureka-dns-server docker image](https://cloud.docker.com/repository/docker/gracnar/eureka-dns-server/) is standalone daemon
packaged as docker container; it's usage is exactly the same to standalone daemon, just don't forget to expose listening
port:

```
docker run -m 384m -it --rm -p 8553:5353 gracnar/eureka-dns-server -e http://eureka.example.com/eureka
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
excellent [consul DNS zone forwarding guide](https://www.consul.io/docs/guides/forwarding.html) guide how to configure
different DNS servers.  

# Building

To build this project you need at least JDK 8 installed. Build with JDK11 was tested as well.

```bash
./gradlew build
```

# Integration, DNS zone forwarding