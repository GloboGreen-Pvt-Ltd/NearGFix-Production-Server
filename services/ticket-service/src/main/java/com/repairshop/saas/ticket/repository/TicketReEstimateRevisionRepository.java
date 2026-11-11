package com.repairshop.saas.ticket.repository;

import com.repairshop.saas.ticket.entity.TicketReEstimateRevision;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TicketReEstimateRevisionRepository extends JpaRepository<TicketReEstimateRevision, UUID> {

    /** Full history, newest first. */
    List<TicketReEstimateRevision> findByTicketIdOrderByRevisionNoDesc(UUID ticketId);

    List<TicketReEstimateRevision> findByTicketIdAndStatusIn(UUID ticketId, Collection<String> statuses);

    /** Open revisions for a page of tickets in one query (Jobs list). */
    List<TicketReEstimateRevision> findByTicketIdInAndStatusIn(Collection<UUID> ticketIds, Collection<String> statuses);

    /** Open rows (REQUESTED / AWAITING_APPROVAL), row-locked for the rest of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TicketReEstimateRevision r WHERE r.ticketId = :ticketId AND r.status IN :statuses")
    List<TicketReEstimateRevision> lockByTicketIdAndStatusIn(@Param("ticketId") UUID ticketId,
                                                           @Param("statuses") Collection<String> statuses);

    /** One revision, row-locked — serialises concurrent decisions on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TicketReEstimateRevision r WHERE r.id = :id")
    Optional<TicketReEstimateRevision> lockById(@Param("id") UUID id);

    @Query("SELECT COALESCE(MAX(r.revisionNo), 0) FROM TicketReEstimateRevision r WHERE r.ticketId = :ticketId")
    int maxRevisionNo(@Param("ticketId") UUID ticketId);
}
