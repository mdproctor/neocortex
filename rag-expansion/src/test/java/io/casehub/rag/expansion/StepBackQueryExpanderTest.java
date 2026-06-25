package io.casehub.rag.expansion;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.casehub.rag.RetrievalQuery;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class StepBackQueryExpanderTest {

    @Test
    void expandReturnsOriginalAndAbstract() {
        ChatModel model = stubChatModel(prompt -> "What are NSAIDs?");
        var expander = new StepBackQueryExpander(model, stubConfig(Optional.empty()));
        var result = expander.expand(RetrievalQuery.of("side effects of ibuprofen for liver patients"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).text()).isEqualTo("side effects of ibuprofen for liver patients");
        assertThat(result.get(0).expandedText()).isNull();
        assertThat(result.get(1).text()).isEqualTo("What are NSAIDs?");
    }

    @Test
    void expandUsesDefaultPromptTemplate() {
        var capturedPrompt = new String[1];
        ChatModel model = stubChatModel(prompt -> {
            capturedPrompt[0] = prompt;
            return "abstract question";
        });
        var expander = new StepBackQueryExpander(model, stubConfig(Optional.empty()));
        expander.expand(RetrievalQuery.of("test query"));

        assertThat(capturedPrompt[0]).contains("test query");
        assertThat(capturedPrompt[0]).contains("more general, abstract version");
    }

    @Test
    void expandUsesCustomPromptTemplate() {
        ChatModel model = stubChatModel(prompt -> "custom abstract");
        var expander = new StepBackQueryExpander(model,
            stubConfig(Optional.of("Step back from: %s")));
        var result = expander.expand(RetrievalQuery.of("specific question"));

        assertThat(result).hasSize(2);
        assertThat(result.get(1).text()).isEqualTo("custom abstract");
    }

    @Test
    void expandPreservesOriginalExpansion() {
        ChatModel model = stubChatModel(prompt -> "abstract");
        var expander = new StepBackQueryExpander(model, stubConfig(Optional.empty()));
        var query = new RetrievalQuery("original", "prior expansion");
        var result = expander.expand(query);

        assertThat(result.get(0).text()).isEqualTo("original");
        assertThat(result.get(0).expandedText()).isEqualTo("prior expansion");
        assertThat(result.get(1).text()).isEqualTo("abstract");
    }

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

    private static ExpansionConfig stubConfig(Optional<String> stepBackPromptTemplate) {
        return new ExpansionConfig() {
            @Override public boolean enabled() { return true; }
            @Override public String mode() { return "step-back"; }
            @Override public int hypotheticalCount() { return 1; }
            @Override public Optional<String> promptTemplate() { return Optional.empty(); }
            @Override public Optional<String> template() { return Optional.empty(); }
            @Override public Optional<String> stepBackPromptTemplate() { return stepBackPromptTemplate; }
        };
    }
}
