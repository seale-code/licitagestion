package pe.licitagestion.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressResolverTest {
    @Test void ignoresForwardedAddressFromUntrustedClient() {
        var resolver = new ClientAddressResolver("10.0.0.10");
        assertThat(resolver.resolve("198.51.100.22", "203.0.113.7")).isEqualTo("198.51.100.22");
    }

    @Test void usesLastForwardedAddressOnlyForTrustedProxy() {
        var resolver = new ClientAddressResolver("10.0.0.10");
        assertThat(resolver.resolve("10.0.0.10", "203.0.113.8, 198.51.100.22")).isEqualTo("198.51.100.22");
    }

    @Test void ignoresMalformedForwardedAddress() {
        var resolver = new ClientAddressResolver("10.0.0.10");
        assertThat(resolver.resolve("10.0.0.10", "attacker.example")).isEqualTo("10.0.0.10");
        assertThat(resolver.resolve("10.0.0.10", "010.0.0.1")).isEqualTo("10.0.0.10");
    }
}

