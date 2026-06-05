package com.otapp.hmis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.otapp.hmis.support.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Exports the per-module OpenAPI slices to {@code docs/openapi/} (DoD item).
 *
 * <p>Fetches the live springdoc {@code /v3/api-docs}, filters {@code paths} by each module's base
 * path prefix(es), prunes {@code components.schemas} to only those <em>transitively referenced</em>
 * by the kept paths, and writes one {@code docs/openapi/<module>.yaml} per module. It also asserts
 * the expected endpoints are present, so it doubles as a contract-presence regression guard.
 *
 * <p><strong>Schema pruning (inc-07 07c-ii FINAL):</strong> springdoc emits ONE global
 * {@code components.schemas} set spanning every module. Earlier this export copied that whole set
 * into every slice, so e.g. the inpatient {@code AdmissionRequest} DTO leaked into
 * {@code pharmacy.yaml} and {@code inventory.yaml}. We now compute the transitive {@code $ref}
 * closure of each slice's paths and keep only those schemas — each module's contract carries only
 * its own types.
 */
class OpenApiExportIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void exportPerModuleOpenApi() throws Exception {
        MvcResult res = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode api = objectMapper.readTree(res.getResponse().getContentAsString());

        writeSlice(api, "pharmacy",  "pharmacy",  List.of("/api/v1/pharmacy"));
        writeSlice(api, "inventory", "inventory", List.of("/api/v1/inventory"));
        // Inpatient owns both its own base path and the ops trigger for the ward-accrual job
        // (POST /api/v1/ops/jobs/ward-accrual/trigger — inc-07 07c-ii, WardAccrualOpsController).
        writeSlice(api, "inpatient", "inpatient", List.of("/api/v1/inpatient", "/api/v1/ops/jobs"));

        // contract-presence assertions (sanity that the controllers are wired)
        JsonNode paths = api.get("paths");
        assertThat(paths.has("/api/v1/pharmacy/prescriptions/uid/{uid}/dispense")).isTrue();
        assertThat(paths.has("/api/v1/pharmacy/sale-orders")).isTrue();
        assertThat(paths.has("/api/v1/inventory/lpos")).isTrue();
        assertThat(paths.has("/api/v1/inventory/grns/uid/{uid}/approve")).isTrue();
        assertThat(paths.has("/api/v1/inventory/ps-transfers/tos/uid/{uid}/issue")).isTrue();
        assertThat(paths.has("/api/v1/inventory/pp-transfers/rns/uid/{uid}/complete")).isTrue();
        assertThat(paths.has("/api/v1/inpatient/admissions")).isTrue();
        assertThat(paths.has("/api/v1/ops/jobs/ward-accrual/trigger")).isTrue();
    }

    private void writeSlice(JsonNode api, String name, String title, List<String> prefixes)
            throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("openapi", api.get("openapi"));
        ObjectNode info = objectMapper.createObjectNode();
        info.put("title", "Zana HMIS — " + title + " API");
        info.put("version", "v1");
        root.set("info", info);

        // filter paths by any of the module's prefixes (sorted)
        ObjectNode paths = objectMapper.createObjectNode();
        Map<String, JsonNode> sorted = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = api.get("paths").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (prefixes.stream().anyMatch(p -> e.getKey().startsWith(p))) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        sorted.forEach(paths::set);
        root.set("paths", paths);

        // Prune components.schemas to the transitive $ref closure of the kept paths, so a module's
        // slice carries only its own schemas (no cross-module DTO leakage).
        JsonNode allSchemas = api.path("components").path("schemas");
        if (allSchemas.isObject() && !allSchemas.isMissingNode()) {
            Set<String> keep = transitiveSchemaClosure(paths, allSchemas);
            ObjectNode prunedSchemas = objectMapper.createObjectNode();
            keep.stream().sorted().forEach(s -> prunedSchemas.set(s, allSchemas.get(s)));
            ObjectNode components = objectMapper.createObjectNode();
            components.set("schemas", prunedSchemas);
            root.set("components", components);
        }

        Path out = Path.of("..", "docs", "openapi", name + ".yaml");
        Files.createDirectories(out.getParent());
        new YAMLMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), root);
        assertThat(paths.size()).as(name + " has endpoints").isGreaterThan(0);
    }

    /**
     * Compute the set of schema names transitively referenced from {@code paths}. Seeds with every
     * {@code #/components/schemas/X} {@code $ref} found anywhere under the kept paths, then closes
     * over schema-to-schema references until no new names appear.
     */
    private Set<String> transitiveSchemaClosure(JsonNode paths, JsonNode allSchemas) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        collectRefs(paths, seen, queue);
        while (!queue.isEmpty()) {
            String schemaName = queue.poll();
            JsonNode schema = allSchemas.get(schemaName);
            if (schema != null) {
                collectRefs(schema, seen, queue);
            }
        }
        return seen;
    }

    /** Walk {@code node}, adding every {@code #/components/schemas/X} ref name to {@code seen}/{@code queue}. */
    private void collectRefs(JsonNode node, Set<String> seen, Deque<String> queue) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String text = ref.asText();
                String prefix = "#/components/schemas/";
                if (text.startsWith(prefix)) {
                    String schemaName = text.substring(prefix.length());
                    if (seen.add(schemaName)) {
                        queue.add(schemaName);
                    }
                }
            }
            node.fields().forEachRemaining(e -> collectRefs(e.getValue(), seen, queue));
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, seen, queue));
        }
    }
}
