package app.kitappla.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class SsrfValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost",
            "http://localhost:8080/h2",
            "http://127.0.0.1",
            "http://127.0.0.1:8080",
            "http://127.0.1.1",
            "http://127.255.255.255",
            "http://[::1]",
            "http://[::1]:8080/secret",
            "http://0.0.0.0",
            "http://10.0.0.1",
            "http://10.255.255.255",
            "http://172.16.0.1",
            "http://172.31.255.255",
            "http://192.168.0.1",
            "http://192.168.1.254",
            "http://169.254.169.254",
            "http://169.254.169.254/latest/meta-data/",
            "http://100.64.0.1",
            "http://192.0.2.1",
            "http://198.51.100.1",
            "http://203.0.113.1",
            "http://metadata.google.internal",
            "http://instance-data",
            "http://myhost.local",
            "http://myhost.internal",
            "http://myhost.lan",
            "http://8.8.8.8:22/",
            "http://8.8.8.8:25/",
            "http://8.8.8.8:6379/",
            "http://8.8.8.8:3306/",
            "file:///etc/passwd",
            "file:///C:/Windows/win.ini",
            "ftp://example.com/kitap.jpg",
            "gopher://127.0.0.1:70/",
            "javascript:alert(1)",
            "data:text/html,test"
    })
    void guvensizAdreslerReddedilir(String url) {
        assertThat(SsrfValidator.isSafeUrl(url)).isFalse();
    }

    @Test
    void bosVeGecersizUrlReddedilir() {
        assertThat(SsrfValidator.isSafeUrl(null)).isFalse();
        assertThat(SsrfValidator.isSafeUrl("")).isFalse();
        assertThat(SsrfValidator.isSafeUrl("   ")).isFalse();
        assertThat(SsrfValidator.isSafeUrl("not-a-url")).isFalse();
    }

    @Test
    void guvenliGenelIpDogrulanir() throws Exception {
        InetAddress googleDns = InetAddress.getByName("8.8.8.8");
        InetAddress cloudflareDns = InetAddress.getByName("1.1.1.1");

        assertThat(SsrfValidator.isSafeIp(googleDns)).isTrue();
        assertThat(SsrfValidator.isSafeIp(cloudflareDns)).isTrue();
    }

    @Test
    void guvensizIpAdresleriDogrudanReddedilir() throws Exception {
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("10.1.2.3"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("172.20.0.1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("192.168.1.1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("::1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("fe80::1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("fc00::1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("fd12:3456::1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("::ffff:127.0.0.1"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("::ffff:169.254.169.254"))).isFalse();
        assertThat(SsrfValidator.isSafeIp(InetAddress.getByName("::ffff:10.0.0.1"))).isFalse();
    }
}
