package io.rbgs.api.seasons;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeasonRepository extends JpaRepository<SeasonEntity, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select s from SeasonEntity s where s.id = :id")
    java.util.Optional<SeasonEntity> findForSelection(@org.springframework.data.repository.query.Param("id") UUID id);

}
