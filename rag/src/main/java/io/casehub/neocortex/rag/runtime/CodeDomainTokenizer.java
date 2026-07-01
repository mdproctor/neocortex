package io.casehub.neocortex.rag.runtime;

import java.util.ArrayList;
import java.util.List;

final class CodeDomainTokenizer {

    private CodeDomainTokenizer() {}

    static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return List.of();

        List<String> tokens = new ArrayList<>();
        // Step 1: split on non-alphanumeric boundaries
        String[] segments = text.split("[^a-zA-Z0-9]+");
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            List<String> parts = splitCamelCase(segment);
            for (String part : parts) {
                String lower = part.toLowerCase();
                if (!lower.isEmpty()) tokens.add(lower);
            }
            // Compound preservation: keep the full segment if it was split
            if (parts.size() > 1) {
                tokens.add(segment.toLowerCase());
            }
        }
        return List.copyOf(tokens);
    }

    private static List<String> splitCamelCase(String s) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < s.length(); i++) {
            boolean split = false;
            char prev = s.charAt(i - 1);
            char curr = s.charAt(i);
            // lowercase followed by uppercase: chatModel -> chat|Model
            if (Character.isLowerCase(prev) && Character.isUpperCase(curr)) {
                split = true;
            }
            // digit-letter or letter-digit boundary: BM25 -> BM|25
            if (Character.isDigit(prev) != Character.isDigit(curr)) {
                split = true;
            }
            // uppercase followed by uppercase+lowercase: HTTPClient -> HTTP|Client
            if (i + 1 < s.length() && Character.isUpperCase(prev)
                    && Character.isUpperCase(curr) && Character.isLowerCase(s.charAt(i + 1))) {
                split = true;
            }
            if (split) {
                parts.add(s.substring(start, i));
                start = i;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }
}
