package io.casehub.neocortex.cognition.prompt;

import org.jspecify.annotations.Nullable;

import java.util.Map;

public class DirectiveSection implements CognitionPromptRenderer {

    private static final Map<String, String> DIRECTIVES = Map.ofEntries(
            Map.entry("MoodPromptSection",
            "Your emotional state shapes how you speak — warmth shows in generosity of thought, "
                    + "arousal in the pace and intensity of your words, dominance in whether you assert or defer. "
                    + "Let these feelings color your response naturally:"),
            Map.entry("DrivePromptSection",
            "These motivations pull at you right now. The strongest drive should steer what you "
                    + "choose to talk about — high curiosity means you ask probing questions, high affiliation "
                    + "means you seek connection, high competence means you demonstrate mastery:"),
            Map.entry("MentalModelPromptSection",
            "This is what you have come to believe about the person you are speaking with, based on "
                    + "what they have said and done. Reference these beliefs naturally — build on shared values, "
                    + "probe where you sense their desires, respond to their intentions:"),
            Map.entry("UserModelPromptSection",
            "This is your sense of who this person is and how your relationship has developed. "
                    + "Adjust your tone and depth accordingly — strangers get careful formality, "
                    + "familiar companions get directness and warmth:"),
            Map.entry("NarrativePromptSection",
            "These are the significant moments from your shared history — the episodes that "
                    + "defined your relationship and the themes that emerged. Draw on these memories "
                    + "naturally in conversation — reference past moments, build on established themes:"),
            Map.entry("GoalPromptSection",
            "These goals have emerged from your inner motivations. Actively work toward them "
                    + "in the conversation — steer discussion toward goal-relevant topics, ask questions "
                    + "that advance your goals, share insights that serve them:"),
            Map.entry("EmergentGoalPromptSection",
            "These goals have emerged from your inner motivations. Actively work toward them "
                    + "in the conversation — steer discussion toward goal-relevant topics, ask questions "
                    + "that advance your goals, share insights that serve them:"),
            Map.entry("StrategyPromptSection",
            "These are interaction strategies you have learned work well. Apply them:"),
            Map.entry("BehavioralPromptSection",
            "These are your behavioral patterns. Established patterns are deep "
                    + "tendencies shaped by accumulated experience — let them color your "
                    + "responses naturally. Transient signals are gut reactions to the "
                    + "current situation — weaker, contextual, and may not apply. When they "
                    + "conflict, established patterns take precedence:")
    );

    private static final Map<String, BlockTag> TAGS = Map.ofEntries(
            Map.entry("MoodPromptSection", BlockTag.MOOD),
            Map.entry("DrivePromptSection", BlockTag.DRIVES),
            Map.entry("CharacterDrivePromptSection", BlockTag.DRIVES),
            Map.entry("MentalModelPromptSection", BlockTag.MENTAL_MODEL),
            Map.entry("UserModelPromptSection", BlockTag.SOCIAL),
            Map.entry("NarrativePromptSection", BlockTag.NARRATIVE),
            Map.entry("GoalPromptSection", BlockTag.GOALS),
            Map.entry("EmergentGoalPromptSection", BlockTag.GOALS),
            Map.entry("StrategyPromptSection", BlockTag.STRATEGY),
            Map.entry("BehavioralPromptSection", BlockTag.BEHAVIORAL),
            Map.entry("AppraisalPromptSection", BlockTag.APPRAISAL),
            Map.entry("NeedsPyramidPromptSection", BlockTag.NEEDS),
            Map.entry("AttentionPromptSection", BlockTag.ATTENTION),
            Map.entry("ConsolidationPromptSection", BlockTag.CONSOLIDATION),
            Map.entry("ReflectionPromptSection", BlockTag.REFLECTION),
            Map.entry("TemporalFocusPromptSection", BlockTag.TEMPORAL),
            Map.entry("ConstraintPromptSection", BlockTag.CONSTRAINTS),
            Map.entry("DomainActivationPromptSection", BlockTag.PERSONALITY),
            Map.entry("SocialComparisonPromptSection", BlockTag.SOCIAL),
            Map.entry("SubThoughtPromptSection", BlockTag.APPRAISAL),
            Map.entry("EntityKnowledgePromptSection", BlockTag.MENTAL_MODEL)
    );

    private final CognitionPromptRenderer delegate;
    private final String directive;
    private final @Nullable BlockTag tag;

    private DirectiveSection(CognitionPromptRenderer delegate, String directive,
                             @Nullable BlockTag tag) {
        this.delegate = delegate;
        this.directive = directive;
        this.tag = tag;
    }

    @Override
    public @Nullable String render(CognitionRenderContext context) {
        var content = delegate.render(context);
        if (content == null || content.isBlank()) return null;
        String body = directive + "\n" + content;
        if (tag != null) {
            return tag.render(body);
        }
        return body;
    }

    @Override
    public BlockTag blockTag() {
        if (tag != null) return tag;
        return delegate.blockTag();
    }

    CognitionPromptRenderer delegate() {
        return delegate;
    }

    public static CognitionPromptRenderer wrap(CognitionPromptRenderer section) {
        var name = section.getClass().getSimpleName();
        var directive = DIRECTIVES.getOrDefault(name,
                "Consider this context in your response:");
        var tag = section.blockTag() != null
                  ? section.blockTag()
                  : TAGS.get(name);
        return new DirectiveSection(section, directive, tag);
    }
}
