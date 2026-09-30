package hzpro.com.tradingdesk.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import hzpro.com.tradingdesk.entity.enums.DataSource;

import java.time.ZonedDateTime;

@Entity
@Table(name = "fetching_prediction_markets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PredictionMarket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DataSource datasource;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "event_ticker")
    private String eventTicker;

    @Column(name = "event_title")
    private String eventTitle;

    @Column(name = "event_subtitle")
    private String eventSubtitle;

    @Column(name = "event_description")
    private String eventDescription;

    @Column(name = "market_ticker")
    private String marketTicker;

    @Column(name = "market_open_datetime", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private ZonedDateTime marketOpenDatetime;

    @Column(name = "market_close_datetime", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private ZonedDateTime marketCloseDatetime;

    @Column(name = "market_title")
    private String marketTitle;

    @Column(name = "market_description")
    private String marketDescription;

    @Column(name = "market_status", length = 100)
    private String marketStatus;

    @Column(name = "market_result")
    private String marketResult;

    @Column(name = "market_raw_data")
    private String marketRawData;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "TIMESTAMP WITH TIME ZONE", updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private ZonedDateTime updatedAt;

    @Column(name = "yes_token_id")
    private String yesTokenId;

    @Column(name = "no_token_id")
    private String noTokenId;

    @Column(name = "condition_id")
    private String conditionId;
}
