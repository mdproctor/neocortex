package io.casehub.neocortex.corpus.zip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Manages the {@code chain.json} manifest that tracks the ordered chain of
 * ZIP archives in a corpus. Each archive is represented by a {@link ChainEntry}.
 *
 * <p>JSON serialization uses {@link StringBuilder} (no library dependency).
 * JSON deserialization uses a simple recursive-descent parser over the raw string.
 */
public final class ChainManifest {

    private final String corpusName;
    private final List<ChainEntry> entries;

    private ChainManifest(String corpusName, List<ChainEntry> entries) {
        this.corpusName = corpusName;
        this.entries = entries;
    }

    // ── factory methods ──────────────────────────────────────────────────

    public static ChainManifest create(String corpusName) {
        return new ChainManifest(corpusName, new ArrayList<>());
    }

    public static ChainManifest load(Path chainJsonPath) throws IOException {
        String json = Files.readString(chainJsonPath);
        return parse(json);
    }

    // ── mutators ─────────────────────────────────────────────────────────

    public void addEntry(ChainEntry entry) {
        entries.add(entry);
    }

    public void closeEntry(String uuid, String contentHash, int entryCount) {
        for (int i = 0; i < entries.size(); i++) {
            ChainEntry e = entries.get(i);
            if (e.uuid().equals(uuid)) {
                entries.set(i, new ChainEntry(
                        e.uuid(), e.file(), e.sequence(), "closed", e.predecessor(),
                        entryCount, e.cumulativeEntryCount(), contentHash,
                        e.domains(), e.earliest(), e.latest(), e.replacedBy()));
                return;
            }
        }
    }

    public void retireEntry(String oldUuid, String newUuid) {
        for (int i = 0; i < entries.size(); i++) {
            ChainEntry e = entries.get(i);
            if (e.uuid().equals(oldUuid)) {
                entries.set(i, new ChainEntry(
                        e.uuid(), e.file(), e.sequence(), "compacted", e.predecessor(),
                        e.entryCount(), e.cumulativeEntryCount(), e.contentHash(),
                        e.domains(), e.earliest(), e.latest(), newUuid));
                return;
            }
        }
    }

    // ── queries ──────────────────────────────────────────────────────────

    public Optional<ChainEntry> activeEntry() {
        return entries.stream()
                .filter(e -> "active".equals(e.status()))
                .findFirst();
    }

    public List<ChainEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public String corpusName() {
        return corpusName;
    }

    public int schemaVersion() {
        return 1;
    }

    // ── persistence ──────────────────────────────────────────────────────

