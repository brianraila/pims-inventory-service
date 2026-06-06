package ke.co.safaricom.pims.inventory.web.api;

import ke.co.safaricom.pims.inventory.config.TestSecurityConfig;
import ke.co.safaricom.pims.inventory.exception.ConflictException;
import ke.co.safaricom.pims.inventory.exception.ServiceValidationException;
import ke.co.safaricom.pims.inventory.exception.handler.GlobalExceptionHandler;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PaymentDetails;
import ke.co.safaricom.pims.inventory.service.ReceiptService;
import ke.co.safaricom.pims.inventory.service.SalesOrderService;
import ke.co.safaricom.pims.inventory.web.model.SalesOrderSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = SalesOrderController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class})
class SalesOrderControllerTest {

    private static final String BASE = "/api/v1/inventory/orders";
    private static final String ORDER_ID = "SINV-2024-00001";

    @Autowired
    private WebTestClient client;

    @MockBean
    private SalesOrderService salesOrderService;

    @MockBean
    private OrderPaymentService orderPaymentService;

    @MockBean
    private ReceiptService receiptService;

    @MockBean
    private TenantContextResolver tenants;

    @BeforeEach
    void setUp() {
        when(tenants.resolveTenantId(any(), any())).thenReturn(Mono.just("t1"));
    }

    // ---- POST /{order_id}/pay --------------------------------------------------

    @Test
    void pay_returns_200_with_payment_response() {
        SalesOrderSchemas.PaymentResponse response = new SalesOrderSchemas.PaymentResponse(
                ORDER_ID, "recorded", "cash", 1500.0, 500.0, null, "PE-0001");
        when(orderPaymentService.recordPayment(eq("t1"), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.just(response));

        client.post().uri(BASE + "/" + ORDER_ID + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"payment_method\":\"cash\",\"amount_tendered\":1500.0}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.order_id").isEqualTo(ORDER_ID)
                .jsonPath("$.status").isEqualTo("recorded")
                .jsonPath("$.change_due").isEqualTo(500.0)
                .jsonPath("$.payment_entry_id").isEqualTo("PE-0001");
    }

    @Test
    void pay_returns_400_when_payment_method_missing() {
        client.post().uri(BASE + "/" + ORDER_ID + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"amount_tendered\":1500.0}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void pay_returns_400_when_amount_tendered_not_positive() {
        client.post().uri(BASE + "/" + ORDER_ID + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"payment_method\":\"cash\",\"amount_tendered\":-5}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void pay_returns_409_when_order_already_submitted() {
        when(orderPaymentService.recordPayment(eq("t1"), eq(ORDER_ID), any(PaymentDetails.class)))
                .thenReturn(Mono.error(new ConflictException("Order has already been submitted")));

        client.post().uri(BASE + "/" + ORDER_ID + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"payment_method\":\"cash\",\"amount_tendered\":1500.0}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONFLICT");
    }

    // ---- GET /{order_id}/receipt ------------------------------------------------

    @Test
    void receipt_returns_pdf_with_content_type_and_disposition() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46};
        when(receiptService.generateReceipt(eq("t1"), eq(ORDER_ID), anyString()))
                .thenReturn(Mono.just(pdf));

        client.get().uri(BASE + "/" + ORDER_ID + "/receipt")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + ORDER_ID + "-receipt.pdf\"")
                .expectBody(byte[].class).isEqualTo(pdf);
    }

    @Test
    void receipt_returns_400_when_order_is_still_draft() {
        when(receiptService.generateReceipt(eq("t1"), eq(ORDER_ID), anyString()))
                .thenReturn(Mono.error(new ServiceValidationException("Receipt is only available once an order has been submitted")));

        client.get().uri(BASE + "/" + ORDER_ID + "/receipt")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("BAD_REQUEST");
    }

    @Test
    void receipt_uses_jwt_name_claim_as_dispensing_pharmacist() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46};
        when(receiptService.generateReceipt("t1", ORDER_ID, "Dr. Jane Mwangi"))
                .thenReturn(Mono.just(pdf));

        client.mutateWith(SecurityMockServerConfigurers.mockJwt().jwt(jwt -> jwt.claim("name", "Dr. Jane Mwangi")))
                .get().uri(BASE + "/" + ORDER_ID + "/receipt")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void receipt_falls_back_to_unknown_pharmacist_when_no_jwt_present() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46};
        when(receiptService.generateReceipt("t1", ORDER_ID, "unknown"))
                .thenReturn(Mono.just(pdf));

        client.get().uri(BASE + "/" + ORDER_ID + "/receipt")
                .exchange()
                .expectStatus().isOk();
    }
}
