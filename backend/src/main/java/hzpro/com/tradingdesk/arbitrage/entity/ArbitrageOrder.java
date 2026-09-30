package hzpro.com.tradingdesk.arbitrage.entity;

import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "arbitrage_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "opportunity_uuid", nullable = false)
    private UUID opportunityUuid;

    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    @Column(name = "contract_type", nullable = false, length = 10)
    private String contractType;

    @Column(name = "price", nullable = false, precision = 8, scale = 6)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private Long quantity;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private OrderState status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "filled_quantity")
    private Long filledQuantity;

    @Column(name = "filled_amount", precision = 10, scale = 2)
    private BigDecimal filledAmount;

    @Column(name = "executed_at")
    private Instant executedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (status == null) status = OrderState.CREATED;
    }
}