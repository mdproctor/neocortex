package io.casehub.neocortex.rag.runtime;

import io.casehub.neocortex.rag.ExtractionResult;
import io.casehub.neocortex.rag.MetadataExtractor;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@DefaultBean
@ApplicationScoped
public class YamlFrontmatterExtractor implements MetadataExtractor {

    @Override
    public ExtractionResult extract(String path, byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return new ExtractionResult("", Map.of());
        }

        if (!text.startsWith("---")) {
            return new ExtractionResult(text, Map.of());
        }

        int closingIndex = text.indexOf("\n---", 3);
        if (closingIndex < 0) {
            return new ExtractionResult(text, Map.of());
        }

        String frontmatterBlock = text.substring(4, closingIndex).trim();
        String body = text.substring(closingIndex + 4).trim();

        Map<String, String> metadata = parseFrontmatter(frontmatterBlock);
        return new ExtractionResult(body, metadata);
    }

    private Map<String, String> parseFrontmatter(String block) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int colonPos = line.indexOf(':');
            if (colonPos <= 0) continue;
            String key = line.substring(0, colonPos).trim();
            String value = line.substring(colonPos + 1).trim();
            value = stripQuotes(value);
            metadata.put(key, value);
        }
        return metadata;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2) {
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
