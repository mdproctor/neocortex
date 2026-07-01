package io.casehub.neocortex.examples.rag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CorpusTestSupport {

    private CorpusTestSupport() {}

    public static void copyCorpus(Path targetDir) throws IOException {
        for (String file : ExampleCorpus.FILES) {
            Path target = targetDir.resolve(file.substring("corpus/".length()));
            Files.createDirectories(target.getParent());
            try (InputStream is = CorpusTestSupport.class.getClassLoader().getResourceAsStream(file)) {
                if (is == null) throw new IllegalStateException("Missing classpath resource: " + file);
                Files.copy(is, target);
            }
        }
    }

    public static int documentCount() {
        return ExampleCorpus.FILES.size();
    }
}
