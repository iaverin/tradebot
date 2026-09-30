package hzpro.com.tradingdesk.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;

@Entity
@Table(name = "settings")
@Getter
@Setter
@NoArgsConstructor
public class Setting {

    @Id
    @Column(name = "key", nullable = false, unique = true, length = 255)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @CreationTimestamp
    @Column(updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    private ZonedDateTime updatedAt;
}
