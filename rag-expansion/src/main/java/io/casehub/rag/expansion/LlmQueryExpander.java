package io.casehub.rag.expansion;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.rag.QueryExpander;
import io.casehub.rag.RetrievalQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "llm", enableIfMissing = true)
public class LlmQueryExpander implements QueryExpander {

    static final String DEFAULT_PROMPT =
        "Given the question below, write a short passage (3-5 sentences) "
            + "that would directly answer it. Write as if the passage comes from "
            + "an authoritative document. Do not include the question itself.\n\n"
            + "Question: %s\n\nPassage:";

    private final ChatModel chatModel;
    private final ExpansionConfig config;

    @Inject
    public LlmQueryExpander(ChatModel chatModel, ExpansionConfig config) {
        this.chatModel = chatModel;
        this.config = config;
    }

    @Override
    public List<RetrievalQuery> expand(RetrievalQuery query) {
        int n = config.hypotheticalCount();
        String promptTemplate = config.promptTemplate().orElse(DEFAULT_PROMPT);

        List<RetrievalQuery> expansions = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String prompt = promptTemplate.formatted(query.text());
            String hypothetical = chatModel.chat(prompt);
            expansions.add(query.withExpansion(hypothetical));
        }
        return expansions;
    }
}
