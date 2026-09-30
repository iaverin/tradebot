package hzpro.com.tradingdesk.arbitrage.repository;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEventMarketsInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArbitrageEventMarketsInfoRepository extends JpaRepository<ArbitrageEventMarketsInfo, Long> {

    @Query("SELECT m FROM ArbitrageEventMarketsInfo m WHERE m.uuid IN :uuids")
    List<ArbitrageEventMarketsInfo> findByUuidIn(@Param("uuids") List<UUID> uuids);
}