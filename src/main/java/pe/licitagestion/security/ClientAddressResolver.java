package pe.licitagestion.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a client address without trusting headers supplied by arbitrary clients. */
@Component
public class ClientAddressResolver {
    private final Set<String> trustedProxies;

    public ClientAddressResolver(@Value("${app.security.trusted-proxies:}") String configuredProxies) {
        trustedProxies = Arrays.stream(configuredProxies.split(","))
            .map(ClientAddressResolver::canonicalAddress)
            .filter(address -> !address.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(String remoteAddress, String forwardedFor) {
        String remote = canonicalAddress(remoteAddress);
        if (remote.isEmpty()) remote = "unknown";
        if (!trustedProxies.contains(remote) || forwardedFor == null || forwardedFor.isBlank()) return remote;

        String[] chain = forwardedFor.split(",");
        String forwarded = canonicalAddress(chain[chain.length - 1]);
        return forwarded.isEmpty() ? remote : forwarded;
    }

    private static String canonicalAddress(String candidate) {
        if (candidate == null) return "";
        String value = candidate.trim();
        try {
            if (value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                for (String octet : value.split("\\.")) {
                    if (octet.length() > 1 && octet.startsWith("0")) return "";
                    if (Integer.parseInt(octet) > 255) return "";
                }
                return InetAddress.getByName(value).getHostAddress();
            }
            if (value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+")) {
                InetAddress parsed = InetAddress.getByName(value);
                return parsed instanceof Inet6Address ? parsed.getHostAddress() : "";
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }
}

