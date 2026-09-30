package hzpro.com.tradingdesk.repository;

import hzpro.com.tradingdesk.entity.Setting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettingsRepository extends JpaRepository<Setting, String> {
    Optional<Setting> findByKey(String key);
}
