package io.casehub.neocortex.memory.mem0;

import io.casehub.neocortex.memory.*;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Testcontainers resource for integration tests: starts Ollama (local embeddings) and
 * Mem0 OSS on a shared Docker network, then returns the Mem0 base URL for Quarkus config.
 *
 * <p><strong>Requires Docker or Podman.</strong> On macOS with Podman, set
 * {@code DOCKER_HOST=unix:///var/run/podman/podman.sock} — Testcontainers 2.x ignores
 * {@code docker.host} property and requires the env var (see GE-20260616-bb45d5).
 *
 * <p><strong>Ollama model:</strong> {@code nomic-embed-text} is pulled inside the container
 * after start. First run downloads ~274 MB; subsequent runs use the pulled layer from cache
 * if the container is reused.
 *
 * <p><strong>Mem0 config:</strong> A {@code config.yaml} is mounted at {@code /app/config.yaml}.
 * The Mem0 container reads this on startup to configure the embedder and vector store.
 * If the container's config path changes in a future Mem0 release, update {@code MEM0_CONFIG_PATH}.
 *
 * <p><strong>Enabling:</strong> Remove {@code @Disabled} from {@link CaseMemoryStoreContractIT}
 * once CI has Docker available with sufficient resources (~4 GB RAM for Ollama + Mem0).
 */
public class Mem0ContainerResource implements QuarkusTestResourceLifecycleManager {

    static final String OLLAMA_IMAGE   = "ollama/ollama:latest";
    static final String MEM0_IMAGE     = "mem0ai/mem0:latest";
    static final String EMBED_MODEL    = "nomic-embed-text";
    static final int    OLLAMA_PORT    = 11434;
    static final int    MEM0_PORT      = 8000;
    static final String MEM0_CONFIG_PATH = "/app/config.yaml";

    private Network network;
    private GenericContainer<?> ollama;
    private GenericContainer<?> mem0;

    @Override
    @SuppressWarnings("resource")
    public Map<String, String> start() {
        network = Network.newNetwork();

        ollama = new GenericContainer<>(OLLAMA_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("ollama")
            .withExposedPorts(OLLAMA_PORT)
            .waitingFor(Wait.forHttp("/api/version").forPort(OLLAMA_PORT)
                .withStartupTimeout(Duration.ofMinutes(2)));
        ollama.start();

        // Pull the embedding model inside the container.
        // nomic-embed-text is ~274 MB; subsequent starts are fast from the Podman/Docker layer cache.
        try {
            var result = ollama.execInContainer("ollama", "pull", EMBED_MODEL);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                    "Failed to pull Ollama model '" + EMBED_MODEL + "': " + result.getStderr());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama model pull interrupted", e);
        }

        Path configFile = writeMem0Config();

        mem0 = new GenericContainer<>(MEM0_IMAGE)
            .withNetwork(network)
            .withNetworkAliases("mem0")
            .withExposedPorts(MEM0_PORT)
            // infer=false: store text verbatim; embeddings still computed by Ollama for search.
            .withEnv("MEM0_INFER", "false")
            // mount config so Mem0 uses Ollama for embeddings and local ChromaDB for storage
            .withCopyFileToContainer(MountableFile.forHostPath(configFile), MEM0_CONFIG_PATH)
            .waitingFor(Wait.forHttp("/v1/health").forPort(MEM0_PORT)
                .withStartupTimeout(Duration.ofMinutes(2)));
        mem0.start();

        return Map.of(
            "quarkus.rest-client.mem0.url", "http://localhost:" + mem0.getMappedPort(MEM0_PORT),
            "casehub.memory.mem0.api-key",  "no-key-needed",
            "casehub.memory.mem0.infer",    "false"
        );
    }

    @Override
    public void stop() {
        if (mem0   != null) mem0.stop();
        if (ollama != null) ollama.stop();
        if (network != null) network.close();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path writeMem0Config() {
        // Mem0 config.yaml: Ollama embedder (no API key) + local ChromaDB (no external service).
        // ollama_base_url uses Docker network alias "ollama" — reachable inside the mem0 container.
        String yaml = """
            embedder:
              provider: ollama
              config:
                model: %s
                ollama_base_url: "http://ollama:%d"
            vector_store:
              provider: chroma
              config:
                path: /tmp/chroma_casehub_test
            """.formatted(EMBED_MODEL, OLLAMA_PORT);
        try {
            Path tmp = Files.createTempFile("mem0-config-", ".yaml");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (IOException e) {
            throw new IllegalStateException("Could not write Mem0 config file", e);
        }
    }
}
