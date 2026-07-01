package io.casehub.neocortex.rag.expansion;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "casehub.rag.expansion")
public interface ExpansionConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("llm")
    String mode();

    @WithDefault("1")
    int hypotheticalCount();

    Optional<String> promptTemplate();

    Optional<String> template();

    Optional<String> stepBackPromptTemplate();
}