    public void save(Path chainJsonPath) throws IOException {
        String json = toJson();
        Path parent = chainJsonPath.getParent();
        Path tmp = Files.createTempFile(parent, "chain-", ".tmp");
        try {
            Files.writeString(tmp, json);
            Files.move(tmp, chainJsonPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
    }

    // ── JSON writer ──────────────────────────────────────────────────────

    private String toJson() {
        var sb = new StringBuilder(512);
        sb.append("{\n");
        sb.append("  \"corpus\": ").append(jsonString(corpusName)).append(",\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"chain\": [");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\n');
            writeEntry(sb, entries.get(i));
        }
        if (!entries.isEmpty()) sb.append('\n');
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void writeEntry(StringBuilder sb, ChainEntry e) {
        sb.append("    {\n");
        sb.append("      \"uuid\": ").append(jsonString(e.uuid())).append(",\n");
        sb.append("      \"file\": ").append(jsonString(e.file())).append(",\n");
        sb.append("      \"sequence\": ").append(e.sequence()).append(",\n");
        sb.append("      \"status\": ").append(jsonString(e.status())).append(",\n");
        sb.append("      \"predecessor\": ").append(jsonStringOrNull(e.predecessor())).append(",\n");
        sb.append("      \"entryCount\": ").append(e.entryCount()).append(",\n");
        sb.append("      \"cumulativeEntryCount\": ").append(e.cumulativeEntryCount()).append(",\n");
        sb.append("      \"contentHash\": ").append(jsonStringOrNull(e.contentHash())).append(",\n");

        // domains
        sb.append("      \"domains\": ");
        if (e.domains().isEmpty()) {
            sb.append("{}");
        } else {
            sb.append("{");
            boolean first = true;
            for (var kv : e.domains().entrySet()) {
                if (!first) sb.append(',');
                sb.append(' ').append(jsonString(kv.getKey())).append(": ").append(kv.getValue());
                first = false;
            }
            sb.append(" }");
        }
        sb.append(",\n");

        // dateRange
        sb.append("      \"dateRange\": ");
        if (e.earliest() == null && e.latest() == null) {
            sb.append("null");
        } else {
            sb.append("{ \"earliest\": ").append(jsonStringOrNull(str(e.earliest())));
            sb.append(", \"latest\": ").append(jsonStringOrNull(str(e.latest())));
            sb.append(" }");
        }
        sb.append(",\n");

        sb.append("      \"replacedBy\": ").append(jsonStringOrNull(e.replacedBy())).append('\n');
        sb.append("    }");
    }

    private static String jsonString(String v) {
        return "\"" + escapeJson(v) + "\"";
    }

    private static String jsonStringOrNull(String v) {
        return v == null ? "null" : jsonString(v);
    }

    private static String escapeJson(String v) {
        // Minimal escaping for the fixed schema values we deal with
        return v.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }

    // ── JSON parser (simple recursive-descent) ───────────────────────────

    private static ChainManifest parse(String json) {
        var parser = new JsonParser(json);
        return parser.parseManifest();
    }

    /**
     * Minimal recursive-descent JSON parser for the fixed chain.json schema.
     * Handles: objects, arrays, strings, integers, null, booleans.
     */
    private static final class JsonParser {
        private final String src;
        private int pos;

        JsonParser(String src) {
            this.src = src;
            this.pos = 0;
        }

        ChainManifest parseManifest() {
            skipWhitespace();
            expect('{');

            String corpus = null;
            List<ChainEntry> entries = List.of();

            while (true) {
                skipWhitespace();
                if (peek() == '}') { advance(); break; }

                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                switch (key) {
                    case "corpus" -> corpus = readString();
                    case "schemaVersion" -> readInt(); // consume, always 1
                    case "chain" -> entries = readChain();
                    default -> skipValue();
                }

                skipWhitespace();
                if (peek() == ',') advance();
            }

            return new ChainManifest(corpus, new ArrayList<>(entries));
        }

        private List<ChainEntry> readChain() {
            var list = new ArrayList<ChainEntry>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') { advance(); return list; }
            while (true) {
                skipWhitespace();
                list.add(readChainEntry());
                skipWhitespace();
                if (peek() == ',') { advance(); continue; }
                break;
            }
            skipWhitespace();
            expect(']');
            return list;
        }

        private ChainEntry readChainEntry() {
            expect('{');

            String uuid = null, file = null, status = null, predecessor = null;
            String contentHash = null, replacedBy = null;
            int sequence = 0, entryCount = 0, cumulativeEntryCount = 0;
            Map<String, Integer> domains = null;
            LocalDate earliest = null, latest = null;

            while (true) {
                skipWhitespace();
                if (peek() == '}') { advance(); break; }

                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();

                switch (key) {
                    case "uuid" -> uuid = readNullableString();
                    case "file" -> file = readNullableString();
                    case "sequence" -> sequence = readInt();
                    case "status" -> status = readNullableString();
                    case "predecessor" -> predecessor = readNullableString();
                    case "entryCount" -> entryCount = readInt();
                    case "cumulativeEntryCount" -> cumulativeEntryCount = readInt();
                    case "contentHash" -> contentHash = readNullableString();
                    case "domains" -> domains = readDomains();
                    case "dateRange" -> {
                        var range = readDateRange();
                        earliest = range[0];
                        latest = range[1];
                    }
                    case "replacedBy" -> replacedBy = readNullableString();
                    default -> skipValue();
                }

                skipWhitespace();
                if (peek() == ',') advance();
            }

            return new ChainEntry(uuid, file, sequence, status, predecessor,
                    entryCount, cumulativeEntryCount, contentHash,
                    domains, earliest, latest, replacedBy);
        }

        private Map<String, Integer> readDomains() {
            if (tryNull()) return null;
            expect('{');
            skipWhitespace();
            if (peek() == '}') { advance(); return Map.of(); }
            var map = new LinkedHashMap<String, Integer>();
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                int val = readInt();
                map.put(key, val);
                skipWhitespace();
                if (peek() == ',') { advance(); continue; }
                break;
            }
            skipWhitespace();
            expect('}');
            return map;
        }

        private LocalDate[] readDateRange() {
            if (tryNull()) return new LocalDate[]{null, null};
            expect('{');
            LocalDate earliest = null, latest = null;
            while (true) {
                skipWhitespace();
                if (peek() == '}') { advance(); break; }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String val = readNullableString();
                switch (key) {
                    case "earliest" -> earliest = val == null ? null : LocalDate.parse(val);
                    case "latest" -> latest = val == null ? null : LocalDate.parse(val);
                    default -> skipValue();
                }
                skipWhitespace();
                if (peek() == ',') advance();
            }
            return new LocalDate[]{earliest, latest};
        }

        // ── primitives ───────────────────────────────────────────────────

        private String readString() {
            skipWhitespace();
            expect('"');
            var sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case '/' -> sb.append('/');
                        case 'u' -> {
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> { sb.append('\\'); sb.append(esc); }
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("Unterminated string at position " + pos);
        }

        private String readNullableString() {
            skipWhitespace();
            if (tryNull()) return null;
            return readString();
        }

        private int readInt() {
            skipWhitespace();
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            return Integer.parseInt(src.substring(start, pos));
        }

        private boolean tryNull() {
            if (pos + 4 <= src.length() && src.startsWith("null", pos)) {
                // Make sure the next char is not alphanumeric (to avoid matching "nullable" etc.)
                if (pos + 4 >= src.length() || !Character.isLetterOrDigit(src.charAt(pos + 4))) {
                    pos += 4;
                    return true;
                }
            }
            return false;
        }

        private void skipValue() {
            skipWhitespace();
            char c = peek();
            if (c == '"') { readString(); return; }
            if (c == '{') { skipObject(); return; }
            if (c == '[') { skipArray(); return; }
            if (c == 'n') { pos += 4; return; } // null
            if (c == 't') { pos += 4; return; } // true
            if (c == 'f') { pos += 5; return; } // false
            // number
            if (c == '-') pos++;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) pos++;
        }

        private void skipObject() {
            expect('{');
            int depth = 1;
            boolean inString = false;
            while (pos < src.length() && depth > 0) {
                char c = src.charAt(pos++);
                if (inString) {
                    if (c == '\\') pos++;
                    else if (c == '"') inString = false;
                } else {
                    if (c == '"') inString = true;
                    else if (c == '{') depth++;
                    else if (c == '}') depth--;
                }
            }
        }

        private void skipArray() {
            expect('[');
            int depth = 1;
            boolean inString = false;
            while (pos < src.length() && depth > 0) {
                char c = src.charAt(pos++);
                if (inString) {
                    if (c == '\\') pos++;
                    else if (c == '"') inString = false;
                } else {
                    if (c == '"') inString = true;
                    else if (c == '[') depth++;
                    else if (c == ']') depth--;
                }
            }
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void advance() {
            pos++;
        }

        private void expect(char c) {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalStateException(
                        "Expected '" + c + "' at position " + pos +
                        " but found '" + (pos < src.length() ? src.charAt(pos) : "EOF") + "'");
            }
            pos++;
        }
    }
}
