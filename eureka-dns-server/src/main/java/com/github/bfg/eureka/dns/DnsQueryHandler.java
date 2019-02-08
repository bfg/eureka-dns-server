package com.github.bfg.eureka.dns;

import com.google.common.net.InetAddresses;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.InstanceInfo.PortType;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.netty.handler.codec.dns.DnsRecordType.*;
import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * DNS query netty channel handler.
 *
 * @see <a href="https://www.ietf.org/rfc/rfc1035.txt">RFC 1035 :: Domain names</a>
 * @see <a href="https://tools.ietf.org/html/rfc2782">RFC 2782 :: A DNS RR for specifying the location of services
 *         (DNS SRV)</a>
 * @see <a href="https://www.consul.io/docs/agent/dns.html">Consul DNS interface</a>
 * @see <a href="https://www.haproxy.com/blog/dns-service-discovery-haproxy/">Haproxy service discovery</a>
 */
@Slf4j
@ChannelHandler.Sharable
final class DnsQueryHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
    private static final Set<DnsRecordType> VALID_QUESTION_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList(A, AAAA, ANY, TXT, SRV, DS, SOA, NS)));

    private static final String SERVICE_NAME_REGEX = "\\.?_?([\\w\\-]+)\\.(?:_\\w+\\.)?(?:service|node|connect)\\.";
    private static final String DATACENTER_REGEX = "([\\w\\-]+)\\.";


    private final DnsServerConfig config;
    private final EurekaClient eurekaClient;

    /**
     * DNS server hostname (used for NS/SOA responses)
     */
    private final String nsHostname;

    /**
     * Query name pattern for queries that don't include datacenter.
     *
     * @see #getDatacenter(String)
     * @see #getServiceName(String)
     */
    private final Pattern withoutDcPattern;

    /**
     * Query name pattern for queries that include datacenter.
     *
     * @see #getDatacenter(String)
     * @see #getServiceName(String)
     */
    private final Pattern withDcPattern;

    /**
     * Creates new instance.
     *
     * @param config configuration
     */
    DnsQueryHandler(@NonNull DnsServerConfig config) {
        this.config = config;
        this.eurekaClient = config.getEurekaClient();
        this.nsHostname = "ns." + config.getDomain();

        this.withoutDcPattern =
                Pattern.compile(SERVICE_NAME_REGEX + config.getDomain() + "\\.?$");
        this.withDcPattern =
                Pattern.compile(SERVICE_NAME_REGEX + DATACENTER_REGEX + config.getDomain() + "\\.?$");
    }

    @Override
    @SneakyThrows
    protected void channelRead0(@NonNull ChannelHandlerContext ctx, @NonNull DatagramDnsQuery msg) {
        log.trace("received dns query: {}", msg);

        val response = createResponse(msg);
        logDnsQuery(msg, response);

        ctx.writeAndFlush(response);
    }

    /**
     * Logs DNS query.
     *
     * @param msg      original client's dns query.
     * @param response response being sent to client.
     */
    private void logDnsQuery(@NonNull DatagramDnsQuery msg, DatagramDnsResponse response) {
        if (config.isLogQueries()) {
            val question = msg.recordAt(DnsSection.QUESTION);
            val client = response.recipient();
            log.info("query from=[{}]:{} type={} name={} status={}, answers={}",
                    InetAddresses.toAddrString(client.getAddress()), client.getPort(),
                    question.type(), question.name(),
                    response.code(), response.count(DnsSection.ANSWER));
        }
        log.trace("sending DNS response: {}", response);
    }

    /**
     * Creates response to given DNS query.
     *
     * @param query dns query
     * @return response that should be sent to client.
     */
    protected DatagramDnsResponse createResponse(DatagramDnsQuery query) {
        try {
            return finishResponse(respondToDnsQuery(query));
        } catch (Exception e) {
            log.error("exception while constructing response: {}", e.getMessage(), e);
            return basicResponse(query).setCode(DnsResponseCode.SERVFAIL);
        }
    }

    /**
     * Retrieves datacenter name from DNS query name.
     *
     * @param name dns query name
     * @return datacenter on success, otherwise empty string
     */
    protected String getDatacenter(@NonNull String name) {
        // without datacenter
        Matcher matcher = withoutDcPattern.matcher(name);
        if (matcher.find()) {
            return "";
        }

        // with datacenter
        matcher = withDcPattern.matcher(name);
        if (matcher.find()) {
            return matcher.group(2);
        }

        return "";
    }

    /**
     * Retrieves service-name from DNS query name.
     *
     * @param name dns query name
     * @return datacenter on success, otherwise empty string
     */
    protected String getServiceName(@NonNull String name) {
        name = name.toLowerCase().trim();

        // without datacenter
        Matcher matcher = withoutDcPattern.matcher(name);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // with datacenter
        matcher = withDcPattern.matcher(name);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return "";
    }

    private DatagramDnsResponse respondToDnsQuery(@NonNull DatagramDnsQuery msg) {
        val question = msg.recordAt(DnsSection.QUESTION);

        // create response instance.
        val response = basicResponse(msg);

        if (!isValidQuestion(question)) {
            return response.setCode(DnsResponseCode.REFUSED);
        }

        val qType = question.type();
        val questionName = question.name().toLowerCase();

        // NS queries always result in the same response
        if (qType.equals(NS)) {
            return configureResponseNS(response, questionName);
        }
        // SOA queries always result in the same response
        else if (qType.equals(SOA)) {
            return configureResponseSOA(response, questionName);
        }
        // we should always respond with NXDOMAIN to DS queries
        else if (qType.equals(DS)) {
            return response;
        }

        // we absolutely need service name
        val serviceName = getServiceName(questionName);
        if (serviceName.isEmpty()) {
            return response.setCode(DnsResponseCode.BADNAME);
        }

        // datacenter may be in question as well.
        val datacenter = getDatacenter(questionName);

        log.debug("asked for: type={} name={} service={} datacenter={}", qType, questionName, serviceName, datacenter);
        return doConfigureResponse(response, qType, questionName, serviceName, datacenter);
    }

    private DatagramDnsResponse doConfigureResponse(@NonNull DatagramDnsResponse response,
                                                    @NonNull DnsRecordType type,
                                                    String questionName, String serviceName, String datacenter) {
        if (type.equals(A)) {
            return configureResponseA(response, questionName, serviceName, datacenter);
        } else if (type.equals(AAAA)) {
            return configureResponseAAAA(response, questionName, serviceName, datacenter);
        } else if (type.equals(TXT)) {
            return configureResponseTXT(response, questionName, serviceName, datacenter);
        } else if (type.equals(SRV)) {
            return configureResponseSRV(response, questionName, serviceName, datacenter);
        } else if (type.equals(ANY)) {
            return configureResponseANY(response, questionName, serviceName, datacenter);
        }

        throw new IllegalArgumentException("Don't know how to create DNS response to question: "
                + type + " " + questionName);
    }

    /**
     * Configures eureka dns server NS query response.
     *
     * @param response     response to configure
     * @param questionName dns query question name.
     * @return given response
     */
    private DatagramDnsResponse configureResponseNS(DatagramDnsResponse response, String questionName) {
        return response
                .addRecord(DnsSection.ANSWER, createEurekaDnsServerNSRecord(questionName))
                .addRecord(DnsSection.ADDITIONAL, createEurekaDnsServerHostRecord(response.sender().getAddress()));
    }

    /**
     * Configures eureka dns server SOA query response.
     *
     * @param response     response to configure
     * @param questionName dns query question name.
     * @return given response
     */
    private DatagramDnsResponse configureResponseSOA(DatagramDnsResponse response, String questionName) {
        return response
                .addRecord(DnsSection.ANSWER, createEurekaDnsServerSOARecord(questionName))
                .addRecord(DnsSection.AUTHORITY, createEurekaDnsServerNSRecord(questionName))
                .addRecord(DnsSection.ADDITIONAL, createEurekaDnsServerHostRecord(response.sender().getAddress()));
    }

    private ByteBuf encodeRDataNS() {
        return encodeDnsName(nsHostname, Unpooled.buffer());
    }

    /**
     * Performs finishing tasks before response is being returned.
     *
     * @param response response
     * @return the same response
     */
    private DatagramDnsResponse finishResponse(@NonNull DatagramDnsResponse response) {
        if (response.count(DnsSection.ANSWER) > 0) {
            response.setCode(DnsResponseCode.NOERROR);
        }
        return response;
    }

    /**
     * Configures response for A question.
     *
     * @param response     response to be configured
     * @param questionName question name
     * @param serviceName  service name
     * @param datacenter   datacenter name
     * @return given {@code response}
     */
    private DatagramDnsResponse configureResponseA(DatagramDnsResponse response,
                                                   String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for A record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        getEurekaAppInstances(serviceName, datacenter)
                .map(this::getInstanceIpAddress)
                .filter(this::isIpv4Address)
                .map(addr -> new DefaultDnsRawRecord(questionName, A, config.getTtl(), encodeRDataHostAddress(addr)))
                .forEach(record -> response.addRecord(DnsSection.ANSWER, record));

        return response;
    }

    /**
     * Configures response for AAAA question.
     *
     * @param response     response to be configured
     * @param questionName question name
     * @param serviceName  service name
     * @param datacenter   datacenter name
     * @return given {@code response}
     */
    private DatagramDnsResponse configureResponseAAAA(DatagramDnsResponse response,
                                                      String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for AAAA record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        getEurekaAppInstances(serviceName, datacenter)
                .map(this::getInstanceIpAddress)
                .filter(this::isIpv6Address)
                .map(addr -> new DefaultDnsRawRecord(questionName, AAAA, config.getTtl(), encodeRDataHostAddress(addr)))
                .forEach(record -> response.addRecord(DnsSection.ANSWER, record));

        return response;
    }

    /**
     * Configures response for TXT question.
     *
     * @param response     response to be configured
     * @param questionName question name
     * @param serviceName  service name
     * @param datacenter   datacenter name
     * @return given {@code response}
     */
    private DatagramDnsResponse configureResponseTXT(DatagramDnsResponse response,
                                                     String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for TXT record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        val counter = newRecordPredicate();
        getEurekaAppInstances(serviceName, datacenter)
                .map(instanceInfo -> toInstanceUrlAddress(instanceInfo, InstanceInfo::getHostName))
                .distinct()
                .filter(counter::test)
                .map(url -> toDnsTXTRecord(questionName, url))
                .forEach(e -> response.addRecord(DnsSection.ANSWER, e));

        return response;
    }

    /**
     * Configures response for SRV question.
     *
     * @param response     response to be configured
     * @param questionName question name
     * @param serviceName  service name
     * @param datacenter   datacenter name
     * @return given {@code response}
     */
    private DatagramDnsResponse configureResponseSRV(DatagramDnsResponse response,
                                                     String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for SRV record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        val counter = newRecordPredicate();
        getEurekaAppInstances(serviceName, datacenter)
                .filter(counter::test)
                .forEach(instanceInfo -> {
                    // add SRV record
                    response.addRecord(DnsSection.ANSWER, toDnsSRVRecord(questionName, instanceInfo));

                    // optionally add A/AAAA record
                    response.addRecord(DnsSection.ADDITIONAL, toDnsHostRecord(questionName, instanceInfo));
                });

        return response;
    }

    /**
     * Configures response for ANY question.
     *
     * @param response     response to be configured
     * @param questionName question name
     * @param serviceName  service name
     * @param datacenter   datacenter name
     * @return given {@code response}
     */
    private DatagramDnsResponse configureResponseANY(DatagramDnsResponse response,
                                                     String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for SRV record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        configureResponseA(response, questionName, serviceName, datacenter);
        configureResponseAAAA(response, questionName, serviceName, datacenter);
        configureResponseTXT(response, questionName, serviceName, datacenter);

        return response;
    }

    /**
     * Retrieves IP address from instance info.
     *
     * @param instanceInfo instance info
     * @return inet address
     * @throws IllegalArgumentException when instance info contains badly formatted ip address.
     */
    private InetAddress getInstanceIpAddress(InstanceInfo instanceInfo) {
        return InetAddresses.forString(instanceInfo.getIPAddr());
    }

    /**
     * Construct given instance url.
     *
     * @param instanceInfo instance info
     * @return instance url address
     */
    private String toInstanceUrlAddress(@NonNull InstanceInfo instanceInfo,
                                        @NonNull Function<InstanceInfo, String> hostnameFunction) {
        val isSecure = instanceInfo.isPortEnabled(PortType.SECURE);
        val port = getInstancePort(instanceInfo);
        val scheme = "http" + ((isSecure) ? "s" : "") + "://";
        val portStr = ((isSecure && port == 443) || (!isSecure && port == 80)) ? "" : ":" + port;
        val hostname = hostnameFunction.apply(instanceInfo);
        return scheme + hostname + portStr + "/";
    }

    /**
     * Returns stream of eureka instances for specified service name in given datacenter.
     *
     * @param serviceName service name
     * @param datacenter  datacenter name, use {@code "" / empty string} for default datacenter name.
     * @return stream of available instances with status {@code UP}
     */
    private Stream<InstanceInfo> getEurekaAppInstances(@NonNull String serviceName, @NonNull String datacenter) {
        if (serviceName.isEmpty()) {
            return Stream.empty();
        }

        val instances = getApplicationsForDatacenter(datacenter)
                .map(apps -> apps.getRegisteredApplications(serviceName))
                .map(Application::getInstances)
                .orElse(Collections.emptyList());

        return instances.stream()
                .filter(Objects::nonNull)
                .filter(e -> e.getStatus() == InstanceStatus.UP);
    }

    /**
     * Returns applications for given datacenter.
     *
     * @param datacenter datacenter name.
     * @return optional of application container.
     */
    private Optional<Applications> getApplicationsForDatacenter(@NonNull String datacenter) {
        if (datacenter.isEmpty()) {
            return Optional.ofNullable(eurekaClient.getApplications());
        } else {
            return Optional.ofNullable(eurekaClient.getApplicationsForARegion(datacenter));
        }
    }

    /**
     * Constructs basic DNS response, with status code {@link DnsResponseCode#NXDOMAIN}.
     *
     * @param query dns query
     * @return dns response.
     */
    private DatagramDnsResponse basicResponse(DatagramDnsQuery query) {
        val response = new DatagramDnsResponse(query.recipient(), query.sender(), query.id())
                .setCode(DnsResponseCode.NXDOMAIN);

        // add original question to response
        val question = query.recordAt(DnsSection.QUESTION);
        if (question != null) {
            response.addRecord(DnsSection.QUESTION, question);
        }

        return response;
    }

    /**
     * Tells whether specified dns question record is valid.
     *
     * @param question question
     * @return true/false
     */
    private boolean isValidQuestion(DnsRecord question) {
        if (question == null) {
            return false;
        }

        return isValidQuestionType(question.type()) &&
                isValidQuestionClass(question.dnsClass()) &&
                isValidQuestionName(question.name());
    }

    /**
     * Tells whether specified record name is valid.
     *
     * @param name dns record name
     * @return true/false
     */
    private boolean isValidQuestionName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        name = name.toLowerCase();

        return name.equals(config.getDomain() + ".") ||
                name.endsWith("." + config.getDomain() + ".");
    }

    /**
     * Tells whether specified record class is valid.
     *
     * @param dnsClass record dns class
     * @return true/false
     */
    private boolean isValidQuestionClass(int dnsClass) {
        return DnsRecord.CLASS_IN == dnsClass;
    }

    /**
     * Tells whether specified record type is valid.
     *
     * @param type record type
     * @return true/false
     */
    private boolean isValidQuestionType(DnsRecordType type) {
        return VALID_QUESTION_TYPES.contains(type);
    }

    /**
     * Encodes given instance to DNS SRV record payload.
     *
     * @param instanceInfo instance info
     * @return given address as SRV record payload written in byte buffer.
     * @see <a href="https://stackoverflow.com/questions/51449468/implementing-dns-message-name-compression-algorithm-in-python">DNS
     *         Message Compression algorithm</a>
     */
    private ByteBuf encodeRDataSRV(@NonNull InstanceInfo instanceInfo) {
        val buf = Unpooled.buffer();

        // priority
        buf.writeShort(1);

        // weight
        buf.writeShort(10);

        // port
        buf.writeShort(getInstancePort(instanceInfo));

        // target (hostname)
        return encodeDnsName(instanceInfo.getHostName(), buf);
    }

    /**
     * Encodes given address to DNS A/AAAA record payload.
     *
     * @param address address to encode
     * @return given address as A/AAAA record payload written in byte buffer.
     */
    private ByteBuf encodeRDataHostAddress(@NonNull InetAddress address) {
        return Unpooled.wrappedBuffer(address.getAddress());
    }

    /**
     * Encodes given string to DNS TXT record payload.
     *
     * @param str string to encode
     * @return given string as TXT record payload written in byte buffer.
     */
    private ByteBuf encodeRDataTXT(@NonNull String str) {
        val bytes = str.getBytes(US_ASCII);
        val maxBytes = Math.min(255, bytes.length);
        return Unpooled.buffer(maxBytes + 1)
                .writeByte(maxBytes)
                .writeBytes(bytes, 0, maxBytes);
    }

    /**
     * Encodes RFC1035 DNS name.
     *
     * @param name name to encode
     * @param buf  buffer where name will be written
     * @return given byte buffer.
     */
    private ByteBuf encodeDnsName(String name, ByteBuf buf) {
        if (".".equals(name)) {
            // Root domain
            buf.writeByte(0);
            return buf;
        }

        // dns name is series of labels, which are expressed in text form as commas.
        val labels = name.split("\\.");
        for (String label : labels) {

            // each label can be 63 chars long.
            val labelLen = label.length();
            if (labelLen > 63) {
                throw new IllegalArgumentException("Can't encode dns name '" + name + "'; length of label '" + label +
                        "' is too long: " + labelLen);
            }

            if (labelLen == 0) {
                // zero-length label means the end of the name.
                break;
            }

            buf.writeByte(labelLen);
            ByteBufUtil.writeAscii(buf, label);
        }

        buf.writeByte(0); // marks end of name field
        return buf;
    }

    /**
     * Encodes RDATA SOA record for given domain name.
     *
     * @param primaryNsHostname SOA primary name server (MNAME)
     * @param recipientAddr     A domain-name which specifies the mailbox of the person responsible for this zone
     *                          (RNAME).
     * @return byte buffer containing SOA record RDATA
     */
    private ByteBuf encodeRDataSOA(String primaryNsHostname, @NonNull String recipientAddr) {
        val buf = Unpooled.buffer();

//        MNAME
//        The <domain-name> of the name server that was the
//        original or primary source of data for this zone.
        encodeDnsName(primaryNsHostname, buf);

//        RNAME
//        A <domain-name> which specifies the mailbox of the
//        person responsible for this zone.
        encodeDnsName(recipientAddr, buf);

//        SERIAL
//        The unsigned 32 bit version number of the original copy
//        of the zone.  Zone transfers preserve this value.  This
//        value wraps and should be compared using sequence space
//        arithmetic.
        buf.writeInt(Math.abs((int) (System.currentTimeMillis() / 1000)));

//        REFRESH
//        A 32 bit time interval before the zone should be
//        refreshed.
        buf.writeInt(3600);

//        RETRY
//        A 32 bit time interval that should elapse before a
//        failed refresh should be retried.
        buf.writeInt(600);

//        EXPIRE
//        A 32 bit time value that specifies the upper limit on
//        the time interval that can elapse before the zone is no
//        longer authoritative.
        buf.writeInt(86400);

//        MINIMUM
//        The unsigned 32 bit minimum TTL field that should be
//        exported with any RR from this zone.
        buf.writeInt(0);
        return buf;
    }

    /**
     * Returns instance port.
     *
     * @param instanceInfo instance info
     * @return instance port based on {@link InstanceInfo#isPortEnabled(PortType)}
     */
    private int getInstancePort(InstanceInfo instanceInfo) {
        return (instanceInfo.isPortEnabled(PortType.SECURE)) ?
                instanceInfo.getSecurePort() : instanceInfo.getPort();
    }

    /**
     * Creates eureka DNS server NS record.
     *
     * @param questionName dns query question name
     * @return NS record
     */
    private DnsRecord createEurekaDnsServerNSRecord(String questionName) {
        return new DefaultDnsRawRecord(questionName, NS, config.getTtl(), encodeRDataNS());
    }

    /**
     * Creates eureka DNS server host record.
     *
     * @param serverAddr eureka dns server host address
     * @return A/AAAA record
     */
    private DnsRecord createEurekaDnsServerHostRecord(InetAddress serverAddr) {
        return toDnsHostRecord(nsHostname, serverAddr);
    }

    /**
     * Creates eureka DNS server SOA record.
     *
     * @param questionName dns query question name
     * @return SOA record
     */
    private DnsRecord createEurekaDnsServerSOARecord(String questionName) {
        return new DefaultDnsRawRecord(questionName, SOA, config.getTtl(),
                encodeRDataSOA(nsHostname, "hostmaster." + config.getDomain()));
    }

    /**
     * Creates A/AAAA host record.
     *
     * @param name record fully qualified domain name.
     * @param addr record address.
     * @return A/AAAA record
     */
    private DnsRecord toDnsHostRecord(@NonNull String name, @NonNull InetAddress addr) {
        val type = isIpv6Address(addr) ? AAAA : A;
        return new DefaultDnsRawRecord(name, type, config.getTtl(), encodeRDataHostAddress(addr));
    }

    /**
     * Creates A/AAAA host record.
     *
     * @param name         record fully qualified domain name.
     * @param instanceInfo eureka instance info.
     * @return A/AAAA record
     */
    private DnsRecord toDnsHostRecord(String name, InstanceInfo instanceInfo) {
        return toDnsHostRecord(name, InetAddresses.forString(instanceInfo.getIPAddr()));
    }

    /**
     * Converts given eureka instance info to DNS TXT record that contains instance base url address.
     *
     * @param questionName original dns question name
     * @param url          instance url
     * @return DNS TXT record.
     */
    private DnsRecord toDnsTXTRecord(String questionName, String url) {
        return new DefaultDnsRawRecord(questionName, TXT, config.getTtl(), encodeRDataTXT(url));
    }

    /**
     * Converts instance info to DNS SRV record.
     *
     * @param questionName original dns question name
     * @param instanceInfo instance url
     * @return DNS SRV record
     */
    private DnsRecord toDnsSRVRecord(String questionName, InstanceInfo instanceInfo) {
        return new DefaultDnsRawRecord(questionName, SRV, config.getTtl(), encodeRDataSRV(instanceInfo));
    }

    /**
     * Tells whether given inet address is IPv4 address.
     *
     * @param inetAddress inet address
     * @return true/false
     */
    private boolean isIpv4Address(InetAddress inetAddress) {
        return inetAddress instanceof Inet4Address;
    }

    /**
     * Tells whether given inet address is IPv6 address.
     *
     * @param inetAddress inet address
     * @return true/false
     */
    private boolean isIpv6Address(InetAddress inetAddress) {
        return inetAddress instanceof Inet6Address;
    }

    /**
     * Returns new stream record limit predicate.
     *
     * @return stream predicate.
     */
    private RecordCounter newRecordPredicate() {
        return new RecordCounter(config.getMaxResponses());
    }
}