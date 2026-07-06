package io.casehub.neocortex.rag.expansion;

import dev.langchain4j.model.chat.ChatModel;
import io.casehub.neocortex.rag.QueryExpander;
import io.casehub.neocortex.rag.RetrievalQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@ApplicationScoped
@IfBuildProperty(name = "casehub.rag.expansion.mode", stringValue = "llm")
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
    public List<RetrievalQuery> expand(final RetrievalQuery query) {
        final int n = config.hypotheticalCount();
        final String promptTemplate = config.promptTemplate().orElse(DEFAULT_PROMPT);

        if (n == 1) {
            String prompt = promptTemplate.formatted(query.text());
            String hypothetical = chatModel.chat(prompt);
            return List.of(query.withExpansion(hypothetical));
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<RetrievalQuery>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                futures.add(executor.submit(() -> {
                    String prompt = promptTemplate.formatted(query.text());
                    String hypothetical = chatModel.chat(prompt);
                    return query.withExpansion(hypothetical);
                }));
            }
            List<RetrievalQuery> expansions = new ArrayList<>(n);
            for (var future : futures) {
                expansions.add(future.get());
            }
            return expansions;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
