package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.api.dto.CreateSupplierGroupRequest;
import ke.co.safaricom.pims.inventory.api.dto.CreateSupplierRequest;
import ke.co.safaricom.pims.inventory.api.dto.SupplierGroupResponse;
import ke.co.safaricom.pims.inventory.api.dto.SupplierResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.mapper.SupplierMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-aware CRUD for the ERPNext {@code Supplier} doctype. Suppliers created here are
 * immediately visible to the rest of inventory/stock (product create, purchase orders,
 * goods-receiving, batches) via the shared {@code /meta/suppliers} lookup, since they all
 * read the same ERPNext doctype.
 */
@Service
public class SupplierService {

    private static final String DOCTYPE_SUPPLIER = "Supplier";
    private static final String PARAM_FIELDS = "fields";
    private static final String PARAM_FILTERS = "filters";

    private static final String SUPPLIER_FIELDS =
            "[\"name\",\"supplier_name\",\"supplier_group\",\"supplier_type\",\"tax_id\","
                    + "\"country\",\"supplier_details\",\"disabled\",\"creation\",\"modified\"]";

    private final ErpNextTenantRouter router;
    private final SupplierMapper mapper;
    private final String defaultGroup;
    private final String defaultType;
    private final String defaultCountry;

    public SupplierService(ErpNextTenantRouter router,
                           SupplierMapper mapper,
                           @Value("${erpnext.supplier.default-group:All Supplier Groups}") String defaultGroup,
                           @Value("${erpnext.supplier.default-type:Company}") String defaultType,
                           @Value("${erpnext.supplier.default-country:Kenya}") String defaultCountry) {
        this.router = router;
        this.mapper = mapper;
        this.defaultGroup = defaultGroup;
        this.defaultType = defaultType;
        this.defaultCountry = defaultCountry;
    }

    public Mono<List<SupplierResponse>> listSuppliers(String tenantId, String search) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, SUPPLIER_FIELDS);
        params.put("order_by", "modified desc");
        if (search != null && !search.isBlank()) {
            String term = search.replace("\"", "").trim();
            params.put(PARAM_FILTERS, "[[\"supplier_name\",\"like\",\"%" + term + "%\"]]");
        }
        return router.getList(tenantId, DOCTYPE_SUPPLIER, params, LIST_TYPE)
                .map(response -> response.data().stream().map(mapper::toResponse).toList());
    }

    public Mono<SupplierResponse> getSupplier(String tenantId, String id) {
        return router.getOne(tenantId, DOCTYPE_SUPPLIER, id, SINGLE_TYPE)
                .map(response -> mapper.toResponse(response.data()));
    }

    public Mono<SupplierResponse> createSupplier(String tenantId, CreateSupplierRequest request) {
        Map<String, Object> body = buildBody(request);
        body.put("doctype", DOCTYPE_SUPPLIER);
        body.put("supplier_name", request.supplierName().trim());
        return router.create(tenantId, DOCTYPE_SUPPLIER, body, SINGLE_TYPE)
                .map(response -> mapper.toResponse(response.data()));
    }

    public Mono<SupplierResponse> updateSupplier(String tenantId, String id, CreateSupplierRequest request) {
        Map<String, Object> body = buildBody(request);
        return router.replace(tenantId, DOCTYPE_SUPPLIER, id, body, SINGLE_TYPE)
                .map(response -> mapper.toResponse(response.data()));
    }

    public Mono<Void> deleteSupplier(String tenantId, String id) {
        return router.delete(tenantId, DOCTYPE_SUPPLIER, id);
    }

    // ---- Supplier groups ----------------------------------------------------

    private static final String DOCTYPE_SUPPLIER_GROUP = "Supplier Group";
    private static final String GROUP_FIELDS =
            "[\"name\",\"supplier_group_name\",\"parent_supplier_group\",\"is_group\"]";

    public Mono<List<SupplierGroupResponse>> listSupplierGroups(String tenantId) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, GROUP_FIELDS);
        params.put("order_by", "name asc");
        return router.getList(tenantId, DOCTYPE_SUPPLIER_GROUP, params, LIST_TYPE)
                .map(response -> response.data().stream().map(this::toGroupResponse).toList());
    }

    public Mono<SupplierGroupResponse> createSupplierGroup(String tenantId, CreateSupplierGroupRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("doctype", DOCTYPE_SUPPLIER_GROUP);
        body.put("supplier_group_name", request.name().trim());
        body.put("parent_supplier_group", blankToDefault(request.parent(), defaultGroup));
        body.put("is_group", 0);
        return router.create(tenantId, DOCTYPE_SUPPLIER_GROUP, body, SINGLE_TYPE)
                .map(response -> toGroupResponse(response.data()));
    }

    public Mono<SupplierGroupResponse> updateSupplierGroup(String tenantId, String id,
                                                           CreateSupplierGroupRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("supplier_group_name", request.name().trim());
        if (notBlank(request.parent())) {
            body.put("parent_supplier_group", request.parent().trim());
        }
        return router.replace(tenantId, DOCTYPE_SUPPLIER_GROUP, id, body, SINGLE_TYPE)
                .map(response -> toGroupResponse(response.data()));
    }

    public Mono<Void> deleteSupplierGroup(String tenantId, String id) {
        return router.delete(tenantId, DOCTYPE_SUPPLIER_GROUP, id);
    }

    private SupplierGroupResponse toGroupResponse(ErpNextDoc doc) {
        return new SupplierGroupResponse(
                doc.name(),
                doc.supplierGroupName() != null ? doc.supplierGroupName() : doc.name(),
                doc.parentSupplierGroup(),
                doc.isGroup() != null && doc.isGroup() == 1
        );
    }

    /** Maps the request to ERPNext-standard Supplier fields; extras go into supplier_details. */
    private Map<String, Object> buildBody(CreateSupplierRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("supplier_group", blankToDefault(request.supplierGroup(), defaultGroup));
        body.put("supplier_type", blankToDefault(request.supplierType(), defaultType));
        body.put("country", blankToDefault(request.country(), defaultCountry));
        if (notBlank(request.registrationNumber())) {
            body.put("tax_id", request.registrationNumber().trim());
        }
        if (request.disabled() != null) {
            body.put("disabled", request.disabled() ? 1 : 0);
        }
        String details = composeDetails(request);
        if (!details.isBlank()) {
            body.put("supplier_details", details);
        }
        return body;
    }

    /** Structured, human-readable note holding contact + PPB/licensing metadata. */
    private String composeDetails(CreateSupplierRequest r) {
        List<String> lines = new ArrayList<>();
        addLine(lines, "Registration No", r.registrationNumber());
        addLine(lines, "Licence No", r.licenseNumber());
        addLine(lines, "Licence Type", r.licenseType());
        addLine(lines, "Licence Validity", r.licenseValidity());
        addLine(lines, "Ownership", r.ownership());
        addLine(lines, "Email", r.email());
        addLine(lines, "Phone", r.phone());
        addLine(lines, "County", r.county());
        addLine(lines, "Street", r.street());
        addLine(lines, "Notes", r.notes());
        return String.join("\n", lines);
    }

    private void addLine(List<String> lines, String label, String value) {
        if (notBlank(value)) {
            lines.add(label + ": " + value.trim());
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToDefault(String value, String fallback) {
        return notBlank(value) ? value.trim() : fallback;
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
