package ke.co.safaricom.pims.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Lightweight guardrails so the published YAML stays aligned with routing expectations. */
class InventoryOpenApiYamlContractTest {

    @Test
    void canonical_yaml_documents_core_inventory_paths_and_contract_hints() throws Exception {
        var resource = new ClassPathResource("static/openapi/inventory-api-v1.yaml");
        assertThat(resource.exists()).isTrue();
        String yaml = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(yaml).contains("/inventory/products:");
        assertThat(yaml).contains("/inventory/sales:");
        assertThat(yaml).contains("/inventory/stock-report:");
        assertThat(yaml).contains("/inventory/revenue:");
        assertThat(yaml).contains("/inventory/reports/revenue-analytics:");
        assertThat(yaml).contains("/inventory/reports/revenue-trends:");
        assertThat(yaml).contains("/inventory/reports/revenue-by-category:");
        assertThat(yaml).contains("/inventory/reports/top-selling-products:");
        assertThat(yaml).contains("/inventory/reports/revenue-by-payment-method:");
        assertThat(yaml).contains("/inventory/reports/transaction-types:");
        assertThat(yaml).contains("/inventory/reports/revenue-overview:");
        assertThat(yaml).contains("manufacturer_id");
        assertThat(yaml).contains("/inventory/purchase-orders:");
        assertThat(yaml).contains("PMIS");
    }
}
