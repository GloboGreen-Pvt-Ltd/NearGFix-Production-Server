package com.repairshop.saas.ticket.service;

import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * "Owner as Technician": the shop owner works jobs through the normal
 * technician workflow using their own owner login. The owner is mapped to ONE
 * technicians row per shop (is_owner = true), reused if a row for the owner's
 * user id already exists, so no duplicate technician records are created.
 */
@Service
@RequiredArgsConstructor
public class OwnerTechnicianService {

    /** A technician's part is over once the job reaches these statuses. */
    static final Set<String> DONE_STATUSES = Set.of(
            "READY", "DELIVERED", "DELIVERED_PROCESSING", "DELIVERED_TO_CUSTOMER", "RETURNED",
            "RETURN_DELIVERY", "CANCELLED", "CLOSED", "COMPLETED");

    private final TechnicianRepository technicianRepository;
    private final TicketRepository ticketRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID shopId, UUID ownerUserId) {
        Optional<Technician> row = ownerRow(shopId, ownerUserId);
        boolean enabled = row.map(Technician::isOwnerTechEnabled).orElse(false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("technicianId", row.map(Technician::getId).orElse(null));
        out.putAll(counts(shopId, row.map(Technician::getId).orElse(null)));
        return out;
    }

    @Transactional
    public Map<String, Object> set(UUID shopId, UUID ownerUserId, boolean enabled, String ownerName) {
        Optional<Technician> existing = ownerRow(shopId, ownerUserId);
        if (!enabled) {
            existing.ifPresent(t -> {
                long active = (long) counts(shopId, t.getId()).get("activeCount");
                if (active > 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "You have " + active + " active repair job(s). Re-assign them before turning this off.");
                }
                t.setOwnerTechEnabled(false);
                technicianRepository.save(t);
            });
            return get(shopId, ownerUserId);
        }
        Technician t = existing.orElseGet(() -> Technician.builder()
                .shopId(shopId)
                .userId(ownerUserId)
                .name(ownerName == null || ownerName.isBlank() ? "Owner" : ownerName.trim())
                .roleLabel("Owner")
                .build());
        t.setOwner(true);
        t.setOwnerTechEnabled(true);
        technicianRepository.save(t);
        return get(shopId, ownerUserId);
    }

    /** Owner's row: the flagged one, else an existing row for the owner's user id (reused, not duplicated). */
    private Optional<Technician> ownerRow(UUID shopId, UUID ownerUserId) {
        Optional<Technician> flagged = technicianRepository.findOwnerRow(shopId);
        if (flagged.isPresent()) {
            if (ownerUserId != null && flagged.get().getUserId() != null && !ownerUserId.equals(flagged.get().getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the owner of this shop");
            }
            return flagged;
        }
        return ownerUserId == null ? Optional.empty() : technicianRepository.findByShopIdAndUserId(shopId, ownerUserId);
    }

    /** Counts of the owner's OWN technician jobs (never the whole shop's). */
    Map<String, Object> counts(UUID shopId, UUID technicianId) {
        long assigned = 0, pending = 0, completed = 0, all = 0;
        if (technicianId != null) {
            List<Ticket> mine = ticketRepository.findByShopIdAndAssignedTechnicianId(shopId, technicianId);
            for (Ticket t : mine) {
                all++;
                String st = t.getStatus() == null ? "" : t.getStatus().toUpperCase(Locale.ROOT);
                if (DONE_STATUSES.contains(st)) completed++;
                else if (t.getTechnicianAcceptedAt() == null) assigned++;
                else pending++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assignedCount", assigned);
        m.put("pendingCount", pending);
        m.put("completedCount", completed);
        m.put("allCount", all);
        m.put("activeCount", assigned + pending);
        return m;
    }
}
