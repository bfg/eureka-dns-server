package com.github.bfg.eureka.dns;

import com.google.common.net.InetAddresses;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import io.netty.buffer.ByteBuf;
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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.netty.handler.codec.dns.DnsRecordType.*;

/**
 * DNS query netty channel handler.
 *
 * @see <a href="https://tools.ietf.org/html/rfc2782">RFC 2782 :: A DNS RR for specifying the location of services
 *         (DNS SRV)</a>
 * @see <a href="https://www.consul.io/docs/agent/dns.html">Consul DNS interface</a>
 * @see <a href="https://www.haproxy.com/blog/dns-service-discovery-haproxy/">Haproxy service discovery</a>
 */
@Slf4j
@ChannelHandler.Sharable
final class DnsQueryHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {

    private final DnsServerConfig config;
    private final EurekaClient eurekaClient;

    // all question names should end with the following suffix
    private final String requiredQuestionNameSuffix;

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
        this.requiredQuestionNameSuffix = "." + config.getDomain() + ".";

        this.withoutDcPattern =
                Pattern.compile("\\.?([\\w\\-]+)\\.(?:service|node|connect)\\." + config.getDomain() + "\\.?$");
        this.withDcPattern =
                Pattern.compile("\\.?([\\w\\-]+)\\.(?:service|node|connect)\\.([\\w\\-]+)\\." + config.getDomain() + "\\.?$");
    }

    @Override
    @SneakyThrows
    protected void channelRead0(@NonNull ChannelHandlerContext ctx, @NonNull DatagramDnsQuery msg) {
        log.trace("received dns query: {}", msg);

        val response = respondToDnsQuery(msg);
        log.trace("sending DNS response: {}", response);

//        val response = new DatagramDnsResponse(msg.recipient(), msg.sender(), msg.id());
//
//        val v4Addr = InetAddress.getByName("192.168.3.2");
//        val v6Addr = InetAddress.getByName("2607:f8b0:400a:800::2004");
//
//        response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(q.name(), A, 1, encodeRData(A, v4Addr)));
//        response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(q.name(), AAAA, 2, encodeRData(AAAA, v6Addr)));
//        response.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord(q.name(), TXT, 3, encodeRData(TXT, "freeform foo bar: ČĆŽŠĐ čćžšđ")));

        ctx.writeAndFlush(response);
    }

    /**
     * Retrieves datacenter name from DNS query name.
     *
     * @param name dns query name
     * @return datacenter on success, otherwise empty string
     */
    protected String getDatacenter(@NonNull String name) {
        name = name.toLowerCase().trim();

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

    protected DatagramDnsResponse respondToDnsQuery(@NonNull DatagramDnsQuery msg) {
        val question = msg.recordAt(DnsSection.QUESTION);

        // create response instance.
        val response = basicResponse(msg);

        if (!isValidQuestion(question)) {
            return response.setCode(DnsResponseCode.REFUSED);
        }

        // we absolutely need service name
        val questionName = question.name();
        val serviceName = getServiceName(questionName);
        if (serviceName.isEmpty()) {
            return response.setCode(DnsResponseCode.BADNAME);
        }

        // datacenter may be in question as well.
        val datacenter = getDatacenter(questionName);

        val type = question.type();
        return doConfigureResponse(response, type, questionName, serviceName, datacenter);
    }

    private DatagramDnsResponse doConfigureResponse(@NonNull DatagramDnsResponse response,
                                                    @NonNull DnsRecordType type,
                                                    String questionName, String serviceName, String datacenter) {
        if (type.equals(A)) {
            return finishResponse(addARecords(response, questionName, serviceName, datacenter));
        } else if (type.equals(AAAA)) {
            return finishResponse(addAAAARecords(response, questionName, serviceName, datacenter));
        } else if (type.equals(TXT)) {
            return finishResponse(addTXTRecords(response, questionName, serviceName, datacenter));
        } else if (type.equals(SRV)) {
            return finishResponse(addSRVRecords(response, questionName, serviceName, datacenter));
        } else if (type.equals(ANY)) {
            return finishResponse(addANYRecords(response, questionName, serviceName, datacenter));
        }

        throw new IllegalArgumentException("Don't know how to create DNS response to question: " + questionName);
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

    private DatagramDnsResponse addARecords(DatagramDnsResponse response,
                                            String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for A record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        getEurekaAppInstances(serviceName, datacenter)
                .map(this::getInstanceIpAddress)
                .filter(this::isIpv4Address)
                .map(addr -> new DefaultDnsRawRecord(questionName, A, config.getTtl(), encodeRDataA(addr)))
                .forEach(record -> response.addRecord(DnsSection.ANSWER, record));

        return response;
    }

    private DatagramDnsResponse addAAAARecords(DatagramDnsResponse response,
                                               String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for AAAA record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        getEurekaAppInstances(serviceName, datacenter)
                .map(this::getInstanceIpAddress)
                .filter(this::isIpv6Address)
                .map(addr -> new DefaultDnsRawRecord(questionName, AAAA, config.getTtl(), encodeRDataA(addr)))
                .forEach(record -> response.addRecord(DnsSection.ANSWER, record));

        return response;
    }

    private DatagramDnsResponse addTXTRecords(DatagramDnsResponse response,
                                              String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for TXT record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);
        return response;
    }

    private DatagramDnsResponse addSRVRecords(DatagramDnsResponse response,
                                              String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for SRV record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);
        return response;
    }

    private DatagramDnsResponse addANYRecords(DatagramDnsResponse response,
                                              String questionName, String serviceName, String datacenter) {
        log.debug("{} asked for SRV record {}: service={}, datacenter={}",
                response.recipient(), questionName, serviceName, datacenter);

        addARecords(response, questionName, serviceName, datacenter);
        addAAAARecords(response, questionName, serviceName, datacenter);
        addTXTRecords(response, questionName, serviceName, datacenter);

        return response;
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
    private Optional<Applications> getApplicationsForDatacenter(String datacenter) {
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
        return name.endsWith(requiredQuestionNameSuffix);
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
        if (type == null) {
            return false;
        }

        return type.equals(A) || type.equals(AAAA) || type.equals(ANY) || type.equals(TXT) || type.equals(SRV);
    }

    /**
     * Encodes given instance to DNS SRV record payload.
     *
     * @param address address to encode
     * @return given address as SRV record payload written in byte buffer.
     */
    private ByteBuf encodeRDataSRV(Object content) {
        return null;
    }

    /**
     * Encodes given address to DNS A/AAAA record payload.
     *
     * @param address address to encode
     * @return given address as A/AAAA record payload written in byte buffer.
     */
    private ByteBuf encodeRDataA(@NonNull InetAddress address) {
        return Unpooled.wrappedBuffer(address.getAddress());
    }

    /**
     * Encodes given string to DNS TXT record payload.
     *
     * @param str string to encode
     * @return given string as TXT record payload written in byte buffer.
     */
    private ByteBuf encodeRDataTXT(@NonNull String str) {
        val bytes = str.getBytes(StandardCharsets.US_ASCII);
        val maxBytes = Math.max(255, bytes.length);
        return Unpooled.buffer(maxBytes + 1)
                .writeByte(maxBytes)
                .writeBytes(bytes, 0, maxBytes);
    }
}