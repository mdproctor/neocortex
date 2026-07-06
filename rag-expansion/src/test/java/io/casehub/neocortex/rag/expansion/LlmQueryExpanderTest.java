package io.casehub.neocortex.rag.expansion;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.neocortex.rag.RetrievalQuery;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class LlmQueryExpanderTest {

    @Test
    void expandCallsChatModelWithDefaultPrompt() {
        var capturedPrompt = new String[1];
        ChatModel mockModel = stubChatModel(prompt -> {
            capturedPrompt[0] = prompt;
            return "Diabetes is a chronic condition characterized by elevated blood sugar.";
        });

        var expander = new LlmQueryExpander(mockModel, stubConfig(Optional.empty()));
        var results = expander.expand(RetrievalQuery.of("what is diabetes?"));

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.text()).isEqualTo("what is diabetes?");
        assertThat(result.expandedText()).isEqualTo(
            "Diabetes is a chronic condition characterized by elevated blood sugar.");
        assertThat(capturedPrompt[0]).contains("what is diabetes?");
        assertThat(capturedPrompt[0]).contains("write a short passage");
    }

    @Test
    void expandUsesCustomPromptTemplate() {
        ChatModel mockModel = stubChatModel(prompt -> "custom response");
        var expander = new LlmQueryExpander(mockModel,
            stubConfig(Optional.of("Custom: %s")));
        var results = expander.expand(RetrievalQuery.of("test query"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).expandedText()).isEqualTo("custom response");
    }

    @Test
    void expandPreservesOriginalText() {
        ChatModel mockModel = stubChatModel(prompt -> "hypothetical passage");
        var expander = new LlmQueryExpander(mockModel, stubConfig(Optional.empty()));

        var query = RetrievalQuery.of("my question");
        var results = expander.expand(query);

        assertThat(results).hasSize(1);
        var result = results.get(0);
        assertThat(result.text()).isEqualTo("my question");
        assertThat(result.searchText()).isEqualTo("hypothetical passage");
    }

    /**
     * Creates a ChatModel stub that intercepts via doChat,
     * extracts the user message text, and delegates to the handler.
     */
    private static ChatModel stubChatModel(Function<String, String> handler) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                String userText = ((UserMessage) request.messages().get(0)).singleText();
                String response = handler.apply(userText);
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
            }
        };
    }

    @Test
    void expandReturnsMultipleHypotheticals() {
        var callCount = new AtomicInteger();
        ChatModel model = stubChatModel(prompt -> "hypothetical " + callCount.incrementAndGet());
        var expander = new LlmQueryExpander(model, stubConfig(Optional.empty(), 3));
        var result = expander.expand(RetrievalQuery.of("test query"));

        assertThat(result).hasSize(3);
        assertThat(result).allSatisfy(q -> {
            assertThat(q.text()).isEqualTo("test query");
            assertThat(q.expandedText()).startsWith("hypothetical ");
        });
        assertThat(result).extracting(r -> r.expandedText())
            .containsExactlyInAnyOrder("hypothetical 1", "hypothetical 2", "hypothetical 3");
    }

    @Test
    void expandDefaultCountIsOne() {
        ChatModel model = stubChatModel(prompt -> "single hypothetical");
        var expander = new LlmQueryExpander(model, stubConfig(Optional.empty(), 1));
        var result = expander.expand(RetrievalQuery.of("test"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).expandedText()).isEqualTo("single hypothetical");
    }

    @Test
    void expandRunsConcurrentlyWhenCountGreaterThanOne() throws Exception {
        final int n = 3;
        var allStarted = new CountDownLatch(n);
        var proceed = new CountDownLatch(1);

        ChatModel model = stubChatModel(prompt -> {
            allStarted.countDown();
            try {
                proceed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "hypothetical";
        });

        var expander = new LlmQueryExpander(model, stubConfig(Optional.empty(), n));

        var future = CompletableFuture.supplyAsync(() ->
            expander.expand(RetrievalQuery.of("test")));

        assertThat(allStarted.await(5, TimeUnit.SECONDS))
            .as("All %d calls should start concurrently", n)
            .isTrue();

        proceed.countDown();

        var result = future.get(5, TimeUnit.SECONDS);
        assertThat(result).hasSize(n);
        assertThat(result).allSatisfy(q ->
            assertThat(q.expandedText()).isEqualTo("hypothetical"));
    }

    private static ExpansionConfig stubConfig(Optional<String> promptTemplate, int hypotheticalCount) {
        return new ExpansionConfig() {
            @Override public boolean enabled() { return true; }
            @Override public Optional<String> mode() { return Optional.of("llm"); }
            @Override public int hypotheticalCount() { return hypotheticalCount; }
            @Override public Optional<String> promptTemplate() { return promptTemplate; }
            @Override public Optional<String> template() { return Optional.empty(); }
            @Override public Optional<String> stepBackPromptTemplate() { return Optional.empty(); }
        };
    }

    private static ExpansionConfig stubConfig(Optional<String> promptTemplate) {
        return stubConfig(promptTemplate, 1);
    }
}
