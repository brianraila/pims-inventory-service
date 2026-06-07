package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ResourceNotFoundException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.web.model.InventoryApiSchemas;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CategoryService {

    private static final String DOCTYPE_ITEM_GROUP = "Item Group";
    private static final String DEFAULT_PARENT = "All Item Groups";
    private static final String PARAM_FIELDS = "fields";
    private static final String ITEM_GROUP_FIELDS =
            "[\"name\",\"item_group_name\",\"parent_item_group\",\"is_group\"]";

    private final ErpNextTenantRouter router;

    public CategoryService(ErpNextTenantRouter router) {
        this.router = router;
    }

    public Mono<List<InventoryApiSchemas.Category>> listCategories(String tenantId) {
        return listCategories(tenantId, null, null, null);
    }

    public Mono<List<InventoryApiSchemas.Category>> listCategories(
            String tenantId, Boolean isGroup, String parentCategory, String search) {
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_FIELDS, ITEM_GROUP_FIELDS);
        params.put("limit_page_length", "200");
        // ERPNext supports like-filtering on string fields; is_group and parent_item_group
        // are tree/boolean fields that ERPNext ignores in the filters param, so those are
        // applied in-memory below.
        if (StringUtils.hasText(search))
            params.put("filters", "[[\"item_group_name\",\"like\",\"%" + search + "%\"]]");
        return router.getList(tenantId, DOCTYPE_ITEM_GROUP, params, LIST_TYPE)
                .map(r -> r.data().stream()
                        .map(this::toCategory)
                        .filter(c -> isGroup == null || isGroup.equals(c.isGroup()))
                        .filter(c -> !StringUtils.hasText(parentCategory)
                                || parentCategory.equals(c.parentCategory()))
                        .toList());
    }

    public Mono<InventoryApiSchemas.Category> getCategory(String tenantId, String id) {
        return router.getOne(tenantId, DOCTYPE_ITEM_GROUP, id, SINGLE_TYPE)
                .map(r -> toCategory(r.data()))
                .onErrorMap(ResourceNotFoundException.class,
                        ex -> new ResourceNotFoundException("Category not found: " + id));
    }

    public Mono<InventoryApiSchemas.Category> createCategory(
            String tenantId, InventoryApiSchemas.CreateCategoryRequest req) {
        Map<String, Object> body = buildItemGroupBody(
                req.name(),
                req.parentCategory(),
                req.isGroup());
        return router.create(tenantId, DOCTYPE_ITEM_GROUP, body, SINGLE_TYPE)
                .map(r -> toCategory(r.data()));
    }

    public Mono<InventoryApiSchemas.Category> updateCategory(
            String tenantId, String id, InventoryApiSchemas.UpdateCategoryRequest req) {
        return router.getOne(tenantId, DOCTYPE_ITEM_GROUP, id, SINGLE_TYPE)
                .flatMap(existing -> {
                    ErpNextDoc doc = existing.data();
                    String name = StringUtils.hasText(req.name()) ? req.name() : displayName(doc);
                    String parent = req.parentCategory() != null
                            ? req.parentCategory()
                            : doc.parentItemGroup();
                    boolean isGroup = req.isGroup() != null
                            ? req.isGroup()
                            : doc.isGroup() != null && doc.isGroup() == 1;
                    Map<String, Object> body = buildItemGroupBody(name, parent, isGroup);
                    body.put("name", doc.name());
                    return router.replace(tenantId, DOCTYPE_ITEM_GROUP, doc.name(), body, SINGLE_TYPE)
                            .map(r -> toCategory(r.data()));
                })
                .onErrorMap(ResourceNotFoundException.class,
                        ex -> new ResourceNotFoundException("Category not found: " + id));
    }

    public Mono<Void> deleteCategory(String tenantId, String id) {
        return router.delete(tenantId, DOCTYPE_ITEM_GROUP, id)
                .onErrorMap(ResourceNotFoundException.class,
                        ex -> new ResourceNotFoundException("Category not found: " + id));
    }

    public Mono<Void> validateLeafCategoryExists(String tenantId, String category) {
        if (!StringUtils.hasText(category)) {
            return Mono.error(new ServiceValidationException("category is required"));
        }
        return listCategories(tenantId).flatMap(categories -> {
            List<InventoryApiSchemas.Category> matches = categories.stream()
                    .filter(c -> category.equals(c.id()) || category.equals(c.name()))
                    .toList();
            if (matches.isEmpty()) {
                String available = categories.stream()
                        .map(InventoryApiSchemas.Category::name)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("(none)");
                return Mono.error(new ServiceValidationException(
                        "Category '" + category + "' does not exist. Available categories: " + available));
            }
            if (matches.stream().anyMatch(c -> Boolean.TRUE.equals(c.isGroup()))) {
                return Mono.error(new ServiceValidationException(
                        "Category '" + category + "' is a group folder — products must use a leaf category"));
            }
            return Mono.empty();
        });
    }

    private static Map<String, Object> buildItemGroupBody(String name, String parentCategory, Boolean isGroup) {
        Map<String, Object> body = new HashMap<>();
        body.put("doctype", DOCTYPE_ITEM_GROUP);
        body.put("item_group_name", name);
        body.put("parent_item_group", StringUtils.hasText(parentCategory) ? parentCategory : DEFAULT_PARENT);
        body.put("is_group", Boolean.TRUE.equals(isGroup) ? 1 : 0);
        return body;
    }

    private InventoryApiSchemas.Category toCategory(ErpNextDoc doc) {
        return new InventoryApiSchemas.Category(
                doc.name(),
                displayName(doc),
                doc.parentItemGroup(),
                doc.isGroup() != null && doc.isGroup() == 1);
    }

    private static String displayName(ErpNextDoc doc) {
        return StringUtils.hasText(doc.itemGroupName()) ? doc.itemGroupName() : doc.name();
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
