package io.casehub.neocortex.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DomainRegistry {
    private final Map<String, DomainSupport> domains;

    public DomainRegistry(List<DomainSupport> registrations) {
        this.domains = registrations.stream()
            .collect(Collectors.toUnmodifiableMap(DomainSupport::domain, Function.identity()));
    }

    public Optional<DomainSupport> lookup(String domain) {
        return Optional.ofNullable(domains.get(domain));
    }

    public Map<String, DomainSupport> allDomains() {
        return domains;
    }
}
