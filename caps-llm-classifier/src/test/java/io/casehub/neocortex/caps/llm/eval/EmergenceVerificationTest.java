package io.casehub.neocortex.caps.llm.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.casehub.neocortex.cognition.core.CognitionConfig;
import io.casehub.neocortex.cognition.eval.CognitionTestHarness;
import io.casehub.neocortex.cognition.core.CognitionCore;
import io.casehub.neocortex.cognition.prompt.CognitionRenderContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("llm-eval")
class EmergenceVerificationTest {

    private static final Path EVAL_OUTPUT = Path.of("docs/eval");
    private static final int TICKS = 10;
    private static final List<String> EVAL_AGENTS = List.of(
            "hooded-claw", "penelope-pitstop", "ant-hill-mob", "dick-dastardly");

    @Test
    void emergenceVerification() throws IOException {
        var timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        var outputDir = EVAL_OUTPUT.resolve("emergence-" + timestamp);
        Files.createDirectories(outputDir);
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        var coreA = CognitionTestHarness.builder().withCognitionConfig(CognitionConfig.none()).build().core();
        var coreB = CognitionTestHarness.builder().withCognitionConfig(CognitionConfig.all()).build().core();

        var runASections = new LinkedHashMap<String, Map<Integer, Map<String, String>>>();
        var runBSections = new LinkedHashMap<String, Map<Integer, Map<String, String>>>();
        for (var agent : EVAL_AGENTS) {
            runASections.put(agent, new LinkedHashMap<>());
            runBSections.put(agent, new LinkedHashMap<>());
        }

        for (int tick = 1; tick <= TICKS; tick++) {
            for (var agent : EVAL_AGENTS) {
                var others = EVAL_AGENTS.stream()
                        .filter(a -> !a.equals(agent)).collect(Collectors.toSet());
                coreA.tick(agent, "wacky-manor", null, (a, t) -> others);
                coreB.tick(agent, "wacky-manor", null, (a, t) -> others);

                var sectionsA = renderSections(coreA, agent);
                var sectionsB = renderSections(coreB, agent);
                runASections.get(agent).put(tick, sectionsA);
                runBSections.get(agent).put(tick, sectionsB);
            }
        }

        mapper.writeValue(outputDir.resolve("run-a-voice-only.json").toFile(), runASections);
        mapper.writeValue(outputDir.resolve("run-b-full-cognition.json").toFile(), runBSections);

        var report = generateEmergenceReport(runASections, runBSections);
        Files.writeString(outputDir.resolve("emergence-report.md"), report);

        for (var agent : EVAL_AGENTS) {
            var lastTickB = runBSections.get(agent).get(TICKS);
            var lastTickA = runASections.get(agent).get(TICKS);
            assertThat(lastTickB.size())
                    .as("Run B (full cognition) should produce >= sections than Run A for %s", agent)
                    .isGreaterThanOrEqualTo(lastTickA.size());
        }
    }

    private Map<String, String> renderSections(CognitionCore core, String agentId) {
        var ctx = new CognitionRenderContext(agentId, "wacky-manor", null);
        var result = new LinkedHashMap<String, String>();
        for (var section : core.promptSections()) {
            var rendered = section.render(ctx);
            if (rendered != null && !rendered.isBlank()) {
                result.put(section.getClass().getSimpleName(), rendered);
            }
        }
        return result;
    }

    private String generateEmergenceReport(
            Map<String, Map<Integer, Map<String, String>>> runA,
            Map<String, Map<Integer, Map<String, String>>> runB) {
        var sb = new StringBuilder("# Emergence Verification Report\n\n");
        sb.append("**Run A:** Voice-only, no cognition (`CognitionConfig.none()`)\n");
        sb.append("**Run B:** Voice-only, full cognition (`CognitionConfig.all()`)\n");
        sb.append("**Ticks:** %d\n\n".formatted(TICKS));

        sb.append("## Summary\n\n");
        sb.append("| Character | Run A sections (last tick) | Run B sections (last tick) | Emergence delta |\n");
        sb.append("|-----------|--------------------------|--------------------------|----------------|\n");
        for (var agent : EVAL_AGENTS) {
            var aSize = runA.get(agent).get(TICKS).size();
            var bSize = runB.get(agent).get(TICKS).size();
            sb.append("| %s | %d | %d | +%d |\n".formatted(agent, aSize, bSize, bSize - aSize));
        }

        sb.append("\n## Per-Character Detail\n\n");
        for (var agent : EVAL_AGENTS) {
            sb.append("### %s\n\n".formatted(agent));
            var aSections = runA.get(agent).get(TICKS);
            var bSections = runB.get(agent).get(TICKS);

            var emerged = new java.util.TreeSet<>(bSections.keySet());
            emerged.removeAll(aSections.keySet());

            if (emerged.isEmpty()) {
                sb.append("No emergence — same sections in both runs.\n\n");
            } else {
                sb.append("**Emerged sections** (present in B, absent in A):\n\n");
                for (var section : emerged) {
                    var content = bSections.get(section);
                    var preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;
                    sb.append("- **%s**: %s\n".formatted(section, preview.replace("\n", " ")));
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
