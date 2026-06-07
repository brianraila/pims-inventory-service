package ke.co.safaricom.pims.inventory.config;

import ke.co.safaricom.pims.inventory.api.PurchaseOrderController;
import ke.co.safaricom.pims.inventory.exception.handler.GlobalExceptionHandler;
import ke.co.safaricom.pims.inventory.security.TenantContextResolver;
import ke.co.safaricom.pims.inventory.service.OrderPaymentService;
import ke.co.safaricom.pims.inventory.service.PurchaseOrderService;
import ke.co.safaricom.pims.inventory.service.ReceiptService;
import ke.co.safaricom.pims.inventory.service.SalesOrderService;
import ke.co.safaricom.pims.inventory.web.api.InventoryAdjustmentController;
import ke.co.safaricom.pims.inventory.web.api.InventoryBatchController;
import ke.co.safaricom.pims.inventory.web.api.InventoryProductController;
import ke.co.safaricom.pims.inventory.web.api.ManufacturersController;
import ke.co.safaricom.pims.inventory.web.api.SalesOrderController;
import ke.co.safaricom.pims.inventory.web.api.TerminologyController;
import ke.co.safaricom.pims.inventory.web.service.ProductInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Shared WebFlux slice for all inventory HTTP controller tests so Spring reuses one
 * application context instead of booting a separate context per controller class.
 */
@WebFluxTest(
        controllers = {
            InventoryProductController.class,
            InventoryBatchController.class,
            TerminologyController.class,
            SalesOrderController.class,
            ManufacturersController.class,
            InventoryAdjustmentController.class,
            PurchaseOrderController.class
        })
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
public abstract class AbstractInventoryControllerTest {

    @Autowired
    protected WebTestClient client;

    @MockBean
    protected ProductInventoryService productInventoryService;

    @MockBean
    protected TenantContextResolver tenants;

    @MockBean
    protected SalesOrderService salesOrderService;

    @MockBean
    protected OrderPaymentService orderPaymentService;

    @MockBean
    protected ReceiptService receiptService;

    @MockBean
    protected PurchaseOrderService purchaseOrderService;

    @BeforeEach
    void sharedControllerTestSetUp() {
        when(tenants.resolveTenantId(any(), any())).thenReturn(Mono.just("t1"));
    }
}
