package io.casehub.rag.expansion;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.rag.QueryExpander;
import io.casehub.rag.RetrievalQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "step-back")
public class StepBackQueryExpander implements QueryExpander {

    static final String DEFAULT_PROMPT =
        "Given the question below, generate a more general, abstract version "
        + "that captures the underlying concept. The step-back question should "
        + "be broader and help retrieve foundational knowledge. "
        + "Return only the step-back question, nothing else.\n\n"
        + "Original question: %s\n\nStep-back question:";

    private final ChatModel chatModel;
    private final ExpansionConfig config;

    @Inject
    public StepBackQueryExpander(ChatModel chatModel, ExpansionConfig config) {
        this.chatModel = chatModel;
        this.config = config;
    }

    @Override
    public List<RetrievalQuery> expand(RetrievalQuery query) {
        String prompt = config.stepBackPromptTemplate().orElse(DEFAULT_PROMPT)
            .formatted(query.text());
        String stepBack = chatModel.chat(prompt);
        return List.of(
            query,
            RetrievalQuery.of(stepBack)
        );
    }
}
