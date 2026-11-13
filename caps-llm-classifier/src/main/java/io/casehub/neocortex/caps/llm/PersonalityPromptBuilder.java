package io.casehub.neocortex.caps.llm;

import io.casehub.neocortex.caps.CapsTopology;
import io.casehub.neocortex.caps.NodeType;

import java.util.Map;
import java.util.StringJoiner;

public final class PersonalityPromptBuilder {

    private static final String DISPOSITION_PREFIX = "disposition.";
    private static final String[] AXIS_KEYS = {
        "socialOrient", "ruleFollowing", "riskAppetite", "autonomy", "conflictMode"
    };
    private static final String[] AXIS_LABELS = {
        "", " rule-following", " risk appetite", " autonomy", " conflict style"
    };

    private PersonalityPromptBuilder() {}

    public static boolean hasDisposition(Map<String, String> metadata) {
        if (metadata == null) return false;
        for (String key : AXIS_KEYS) {
            if (metadata.containsKey(DISPOSITION_PREFIX + key)) return true;
        }
        return false;
    }

    public static String userPromptSuffix(Map<String, String> metadata) {
        var joiner = new StringJoiner(", ");
        for (int i = 0; i < AXIS_KEYS.length; i++) {
            String val = metadata.get(DISPOSITION_PREFIX + AXIS_KEYS[i]);
            if (val != null) {
                joiner.add(val + AXIS_LABELS[i]);
            }
        }
        return "\nCharacter disposition: " + joiner +
               ".\nClassify the situation as this character would perceive it.";
    }

    public static String personalitySystemPrompt(Map<String, String> metadata,
                                                  DispositionTension.TensionEntry tension,
                                                  CapsTopology topology) {
        var sb = new StringBuilder();
        sb.append("""
                  You are a psychological situation classifier perceiving the world \
                  through a specific character's lens.

                  This character is """);

        var joiner = new StringJoiner(", ");
        for (int i = 0; i < AXIS_KEYS.length; i++) {
            String val = metadata.get(DISPOSITION_PREFIX + AXIS_KEYS[i]);
            if (val != null) {
                joiner.add(val + AXIS_LABELS[i]);
            }
        }
        sb.append(joiner).append(". ");
        sb.append(tension.narrative()).append("\n\n");

        sb.append("""
                  Classify the following situation into CAPS input node activations.

                  Respond with a JSON object:
                  {"activations": [{"node": "node_id", "confidence": 0.0-1.0}]}

                  Rules:
                  - Only use node IDs from the list below
                  - confidence reflects how strongly the description matches the node
                  - Multiple nodes can activate for the same description
                  - Minimum confidence threshold is 0.3
                  - Respond with valid JSON only

                  Available input nodes:
                  """);

        if (topology != null) {
            topology.nodes().forEach((id, node) -> {
                if (node.type() == NodeType.INPUT) {
                    sb.append("- ").append(id);
                    if (!node.description().isEmpty()) {
                        sb.append(" (").append(node.category())
                          .append(": ").append(node.description()).append(")");
                    } else {
                        sb.append(" (").append(node.category()).append(")");
                    }
                    sb.append(node.vocabulary().isEmpty() ? "" :
                              " — vocabulary: " + String.join(", ", node.vocabulary().keySet()));
                    sb.append("\n");
                }
            });
        }

        return sb.toString();
    }
}
