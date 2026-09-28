package app.kitappla.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF (Server-Side Request Forgery) koruma doğrulayıcısı.
 * <p>
 * Loopback, özel ağlar (RFC 1918), link-local / bulut metadata (169.254.169.254),
 * IPv6 karşılıkları ve dahili host isimlerini engeller.
 * </p>
 */
public final class SsrfValidator {

    private static final Logger log = LoggerFactory.getLogger(SsrfValidator.class);

    private static final Set<String> BLOCKED_HOST_SUFFIXES = Set.of(
            "localhost",
            ".localhost",
            ".local",
            ".internal",
            ".lan",
            ".home.arpa",
            "metadata.google.internal",
            "instance-data"
    );

    private SsrfValidator() {
    }

    /**
     * Verilen URL metninin güvenli bir genel internet adresi olup olmadığını doğrular.
     */
    public static boolean isSafeUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return isSafeUri(uri);
        } catch (Exception e) {
            log.debug("Geçersiz URL formatı: {}", url, e);
            return false;
        }
    }

    /**
     * Verilen URI'nin şemasını, host'unu ve DNS çözümlemesi sonrasındaki tüm IP adreslerini doğrular.
     */
    public static boolean isSafeUri(URI uri) {
        if (uri == null) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        String hostLower = host.toLowerCase(Locale.ROOT);
        for (String suffix : BLOCKED_HOST_SUFFIXES) {
            if (hostLower.equals(suffix) || hostLower.endsWith(suffix.startsWith(".") ? suffix : "." + suffix)) {
                log.warn("SSRF engellendi (yasaklı host): {}", host);
                return false;
            }
        }

        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && port != 8080 && port != 8443) {
            log.warn("SSRF engellendi (yasaklı port {}): {}", port, uri);
            return false;
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                return false;
            }
            for (InetAddress addr : addresses) {
                if (!isSafeIp(addr)) {
                    log.warn("SSRF engellendi (güvensiz IP {}): {}", addr.getHostAddress(), host);
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            log.debug("Host DNS çözülemedi: {}", host, e);
            return false;
        } catch (Exception e) {
            log.warn("Host doğrulama hatası: {}", host, e);
            return false;
        }
    }

    /**
     * IP adresinin genel internete ait olup olmadığını, dahili/özel/rezerve olmadığını doğrular.
     */
    public static boolean isSafeIp(InetAddress address) {
        if (address == null) {
            return false;
        }

        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] b = address.getAddress();
        if (b.length == 4) {
            return isSafeIpv4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF);
        } else if (b.length == 16) {
            return isSafeIpv6(b);
        }

        return false;
    }

    private static boolean isSafeIpv4(int b0, int b1, int b2, int b3) {
        // 0.0.0.0/8 (Mevcut ağ)
        if (b0 == 0) return false;

        // 10.0.0.0/8 (Özel ağ RFC 1918)
        if (b0 == 10) return false;

        // 100.64.0.0/10 (CGNAT / Shared Address Space RFC 6598)
        if (b0 == 100 && (b1 & 0xC0) == 64) return false;

        // 127.0.0.0/8 (Loopback)
        if (b0 == 127) return false;

        // 169.254.0.0/16 (Link-Local / Bulut Metadata RFC 3927)
        if (b0 == 169 && b1 == 254) return false;

        // 172.16.0.0/12 (Özel ağ RFC 1918)
        if (b0 == 172 && (b1 & 0xF0) == 16) return false;

        // 192.0.0.0/24 (IETF Protocol Assignments RFC 6890)
        if (b0 == 192 && b1 == 0 && b2 == 0) return false;

        // 192.0.2.0/24 (TEST-NET-1 RFC 5737)
        if (b0 == 192 && b1 == 0 && b2 == 2) return false;

        // 192.88.99.0/24 (6to4 Relay Anycast RFC 7526)
        if (b0 == 192 && b1 == 88 && b2 == 99) return false;

        // 192.168.0.0/16 (Özel ağ RFC 1918)
        if (b0 == 192 && b1 == 168) return false;

        // 198.18.0.0/15 (Benchmarking RFC 2544)
        if (b0 == 198 && (b1 & 0xFE) == 18) return false;

        // 198.51.100.0/24 (TEST-NET-2 RFC 5737)
        if (b0 == 198 && b1 == 51 && b2 == 100) return false;

        // 203.0.113.0/24 (TEST-NET-3 RFC 5737)
        if (b0 == 203 && b1 == 0 && b2 == 113) return false;

        // 224.0.0.0/4 (Multicast) ve 240.0.0.0/4 (Rezerve & Broadcast)
        if (b0 >= 224) return false;

        return true;
    }

    private static boolean isSafeIpv6(byte[] b) {
        // ::/128 (Unspecified)
        boolean allZero = true;
        for (byte x : b) {
            if (x != 0) {
                allZero = false;
                break;
            }
        }
        if (allZero) return false;

        // ::1/128 (Loopback)
        boolean isLoopback = true;
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) {
                isLoopback = false;
                break;
            }
        }
        if (isLoopback && b[15] == 1) return false;

        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;

        // ff00::/8 (Multicast)
        if (b0 == 0xFF) return false;

        // fe80::/10 (Link-Local)
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) return false;

        // fec0::/10 (Site-Local)
        if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) return false;

        // fc00::/7 (Unique Local Address - ULA)
        if ((b0 & 0xFE) == 0xFC) return false;

        // 2001:db8::/32 (Dokümantasyon)
        if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) return false;

        // 2001:10::/28 & 2001:20::/28 (ORCHID)
        if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xF0) == 0x10) return false;
        if (b0 == 0x20 && b1 == 0x01 && (b[2] & 0xF0) == 0x20) return false;

        // 100::/64 (Discard)
        if (b0 == 0x01 && b1 == 0x00 && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0) return false;

        // IPv4-mapped IPv6 (::ffff:0:0/96)
        boolean isIpv4Mapped = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                isIpv4Mapped = false;
                break;
            }
        }
        if (isIpv4Mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            return isSafeIpv4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF, b[15] & 0xFF);
        }

        // IPv4-compatible IPv6 (::0:0/96)
        boolean isIpv4Compat = true;
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) {
                isIpv4Compat = false;
                break;
            }
        }
        if (isIpv4Compat) {
            return isSafeIpv4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF, b[15] & 0xFF);
        }

        // 6to4 (2002::/16) - bayt 2-5 IPv4 adresidir
        if (b0 == 0x20 && b1 == 0x02) {
            return isSafeIpv4(b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF, b[5] & 0xFF);
        }

        // NAT64 (64:ff9b::/96) - bayt 12-15 IPv4 adresidir
        if (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            boolean middleZero = true;
            for (int i = 4; i < 12; i++) {
                if (b[i] != 0) {
                    middleZero = false;
                    break;
                }
            }
            if (middleZero) {
                return isSafeIpv4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF, b[15] & 0xFF);
            }
        }

        return true;
    }
}
