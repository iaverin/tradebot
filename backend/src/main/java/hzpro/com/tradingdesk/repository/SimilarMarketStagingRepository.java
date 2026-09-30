package hzpro.com.tradingdesk.repository;

import hzpro.com.tradingdesk.entity.SimilarMarketStaging;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SimilarMarketStagingRepository extends JpaRepository<SimilarMarketStaging, Long> {
}
