package io.casehub.rag.tika;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import io.casehub.rag.ChunkInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TikaDocumentParser {

    private final int chunkSize;
    private final int chunkOverlap;
    private final DocumentSplitter splitter;

    @Inject
    public TikaDocumentParser(TikaConfig config) {
        this(config.chunkSize(), config.chunkOverlap());
    }

    public TikaDocumentParser(int chunkSize, int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
    }

    public List<ChunkInput> parse(InputStream content, String sourceDocumentId,
                                   String contentType, Map<String, String> metadata) {
        try {
            // Parse document with Tika
            BodyContentHandler handler = new BodyContentHandler(-1); // no limit
            org.apache.tika.metadata.Metadata tikaMetadata = new org.apache.tika.metadata.Metadata();
            if (contentType != null && !contentType.isBlank()) {
                tikaMetadata.set(org.apache.tika.metadata.Metadata.CONTENT_TYPE, contentType);
            }

            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(content, handler, tikaMetadata, new ParseContext());

            String text = handler.toString();

            // Convert Tika metadata to LangChain4j metadata
            Map<String, String> extractedMetadata = new HashMap<>();
            for (String name : tikaMetadata.names()) {
                String value = tikaMetadata.get(name);
                if (value != null && !value.isBlank()) {
                    extractedMetadata.put(name, value);
                }
            }

            // Create LangChain4j Document with extracted metadata
            Metadata langchainMetadata = Metadata.from(extractedMetadata);
            Document document = Document.from(text, langchainMetadata);

            // Split document into segments
            List<TextSegment> segments = splitter.split(document);

            // Convert segments to ChunkInput with merged metadata
            List<ChunkInput> chunks = new ArrayList<>(segments.size());
            for (TextSegment segment : segments) {
                Map<String, String> merged = new HashMap<>(metadata); // caller-provided metadata
                segment.metadata().toMap().forEach((k, v) -> {
                    if (v != null) merged.putIfAbsent(k, v.toString());
                });
                chunks.add(new ChunkInput(segment.text(), sourceDocumentId, merged));
            }
            return Collections.unmodifiableList(chunks);

        } catch (IOException | SAXException | TikaException e) {
            throw new RuntimeException("Failed to parse document with Tika: " + e.getMessage(), e);
        }
    }
}
