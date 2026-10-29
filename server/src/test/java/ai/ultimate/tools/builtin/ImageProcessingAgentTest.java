package ai.ultimate.tools.builtin;

import ai.ultimate.agents.Agent;
import ai.ultimate.agents.AgentEvent;
import ai.ultimate.agents.AgentExecutor;
import ai.ultimate.agents.AgentPlanner;
import ai.ultimate.agents.AgentStep;
import ai.ultimate.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** Exercises the real ReACT parser/dispatcher, substituting only model responses and database persistence. */
class ImageProcessingAgentTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test void agentDispatchReturnsImageValidationContractInsteadOfArgumentCountError() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("managed/tenant"));
        var tool = new ImageProcessingTool(temporary.resolve("managed"), "unused",
                (command, directory, environment, timeout) -> { throw new AssertionError("Validation must precede native work"); },
                Duration.ofSeconds(10));
        var arguments = arguments(workspace, "invalid");
        arguments.put("width", 5); // convert forbids resizing arguments
        var observation = execute(tool, JSON.writeValueAsString(arguments));
        assertThat(JSON.readTree(observation).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(workspace.resolve("invalid")).doesNotExist();
    }

    @Test void agentDispatchCreatesNativeArtifactAndPublishesStructuredSchema() throws Exception {
        String binary = System.getProperty("ultimate.image.integration.binary");
        assumeTrue(binary != null && Files.isExecutable(Path.of(binary)), "Explicit ImageMagick executable required");
        Path workspace = Files.createDirectories(temporary.resolve("managed/tenant"));
        var source = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        source.setRGB(12, 12, 0x4080c0);
        ImageIO.write(source, "png", workspace.resolve("source.png").toFile());
        var tool = new ImageProcessingTool(temporary.resolve("managed"), binary, new ImageProcessRunner.Native(), Duration.ofSeconds(20));
        JsonNode result = JSON.readTree(execute(tool, JSON.writeValueAsString(arguments(workspace, "agent"))));
        assertThat(result.path("code").asText()).isEqualTo("OK");
        assertThat(result.path("artifacts").get(0).path("path").asText()).isEqualTo("agent/image-01.png");
        var output = ImageIO.read(workspace.resolve("agent/image-01.png").toFile());
        assertThat(output.getWidth()).isEqualTo(32);
        assertThat(output.getHeight()).isEqualTo(24);
        assertThat(output.getRGB(12, 12)).isEqualTo(source.getRGB(12, 12));
    }

    @Test void malformedStructuredInputReturnsToolErrorWithoutPublishing() throws Exception {
        Path workspace = Files.createDirectories(temporary.resolve("managed/tenant"));
        var tool = new ImageProcessingTool(temporary.resolve("managed"), "unused",
                (command, directory, environment, timeout) -> { throw new AssertionError("Malformed JSON must not invoke native work"); },
                Duration.ofSeconds(10));
        assertThat(execute(tool, "{invalid json")).startsWith("Tool error:");
        try (var files = Files.list(workspace)) { assertThat(files).isEmpty(); }
    }

    private Map<String, Object> arguments(Path workspace, String output) {
        return new java.util.HashMap<>(Map.ofEntries(Map.entry("workspacePath", workspace.toString()),
                Map.entry("inputPaths", List.of("source.png")), Map.entry("outputDirectory", "."),
                Map.entry("outputName", output), Map.entry("operation", "convert"), Map.entry("outputFormat", "png"),
                Map.entry("width", 0), Map.entry("height", 0), Map.entry("offsetX", 0), Map.entry("offsetY", 0),
                Map.entry("quality", 100), Map.entry("brightness", 0), Map.entry("contrast", 0), Map.entry("watermark", "")));
    }

    private String execute(ImageProcessingTool tool, String input) {
        var planner = spy(new AgentPlanner(null));
        doReturn(Mono.just("ACTION: processImages\nINPUT: " + input), Mono.just("FINAL_ANSWER: done"))
                .when(planner).planNextStep(anyString(), anyString(), anyString());
        var database = mock(R2dbcEntityTemplate.class);
        when(database.insert(any(AgentStep.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        var executor = new AgentExecutor(planner, new ToolRegistry(List.of(tool, new CalculatorTool())), database);
        UUID user = UUID.randomUUID();
        var events = executor.execute(Agent.create(user, null, "Convert the managed image").withRunning(), user)
                .collectList().block(Duration.ofSeconds(30));
        assertThat(events).isNotNull().anyMatch(event -> event.type() == AgentEvent.EventType.FINAL);
        var descriptions = ArgumentCaptor.forClass(Map.class);
        verify(planner).formatToolList(descriptions.capture());
        assertThat((String) descriptions.getValue().get("processImages"))
                .contains("INPUT must be a JSON object", "workspacePath", "inputPaths", "outputName");
        assertThat((String) descriptions.getValue().get("calculate")).doesNotContain("INPUT must be a JSON object");
        return events.stream().filter(event -> event.type() == AgentEvent.EventType.OBSERVE).findFirst().orElseThrow().data();
    }
}
