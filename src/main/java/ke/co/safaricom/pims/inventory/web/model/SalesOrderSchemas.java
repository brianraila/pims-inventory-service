package ke.co.safaricom.pims.inventory.web.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.UUID;

public final class SalesOrderSchemas {

    private SalesOrderSchemas() {}

    // ---- Requests -----------------------------------------------------------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateOrderRequest(
            /**
             * The ERPNext customer to bill. The prescription service resolves/syncs the customer and
             * passes its ERPNext name here; blank defaults to "Walk-in Customer".
             */
            String customerName,
            /** Optional prescription reference stored in remarks. */
            String prescriptionId,
            @NotEmpty @Valid List<OrderItem> items
    ) {
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public record OrderItem(
                @NotNull UUID productId,
                @NotNull @Positive Double quantity,
                @NotNull @Positive Double unitPrice
        ) {}
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateOrderItemsRequest(
            @NotEmpty @Valid List<CreateOrderRequest.OrderItem> items
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayOrderRequest(
            /** "cash" or "mpesa" */
            @NotEmpty String paymentMethod,
            @NotNull @Positive Double amountTendered,
            /** M-Pesa transaction/receipt reference. Also doubles as the idempotency key for redelivered events. */
            String transactionRef,
            String notes
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubmitOrderRequest(
            /** "cash" or "mpesa" */
            String paymentMethod,
            Double amountReceived,
            /** Required when paymentMethod is "mpesa". */
            String mpesaPhone,
            String notes
    ) {}

    // ---- Responses ----------------------------------------------------------

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderLineItem(
            String itemCode,
            String productName,
            double quantity,
            double unitPrice,
            double lineTotal
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderResponse(
            String orderId,
            String status,
            String customer,
            List<OrderLineItem> items,
            double subtotal,
            double taxAmount,
            double grandTotal,
            String currency,
            String createdAt
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderSummary(
            String orderId,
            String customer,
            String status,
            double grandTotal,
            int totalQty,
            String currency,
            String createdAt
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentResponse(
            String orderId,
            String status,
            String paymentMethod,
            double amountPaid,
            double changeDue,
            String transactionRef,
            String paymentEntryId
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderListResponse(
            List<OrderSummary> data,
            InventoryApiSchemas.Pagination pagination
    ) {}
}
