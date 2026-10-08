package io.casehub.neocortex.knowledge.resolution;

import io.casehub.connectors.Page;
import io.casehub.connectors.PageRequest;
import io.casehub.connectors.contacts.model.Contact;
import io.casehub.connectors.contacts.spi.ContactsPlatform;
import io.casehub.neocortex.knowledge.CachedEntity;
import io.casehub.neocortex.knowledge.KnowledgeQuery;
import io.casehub.neocortex.knowledge.PipelinePage;
import io.casehub.neocortex.knowledge.PipelinePageRequest;
import io.casehub.neocortex.knowledge.SearchableProvider;
import io.casehub.neocortex.knowledge.cache.CacheEntityIdGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ContactsSearchableProvider implements SearchableProvider {

    private static final String DOMAIN = "contacts";
    private final ContactsPlatform platform;
    private final Duration entityTtl;

    public ContactsSearchableProvider(ContactsPlatform platform, Duration entityTtl) {
        this.platform = Objects.requireNonNull(platform);
        this.entityTtl = Objects.requireNonNull(entityTtl);
    }

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String id() { return platform.id(); }

    @Override
    public PipelinePage<CachedEntity> search(KnowledgeQuery query, PipelinePageRequest page) {
        var read = platform.contactRead("pipeline");
        PageRequest connectorPage = new PageRequest(page.cursor(), page.pageSize());

        Page<Contact> result = switch (query) {
            case KnowledgeQuery.TextSearch t -> read.search(t.query(), connectorPage);
            default -> throw new UnsupportedOperationException(
                "Unsupported query type for contacts domain: " + query.getClass().getName());
        };

        Instant now = Instant.now();
        var entities = result.items().stream().map(c -> toEntity(c, now)).toList();
        return new PipelinePage<>(entities, result.nextCursor(), result.hasMore());
    }

    @Override
    public boolean supports(KnowledgeQuery query) {
        return query instanceof KnowledgeQuery.TextSearch;
    }

    private CachedEntity toEntity(Contact contact, Instant now) {
        String entityId = CacheEntityIdGenerator.generate(platform.id(), contact.id());
        String name = contact.name() != null && contact.name().displayName() != null
            ? contact.name().displayName()
            : contact.name() != null
                ? ((contact.name().givenName() != null ? contact.name().givenName() : "")
                    + " " + (contact.name().familyName() != null ? contact.name().familyName() : "")).strip()
                : contact.id();

        Map<String, String> props = new HashMap<>();
        if (contact.company() != null) props.put("company", contact.company());
        if (contact.jobTitle() != null) props.put("jobTitle", contact.jobTitle());
        if (contact.emails() != null && !contact.emails().isEmpty()) {
            props.put("email", contact.emails().get(0).value());
        }
        if (contact.phones() != null && !contact.phones().isEmpty()) {
            props.put("phone", contact.phones().get(0).value());
        }
        if (contact.photoUrl() != null) props.put("photoUrl", contact.photoUrl());

        return new CachedEntity(entityId, name, null, null,
            platform.id(), contact.id(), props, now, null, now.plus(entityTtl),
            Set.of(), false, DOMAIN);
    }
}
