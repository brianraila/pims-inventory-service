package ke.co.safaricom.pims.inventory.service;

import ke.co.safaricom.pims.inventory.erpnext.ErpNextDoc;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextListResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextSingleResponse;
import ke.co.safaricom.pims.inventory.erpnext.ErpNextTenantRouter;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates a printable PDF receipt for a submitted POS order on demand from current ERPNext
 * state (no storage layer — always reflects the live invoice/payment). For pharmacy regulatory
 * compliance the receipt names the dispensing pharmacist (the authenticated user); batch numbers
 * and expiry dates are deferred along with batch tracking generally (see the plan's "Deferred"
 * section — they aren't captured on the order yet).
 *
 * "Amount tendered" / "change due" are POS-moment concepts that ERPNext doesn't persist against a
 * standard Sales Invoice + Payment Entry — they were already returned synchronously by
 * {@code POST /orders/{id}/pay}. This receipt instead reflects what's actually in the ledger: the
 * amount allocated against the invoice, the mode of payment, and the payment reference.
 */
@Service
public class ReceiptService {

    private static final Logger logger = LoggerFactory.getLogger(ReceiptService.class);

    private static final String SI_DOCTYPE = "Sales Invoice";
    private static final String PE_DOCTYPE = "Payment Entry";
    private static final String DEFAULT_CUSTOMER = "Walk-in Customer";
    private static final String DEFAULT_CURRENCY = "KES";

    private static final float MARGIN = 40f;
    private static final float LINE_GAP = 6f;

    private final ErpNextTenantRouter router;

    public ReceiptService(ErpNextTenantRouter router) {
        this.router = router;
    }

    /** Renders the receipt PDF for a submitted order; rejects drafts (nothing to receipt yet). */
    public Mono<byte[]> generateReceipt(String tenantId, String orderId, String dispensingPharmacist) {
        return router.getOne(tenantId, SI_DOCTYPE, orderId, SINGLE_TYPE).flatMap(resp -> {
            ErpNextDoc order = resp.data();
            if (order.docstatus() == null || order.docstatus() != 1) {
                return Mono.error(new ServiceValidationException(
                        "Receipt is only available once an order has been submitted"));
            }
            return findSubmittedPaymentEntry(tenantId, orderId)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .map(entry -> render(order, entry.orElse(null), dispensingPharmacist));
        });
    }

    private Mono<ErpNextDoc> findSubmittedPaymentEntry(String tenantId, String orderId) {
        Map<String, String> params = new HashMap<>();
        params.put("filters", "[[\"Payment Entry Reference\",\"reference_name\",\"=\",\"" + orderId
                + "\"],[\"docstatus\",\"=\",1]]");
        return router.getList(tenantId, PE_DOCTYPE, params, LIST_TYPE)
                .flatMap(r -> r.data().isEmpty() ? Mono.empty() : Mono.just(r.data().get(0)));
    }

    // ---- PDF rendering --------------------------------------------------------

    private byte[] render(ErpNextDoc order, ErpNextDoc payment, String dispensingPharmacist) {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A5);
            doc.addPage(page);
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            String currency = order.currency() != null ? order.currency() : DEFAULT_CURRENCY;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;

                y = line(cs, bold, 13, y, "Sales Receipt");
                y = line(cs, regular, 9, y, "Order: " + order.name() + "   Date: " + str(order.postingDate()));
                y = line(cs, regular, 9, y, "Customer: " + customerOf(order));
                y -= LINE_GAP;

                y = line(cs, bold, 9, y, String.format("%-26s %6s %10s %10s", "Item", "Qty", "Rate", "Amount"));
                for (Map<String, Object> item : itemsOf(order)) {
                    String name = truncate(str(item.get("item_name")), 26);
                    double qty = toDouble(item.get("qty"));
                    double rate = toDouble(item.get("rate"));
                    double amount = toDouble(item.get("amount"));
                    y = line(cs, regular, 9, y, String.format("%-26s %6.2f %10.2f %10.2f", name, qty, rate, amount));
                }
                y -= LINE_GAP;

                y = line(cs, regular, 9, y, totalLine("Subtotal", order.netTotal(), currency));
                y = line(cs, regular, 9, y, totalLine("Tax", order.totalTaxesAndCharges(), currency));
                y = line(cs, bold, 10, y, totalLine("Total", order.grandTotal(), currency));
                y -= LINE_GAP;

                if (payment != null) {
                    y = line(cs, regular, 9, y, "Payment method: " + str(payment.modeOfPayment()));
                    y = line(cs, regular, 9, y, "Amount paid: " + currency + " " + money(payment.paidAmount()));
                    if (StringUtils.hasText(payment.referenceNo())) {
                        y = line(cs, regular, 9, y, "Reference: " + payment.referenceNo()
                                + (StringUtils.hasText(payment.referenceDate()) ? " (" + payment.referenceDate() + ")" : ""));
                    }
                } else {
                    y = line(cs, regular, 9, y, "Payment: on account / pay later");
                }
                y -= LINE_GAP;

                line(cs, regular, 9, y, "Dispensed by: " + dispensingPharmacist);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            logger.error("Failed to generate receipt PDF for order {}", order.name(), e);
            throw new UncheckedIOException("Failed to generate receipt PDF for order " + order.name(), e);
        }
    }

    private static float line(PDPageContentStream cs, PDFont font, float size, float y, String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(MARGIN, y);
        cs.showText(text);
        cs.endText();
        return y - (size + LINE_GAP);
    }

    private static String totalLine(String label, Double value, String currency) {
        return String.format("%-38s %s %s", label + ":", currency, money(value));
    }

    private static List<Map<String, Object>> itemsOf(ErpNextDoc order) {
        return order.items() != null ? order.items() : List.of();
    }

    private static String customerOf(ErpNextDoc order) {
        return order.customer() != null ? order.customer() : DEFAULT_CUSTOMER;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static String money(Double value) {
        return String.format("%.2f", value != null ? value : 0);
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private static final ParameterizedTypeReference<ErpNextListResponse<ErpNextDoc>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ErpNextSingleResponse<ErpNextDoc>> SINGLE_TYPE =
            new ParameterizedTypeReference<>() {};
}
