package io.casehub.neocortex.examples.rag;

import java.util.List;

public final class ExampleCorpus {

    public static final List<String> FILES = List.of(
        "corpus/tech/cdi-injection.md",
        "corpus/tech/quarkus-lifecycle.md",
        "corpus/tech/onnx-runtime-basics.md",
        "corpus/tech/rest-endpoint-design.md",
        "corpus/tech/reactive-streams.md",
        "corpus/news/central-bank-rates.md",
        "corpus/news/tech-earnings-q1.md",
        "corpus/news/climate-summit.md",
        "corpus/news/ai-regulation.md",
        "corpus/news/supply-chain.md",
        "corpus/legal/lease-termination.md",
        "corpus/legal/data-protection.md",
        "corpus/legal/employment-notice.md",
        "corpus/legal/liability-limitation.md",
        "corpus/legal/intellectual-property.md"
    );

    private ExampleCorpus() {}
}
