package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.web.model.TaxSchemas;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaxConfigService {

    private static final String DOCTYPE        = "Sales Taxes and Charges Template";
    private static final String NOT_FOUND_MSG  = "Tax template not found: ";

    private static final String TEMPLATE_FIELDS =
            "[\"name\",\"title\",\"company\",\"is_default\",\"disabled\",\"taxes\"]";

    private final ErpNextTenantRouter router;

    /** Per-tenant cache of the default template (name + company). Refreshes every 5 minutes. */
    private final ConcurrentHashMap<String, Mono<DefaultTaxInfo>> defaultTemplateCache =
            new ConcurrentHashMap<>();

    public TaxConfigService(ErpNextTenantRouter router) {
        this.router = router;
    }

    // ---- Default template resolution (used by SalesOrderService) -----------

    /**
     * Returns the name and company of the tenant's default Sales Taxes and Charges Template.
     * Result is cached per tenant for 5 minutes; empty if no default template is configured.
     */
    public Mono<DefaultTaxInfo> getDefaultTaxTemplateName(String tenantId) {
        return defaultTemplateCache.computeIfAbsent(tenantId, this::fetchDefaultTemplate);
    }

    private Mono<DefaultTaxInfo> fetchDefaultTemplate(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", "[\"name\",\"company\"]");
        params.put("filters", "[[\"is_default\",\"=\",1],[\"disabled\",\"=\",0]]");
        params.put("limit_page_length", "1");
        return router.getList(tenantId, DOCTYPE, params, LIST_TYPE)
                .flatMap(resp -> {
                    if (resp.data() == null || resp.data().isEmpty()) {
                        return Mono.empty();
                    }
                    String templateName = resp.data().get(0).name();
                    return router.getOne(tenantId, DOCTYPE, templateName, SINGLE_TYPE)
                            .map(full -> {
                                ErpNextDoc template = full.data();
                                return new DefaultTaxInfo(
                                        template.name(),
                                        template.company(),
                                        toInvoiceTaxRows(template.taxes()));
                            });
                })
                .cache(Duration.ofMinutes(5));
    }

    /** Evicts the cached default template for a tenant (call after create/update/delete). */
    public void evictCache(String tenantId) {
        defaultTemplateCache.remove(tenantId);
    }

    // ---- List ---------------------------------------------------------------

    public Mono<List<TaxSchemas.TaxTemplateResponse>> listTemplates(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put("fields", TEMPLATE_FIELDS);
        params.put("limit_page_length", "200");
        return router.getList(tenantId, DOCTYPE, params, LIST_TYPE)
                .map(resp -> resp.data().stream().map(this::toResponse).toList());
    }

    // ---- Get single ---------------------------------------------------------

    public Mono<TaxSchemas.TaxTemplateResponse> getTemplate(String tenantId, String name) {
        return router.getOne(tenantId, DOCTYPE, name, SINGLE_TYPE)
                .map(resp -> toResponse(resp.data()))
                .onErrorMap(ResourceNotFoundException.class,
                        ex -> new ResourceNotFoundException(NOT_FOUND_MSG + name));
    }

    // ---- Create -------------------------------------------------------------

    public Mono<TaxSchemas.TaxTemplateResponse> createTemplate(
            String tenantId, TaxSchemas.CreateTaxTemplateRequest req) {
        Map<String, Object> body = buildBodyForCreate(req.title(), req.company(), req.isDefault(), req.taxes());
        return router.create(tenantId, DOCTYPE, body, SINGLE_TYPE)
                .map(resp -> toResponse(resp.data()))
                .doOnSuccess(r -> evictCache(tenantId));
    }

    // ---- Update -------------------------------------------------------------

    public Mono<TaxSchemas.TaxTemplateResponse> updateTemplate(
            String tenantId, String name, TaxSchemas.UpdateTaxTemplateRequest req) {
        return router.getOne(tenantId, DOCTYPE, name, SINGLE_TYPE)
                .flatMap(existing -> {
                    ErpNextDoc doc = existing.data();
                    String title      = StringUtils.hasText(req.title())   ? req.title()   : doc.name();
                    String company    = StringUtils.hasText(req.company()) ? req.company() : doc.company();
                    boolean isDefault = req.isDefault() != null ? req.isDefault()
                            : (doc.isDefault() != null && doc.isDefault() == 1);
                    boolean disabled  = req.disabled()  != null ? req.disabled()
                            : (doc.disabled() != null && doc.disabled() == 1);
                    List<TaxSchemas.TaxChargeRow> taxes = req.taxes() != null ? req.taxes()
                            : toChargeRows(doc.taxes());
                    Map<String, Object> body = buildBody(title, company, isDefault, disabled, taxes);
                    body.put("name", doc.name());
                    return router.replace(tenantId, DOCTYPE, doc.name(), body, SINGLE_TYPE)
                            .map(resp -> toResponse(resp.data()));
                })
                .doOnSuccess(r -> evictCache(tenantId))
                .onErrorMap(ResourceNotFoundException.class,
                        ex -> new ResourceNotFoundException(NOT_FOUND_MSG + name));
    }

    // ---- Delete -------------------------------------------------------------

    public Mono<Void> deleteTemplate(String tenantId, String name) {
        return router.delete(tenantId, DOCTYPE, name)
                .doOnSuccess(v -> evictCache(tenantId))
                .onErrorMap(ResourceNotFoundException.class,
                        ex -> new ResourceNotFoundException(NOT_FOUND_MSG + name));
    }

    // ---- Helpers ------------------------------------------------------------

    private Map<String, Object> buildBody(
            String title, String company, boolean isDefault, boolean disabled,
            List<TaxSchemas.TaxChargeRow> taxes) {
        Map<String, Object> body = new HashMap<>();
        body.put("doctype",    DOCTYPE);
        body.put("title",      title);
        body.put("company",    company);
        body.put("is_default", isDefault ? 1 : 0);
        body.put("disabled",   disabled  ? 1 : 0);
        if (taxes != null) body.put("taxes", taxes.stream().map(this::toErpRow).toList());
        return body;
    }

    private Map<String, Object> buildBodyForCreate(
            String title, String company, Boolean isDefault, List<TaxSchemas.TaxChargeRow> taxes) {
        return buildBody(title, company, Boolean.TRUE.equals(isDefault), false, taxes);
    }

    /** Maps template or invoice tax child rows to the fields ERPNext expects on Sales Invoice POST. */
    public List<Map<String, Object>> toInvoiceTaxRows(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return toChargeRows(raw).stream().map(this::toErpRow).toList();
    }

    private Map<String, Object> toErpRow(TaxSchemas.TaxChargeRow row) {
        Map<String, Object> m = new HashMap<>();
        m.put("charge_type",           row.chargeType());
        m.put("account_head",          row.accountHead());
        m.put("rate",                  row.rate());
        if (StringUtils.hasText(row.description()))   m.put("description",           row.description());
        if (StringUtils.hasText(row.addDeductTax()))  m.put("add_deduct_tax",         row.addDeductTax());
        if (row.includedInPrintRate() != null)        m.put("included_in_print_rate", Boolean.TRUE.equals(row.includedInPrintRate()) ? 1 : 0);
        return m;
    }

    private List<TaxSchemas.TaxChargeRow> toChargeRows(List<Map<String, Object>> raw) {
        if (raw == null) return List.of();
        return raw.stream().map(m -> new TaxSchemas.TaxChargeRow(
                (String) m.get("charge_type"),
                (String) m.get("account_head"),
                m.get("rate") instanceof Number n ? n.doubleValue() : null,
                (String) m.get("description"),
                (String) m.get("add_deduct_tax"),
                m.get("included_in_print_rate") instanceof Number n ? n.intValue() == 1 : null
        )).toList();
    }

    private TaxSchemas.TaxTemplateResponse toResponse(ErpNextDoc doc) {
        return new TaxSchemas.TaxTemplateResponse(
                doc.name(),
                doc.taxTemplateTitle() != null ? doc.taxTemplateTitle() : doc.name(),
                doc.company(),
                doc.isDefault() != null && doc.isDefault() == 1,
                doc.disabled() != null && doc.disabled() == 1,
                toChargeRows(doc.taxes())
        );
    }

    // ---- Inner record -------------------------------------------------------

    /** Carries the resolved default template name, company, and invoice-ready tax rows. */
    public record DefaultTaxInfo(String name, String company, List<Map<String, Object>> taxes) {}

    // ---- Type references ----------------------------------------------------

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
