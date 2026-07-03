package ke.co.safaricom.pims.inventory.mapper;

import ke.co.safaricom.pims.inventory.api.dto.PurchaseOrderResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import org.springframework.stereotype.Component;

@Component
public class PurchaseOrderMapper {

    public PurchaseOrderResponse toResponse(ErpNextDoc doc) {
        int itemsCount = doc.items() != null ? doc.items().size()
                : (doc.itemsCount() != null ? doc.itemsCount() : 0);
        return new PurchaseOrderResponse(
                doc.name(),
                doc.name(),
                safeStr(doc.supplier()),
                safeStr(doc.transactionDate()),
                safeStr(doc.scheduleDate()),
                null,
                safeStr(doc.status()),
                itemsCount,
                doc.totalQty() != null ? doc.totalQty() : 0,
                doc.grandTotal() != null ? doc.grandTotal() : 0
        );
    }

    private String safeStr(String value) {
        return value != null ? value : "";
    }
}
