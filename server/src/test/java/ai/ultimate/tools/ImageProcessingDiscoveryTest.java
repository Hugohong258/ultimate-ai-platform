package ai.ultimate.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ImageProcessingDiscoveryTest {
    @Test
    void exposesImageProcessingThroughTheProductionToolRegistry() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(UltimateTool.class));
        var candidates = scanner.findCandidateComponents("ai.ultimate.tools.builtin");
        Class<?> imageTool = null;
        for (var candidate : candidates) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (Arrays.stream(type.getMethods()).anyMatch(method ->
                    method.getName().equals("processImages")
                            && method.isAnnotationPresent(Tool.class))) {
                imageTool = type;
            }
        }
        assertThat(imageTool).as("an image-processing @Tool component must be discoverable")
                .isNotNull();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(imageTool, ToolRegistry.class);
            context.refresh();
            assertThat(context.getBean(ToolRegistry.class).getAll())
                    .hasSize(1).contains((UltimateTool) context.getBean(imageTool));
        }
    }
}
