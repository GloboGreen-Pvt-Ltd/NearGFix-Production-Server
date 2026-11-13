package com.repairshop.saas.ticket.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.ticket.entity.PlatformRepairBooking;
import com.repairshop.saas.ticket.entity.RepairDelivery;
import com.repairshop.saas.ticket.entity.RepairDeliveryEvent;
import com.repairshop.saas.ticket.entity.Technician;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.exception.ResourceNotFoundException;
import com.repairshop.saas.ticket.repository.InvoiceRepository;
import com.repairshop.saas.ticket.repository.PlatformRepairBookingEventRepository;
import com.repairshop.saas.ticket.repository.PlatformRepairBookingRepository;
import com.repairshop.saas.ticket.repository.RepairDeliveryEventRepository;
import com.repairshop.saas.ticket.repository.RepairDeliveryRepository;
import com.repairshop.saas.ticket.repository.TechnicianRepository;
import com.repairshop.saas.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Doorstep RETURN delivery of a repaired (or returned-unrepaired) device.
 *
 * Status flow (delivery row):
 *   UNASSIGNED (scheduled, no employee) → ASSIGNED → ACCEPTED → [shop releases]
 *   → COLLECTED → OUT_FOR_DELIVERY → ARRIVED → DELIVERED
 *   failure: OUT_FOR_DELIVERY/ARRIVED → FAILED → RETURNING → RETURNED (at shop) → schedule retry.
 *
 * The ticket is marked DELIVERED only after the customer's OTP is verified on
 * the server; it is moved to Out for Delivery (DELIVERED_PROCESSING) when the
 * employee actually starts, if it isn't there already. Everything goes through
 * TicketService.updateStatus, so the existing history/mirror logic is reused.
 */
@Service
@RequiredArgsConstructor
public class RepairDeliveryService {

    static final Duration OTP_TTL = Duration.ofHours(6);
    static final int OTP_MAX_SENDS = 3;
    static final int OTP_MAX_FAILED = 5;
    static final int MIN_COLLECT_PHOTOS = 2;

    private static final Set<String> RETURN_KEYS = Set.of("REPAIR_NOT_COMPLETED", "CUSTOMER_REJECTED", "RETURN_DELIVERY");
    private static final Set<String> QC_KEYS = Set.of("QUALITY_CHECK_COMPLETED", "QC_COMPLETED", "QC_PASSED");
    private static final Set<String> CLOSED_TICKET = Set.of("DELIVERED", "CANCELLED", "CLOSED", "RETURNED");
    private static final Set<String> NOT_RELEASED_EDITABLE = Set.of("UNASSIGNED", "ASSIGNED", "ACCEPTED");
    private static final Map<String, String> LABEL = Map.ofEntries(
            Map.entry("UNASSIGNED", "Scheduled"),
            Map.entry("ASSIGNED", "Employee Assigned"),
            Map.entry("ACCEPTED", "Employee Accepted"),
            Map.entry("COLLECTED", "Collected from Shop"),
            Map.entry("OUT_FOR_DELIVERY", "Out for Delivery"),
            Map.entry("ARRIVED", "Arrived at Customer"),
            Map.entry("DELIVERED", "Delivered to Customer"),
            Map.entry("FAILED", "Delivery Failed"),
            Map.entry("RETURNING", "Returning to Shop"),
            Map.entry("RETURNED", "Returned to Shop"));

    private final RepairDeliveryRepository deliveries;
    private final RepairDeliveryEventRepository events;
    private final TicketRepository tickets;
    private final TechnicianRepository technicians;
    private final PlatformRepairBookingRepository bookings;
    private final PlatformRepairBookingEventRepository bookingEvents;
    private final InvoiceRepository invoices;
    private final TicketService ticketService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    // ------------------------------------------------------------------ owner

    @Transactional(readOnly = true)
    public Map<String, Object> getForShop(UUID shopId, UUID ticketId) {
        Ticket t = ticket(shopId, ticketId);
        Optional<RepairDelivery> d = deliveries.findByTicketId(ticketId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eligibility", eligibility(t, d.orElse(null)));
        out.put("delivery", d.map(x -> view(x, false)).orElse(null));
        out.put("events", d.map(x -> eventViews(x.getId())).orElse(List.of()));
        return out;
    }

    @Transactional
    public Map<String, Object> schedule(UUID shopId, UUID ticketId, Map<String, Object> body) {
        Ticket t = ticket(shopId, ticketId);
        RepairDelivery d = deliveries.lockByTicketId(ticketId).orElse(null);
        Map<String, Object> el = eligibility(t, d);
        if (!Boolean.TRUE.equals(el.get("canSchedule"))) {
            throw conflict(String.join(" ", castList(el.get("reasons"))));
        }
        PlatformRepairBooking b = bookings.findByTicketId(ticketId).orElseThrow();
        boolean retry = d != null;
        if (d == null) {
            d = RepairDelivery.builder().ticketId(ticketId).shopId(shopId).build();
        } else {
            d.setAttempt(d.getAttempt() + 1);
            resetAttempt(d);
        }
        d.setRepairBookingId(b.getId());
        d.setCustomerUserId(b.getCustomerUserId());
        d.setReturnPath(Boolean.TRUE.equals(el.get("returnPath")));
        d.setAmountDue(amountDue(t, d.isReturnPath()));
        applyAddress(d, b, t);
        applySlot(d, body);
        d.setNote(str(body.get("note")));
        UUID techId = uuid(body.get("technicianId"));
        if (techId != null) {
            d.setTechnicianId(employee(shopId, techId).getId());
            d.setStatus("ASSIGNED");
        } else {
            d.setStatus("UNASSIGNED");
        }
        d = deliveries.save(d);
        event(d, d.getStatus(), retry ? "Delivery retry scheduled (attempt " + d.getAttempt() + ")" : "Delivery scheduled", null, "SHOP");
        return getForShop(shopId, ticketId);
    }

    @Transactional
    public Map<String, Object> assign(UUID shopId, UUID ticketId, UUID technicianId) {
        ticket(shopId, ticketId);
        RepairDelivery d = locked(ticketId);
        if (!NOT_RELEASED_EDITABLE.contains(d.getStatus()) || d.getReleasedAt() != null) {
            throw conflict("The employee can't be changed after the device has left the shop.");
        }
        Technician tech = employee(shopId, technicianId);
        boolean re = d.getTechnicianId() != null && !d.getTechnicianId().equals(tech.getId());
        if (tech.getId().equals(d.getTechnicianId()) && !"UNASSIGNED".equals(d.getStatus())) return getForShop(shopId, ticketId);
        d.setTechnicianId(tech.getId());
        d.setStatus("ASSIGNED");
        d.setAcceptedAt(null);
        d.setDeclinedReason(null);
        deliveries.save(d);
        event(d, "ASSIGNED", (re ? "Reassigned to " : "Assigned to ") + tech.getName(), null, "SHOP");
        return getForShop(shopId, ticketId);
    }

    @Transactional
    public Map<String, Object> reschedule(UUID shopId, UUID ticketId, Map<String, Object> body) {
        ticket(shopId, ticketId);
        RepairDelivery d = locked(ticketId);
        if (!NOT_RELEASED_EDITABLE.contains(d.getStatus()) || d.getReleasedAt() != null) {
            throw conflict("The delivery can only be rescheduled before the device leaves the shop.");
        }
        applySlot(d, body);
        deliveries.save(d);
        event(d, d.getStatus(), "Rescheduled to " + d.getScheduledDate() + " " + d.getSlotStart() + "–" + d.getSlotEnd(), null, "SHOP");
        return getForShop(shopId, ticketId);
    }

    /** Shop hands the device to the employee who accepted the delivery. */
    @Transactional
    public Map<String, Object> release(UUID shopId, UUID ticketId) {
        Ticket t = ticket(shopId, ticketId);
        RepairDelivery d = locked(ticketId);
        if (!"ACCEPTED".equals(d.getStatus())) throw conflict("Release the device only after the employee accepts the delivery.");
        if (d.getReleasedAt() != null) return getForShop(shopId, ticketId); // duplicate tap
        if (CLOSED_TICKET.contains(upper(t.getStatus()))) throw conflict("This booking is already closed.");
        d.setReleasedAt(Instant.now());
        deliveries.save(d);
        event(d, "ACCEPTED", "Device released to " + techName(d), null, "SHOP");
        return getForShop(shopId, ticketId);
    }

    /** The device is back at the shop after a failed attempt. */
    @Transactional
    public Map<String, Object> receiveReturn(UUID shopId, UUID ticketId) {
        ticket(shopId, ticketId);
        RepairDelivery d = locked(ticketId);
        if ("RETURNED".equals(d.getStatus())) return getForShop(shopId, ticketId);
        if (!Set.of("FAILED", "RETURNING").contains(d.getStatus())) throw conflict("Only a failed delivery can be received back.");
        d.setStatus("RETURNED");
        d.setReturnedAt(Instant.now());
        d.setOtpCode(null);
        d.setOtpExpiresAt(null);
        deliveries.save(d);
        event(d, "RETURNED", "Device received back at the shop", null, "SHOP");
        return getForShop(shopId, ticketId);
    }

    // --------------------------------------------------------------- employee

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listMine(UUID shopId, UUID userId) {
        Technician me = me(shopId, userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RepairDelivery d : deliveries.findByShopIdAndTechnicianIdOrderByUpdatedAtDesc(shopId, me.getId())) {
            out.add(employeeView(d));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMine(UUID shopId, UUID userId, UUID id) {
        return employeeView(mine(shopId, userId, id, false));
    }

    @Transactional
    public Map<String, Object> employeeAction(UUID shopId, UUID userId, UUID id, String action, Map<String, Object> body) {
        RepairDelivery d = mine(shopId, userId, id, true);
        Map<String, Object> b = body == null ? Map.of() : body;
        Instant now = Instant.now();
        switch (action) {
            case "accept" -> {
                if ("ACCEPTED".equals(d.getStatus())) return employeeView(d);
                requireStatus(d, "ASSIGNED");
                d.setStatus("ACCEPTED");
                d.setAcceptedAt(now);
                save(d, "ACCEPTED", "Employee accepted the delivery", null);
            }
            case "decline" -> {
                if (!Set.of("ASSIGNED", "ACCEPTED").contains(d.getStatus()) || d.getReleasedAt() != null) {
                    throw conflict("You can decline only before collecting the device.");
                }
                String reason = str(b.get("reason"));
                d.setStatus("UNASSIGNED");
                d.setTechnicianId(null);
                d.setAcceptedAt(null);
                d.setDeclinedReason(reason);
                save(d, "UNASSIGNED", "Employee declined the delivery", reason);
            }
            case "collect" -> {
                if ("COLLECTED".equals(d.getStatus())) return employeeView(d);
                requireStatus(d, "ACCEPTED");
                if (d.getReleasedAt() == null) throw conflict("Ask the shop to release the device first.");
                String identifier = str(b.get("identifier"));
                if (identifier == null) throw conflict("Enter the device IMEI / serial number.");
                Ticket t = tickets.findById(d.getTicketId()).orElseThrow();
                if (!identifierMatches(t, identifier)) throw conflict("IMEI / serial doesn't match this booking's device.");
                List<String> photos = urls(b.get("photos"));
                if (photos.size() < MIN_COLLECT_PHOTOS) throw conflict("Take at least " + MIN_COLLECT_PHOTOS + " photos of the device.");
                d.setCollectedIdentifier(identifier);
                d.setAccessoriesJson(write(strings(b.get("accessories"))));
                d.setCollectPhotosJson(write(photos));
                d.setStatus("COLLECTED");
                d.setCollectedAt(now);
                save(d, "COLLECTED", "Device collected from the shop", null);
            }
            case "start" -> {
                if ("OUT_FOR_DELIVERY".equals(d.getStatus())) return employeeView(d);
                requireStatus(d, "COLLECTED");
                d.setStatus("OUT_FOR_DELIVERY");
                d.setStartedAt(now);
                newOtp(d, now);
                save(d, "OUT_FOR_DELIVERY", "Out for delivery", null);
                Ticket t = tickets.findById(d.getTicketId()).orElseThrow();
                if (!"DELIVERED_PROCESSING".equalsIgnoreCase(t.getStatus())) {
                    ticketService.updateStatus(d.getShopId(), d.getTicketId(), "DELIVERED_PROCESSING", "TECHNICIAN");
                }
            }
            case "arrive" -> {
                if ("ARRIVED".equals(d.getStatus())) return employeeView(d);
                requireStatus(d, "OUT_FOR_DELIVERY");
                d.setStatus("ARRIVED");
                d.setArrivedAt(now);
                save(d, "ARRIVED", "Arrived at the customer", null);
            }
            case "otp/resend" -> {
                if (!Set.of("OUT_FOR_DELIVERY", "ARRIVED").contains(d.getStatus())) throw conflict("No delivery in progress.");
                if (d.getOtpSentCount() >= OTP_MAX_SENDS) throw conflict("OTP resend limit reached. Contact the shop.");
                newOtp(d, now);
                deliveries.save(d);
            }
            case "complete" -> {
                if ("DELIVERED".equals(d.getStatus())) throw conflict("This delivery is already completed.");
                if (!Set.of("OUT_FOR_DELIVERY", "ARRIVED").contains(d.getStatus())) throw conflict("Start the delivery first.");
                verifyOtp(d, str(b.get("otp")), now);
                applyPayment(d, b);
                d.setProofPhotosJson(write(urls(b.get("photos"))));
                d.setStatus("DELIVERED");
                d.setDeliveredAt(now);
                d.setOtpCode(null);
                d.setOtpExpiresAt(null);
                save(d, "DELIVERED", "Delivered to the customer (OTP verified)", paymentNote(d));
                recordPaymentOnTicket(d);
                ticketService.updateStatus(d.getShopId(), d.getTicketId(), "DELIVERED", "TECHNICIAN");
            }
            case "fail" -> {
                if (!Set.of("OUT_FOR_DELIVERY", "ARRIVED").contains(d.getStatus())) throw conflict("Only a delivery in progress can fail.");
                String reason = str(b.get("reason"));
                if (reason == null) throw conflict("Enter why the delivery failed.");
                d.setStatus("FAILED");
                d.setFailedAt(now);
                d.setFailReason(reason);
                d.setOtpCode(null);
                d.setOtpExpiresAt(null);
                save(d, "FAILED", "Delivery failed", reason);
            }
            case "return" -> {
                if ("RETURNING".equals(d.getStatus())) return employeeView(d);
                requireStatus(d, "FAILED");
                d.setStatus("RETURNING");
                save(d, "RETURNING", "Returning the device to the shop", null);
            }
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown action");
        }
        return employeeView(d);
    }

    // --------------------------------------------------------------- customer

    @Transactional(readOnly = true)
    public Map<String, Object> getForCustomer(UUID customerUserId, UUID ticketId) {
        Ticket t = tickets.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        if (customerUserId == null || t.getCustomerId() == null || !t.getCustomerId().equals(customerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your booking");
        }
        Optional<RepairDelivery> d = deliveries.findByTicketId(ticketId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketStatus", t.getStatus());
        out.put("qcPassed", qcPassed(t));
        out.put("delivery", d.map(x -> {
            Map<String, Object> v = view(x, false);
            // The OTP is shown to the customer only while the device is on its way.
            if (Set.of("OUT_FOR_DELIVERY", "ARRIVED").contains(x.getStatus()) && x.getOtpExpiresAt() != null
                    && x.getOtpExpiresAt().isAfter(Instant.now())) {
                v.put("otp", x.getOtpCode());
            }
            return v;
        }).orElse(null));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    Map<String, Object> eligibility(Ticket t, RepairDelivery d) {
        List<String> reasons = new ArrayList<>();
        Optional<PlatformRepairBooking> b = bookings.findByTicketId(t.getId());
        boolean doorstep = b.map(x -> "PICKUP".equalsIgnoreCase(x.getServiceMode())).orElse(false);
        Set<String> keys = currentKeys(b.orElse(null));
        boolean returnPath = keys.stream().anyMatch(RETURN_KEYS::contains);
        boolean qc = qcPassed(t) || keys.stream().anyMatch(QC_KEYS::contains);
        boolean invoice = invoices.findByTicketId(t.getId()).isPresent();
        String st = upper(t.getStatus());
        boolean active = d != null && !Set.of("RETURNED", "DELIVERED").contains(d.getStatus());
        boolean redelivery = d != null && "DELIVERED".equals(d.getStatus()) && !CLOSED_TICKET.contains(st);
        if (!doorstep) reasons.add("Only doorstep-pickup bookings get a doorstep return.");
        if (CLOSED_TICKET.contains(st)) reasons.add("The booking is already closed.");
        if (!returnPath && !qc) reasons.add("Complete the quality check first.");
        if (!returnPath && !invoice) reasons.add("Generate the invoice first.");
        if (active) reasons.add("A delivery is already scheduled.");
        if (d != null && "DELIVERED".equals(d.getStatus()) && !redelivery) reasons.add("Already delivered.");
        Map<String, Object> el = new LinkedHashMap<>();
        el.put("ticketStatus", t.getStatus());
        el.put("doorstep", doorstep);
        el.put("returnPath", returnPath);
        el.put("qcPassed", qc);
        el.put("invoice", invoice);
        el.put("canSchedule", reasons.isEmpty());
        el.put("reasons", reasons);
        return el;
    }

    private Set<String> currentKeys(PlatformRepairBooking b) {
        if (b == null) return Set.of();
        Set<String> keys = new HashSet<>();
        bookingEvents.findCurrentCycle(b.getId()).forEach(e -> keys.add(upper(e.getStatus())));
        return keys;
    }

    private static boolean qcPassed(Ticket t) {
        return Set.of("READY", "INVOICE_GENERATED", "INVOICE_READY", "DELIVERED_PROCESSING", "DELIVERED").contains(upper(t.getStatus()));
    }

    private BigDecimal amountDue(Ticket t, boolean returnPath) {
        // Unrepaired returns only collect what an invoice actually asks for.
        if (returnPath && invoices.findByTicketId(t.getId()).isEmpty()) return BigDecimal.ZERO;
        BigDecimal bal = t.getBalanceAmount();
        return bal == null || bal.signum() < 0 ? BigDecimal.ZERO : bal;
    }

    void verifyOtp(RepairDelivery d, String otp, Instant now) {
        if (d.getOtpCode() == null || d.getOtpExpiresAt() == null || now.isAfter(d.getOtpExpiresAt())) {
            throw conflict("The OTP has expired. Resend it to the customer.");
        }
        if (d.getOtpFailedAttempts() >= OTP_MAX_FAILED) throw conflict("Too many wrong OTPs. Resend the OTP.");
        if (otp == null || !java.security.MessageDigest.isEqual(otp.getBytes(), d.getOtpCode().getBytes())) {
            d.setOtpFailedAttempts(d.getOtpFailedAttempts() + 1);
            deliveries.save(d);
            throw conflict("Wrong OTP. Ask the customer for the 4-digit code on their repair page.");
        }
    }

    private void newOtp(RepairDelivery d, Instant now) {
        d.setOtpCode(String.format("%04d", random.nextInt(10_000)));
        d.setOtpExpiresAt(now.plus(OTP_TTL));
        d.setOtpSentCount(d.getOtpSentCount() + 1);
        d.setOtpFailedAttempts(0);
    }

    void applyPayment(RepairDelivery d, Map<String, Object> b) {
        BigDecimal due = d.getAmountDue() == null ? BigDecimal.ZERO : d.getAmountDue();
        if (due.signum() <= 0) {
            d.setAmountCollected(BigDecimal.ZERO);
            return;
        }
        BigDecimal got = amount(b.get("amountCollected"));
        if (got == null || got.compareTo(due) != 0) throw conflict("Collect the full amount due: ₹" + due.toPlainString());
        String method = upper(str(b.get("paymentMethod")));
        if (!Set.of("CASH", "UPI").contains(method)) throw conflict("Choose Cash or UPI.");
        String ref = str(b.get("paymentRef"));
        if ("UPI".equals(method) && ref == null && urls(b.get("photos")).isEmpty()) {
            throw conflict("Add the UPI reference or a payment screenshot.");
        }
        d.setAmountCollected(got);
        d.setPaymentMethod(method);
        d.setPaymentRef(ref);
    }

    /** Adds the doorstep collection to the ticket's paid amount exactly once (completion is row-locked). */
    private void recordPaymentOnTicket(RepairDelivery d) {
        if (d.getAmountCollected() == null || d.getAmountCollected().signum() <= 0) return;
        jdbc.update("UPDATE tickets SET payment_amount = COALESCE(payment_amount, 0) + ?, "
                        + "balance_amount = GREATEST(COALESCE(balance_amount, 0) - ?, 0), payment_status = 'PAID' WHERE id = ?",
                d.getAmountCollected(), d.getAmountCollected(), d.getTicketId());
    }

    private static String paymentNote(RepairDelivery d) {
        if (d.getAmountCollected() == null || d.getAmountCollected().signum() <= 0) return null;
        return "Collected ₹" + d.getAmountCollected().toPlainString() + " by " + d.getPaymentMethod()
                + (d.getPaymentRef() != null ? " (" + d.getPaymentRef() + ")" : "");
    }

    static boolean identifierMatches(Ticket t, String typed) {
        String want = digitsOrAlnum(typed);
        List<String> known = new ArrayList<>();
        if (t.getImei() != null && !t.getImei().isBlank()) known.add(digitsOrAlnum(t.getImei()));
        if (t.getSerialNumber() != null && !t.getSerialNumber().isBlank()) known.add(digitsOrAlnum(t.getSerialNumber()));
        // Nothing recorded at booking: the typed value is kept as the evidence.
        return known.isEmpty() || known.contains(want);
    }

    private static String digitsOrAlnum(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private void resetAttempt(RepairDelivery d) {
        d.setTechnicianId(null);
        d.setAcceptedAt(null);
        d.setReleasedAt(null);
        d.setCollectedAt(null);
        d.setStartedAt(null);
        d.setArrivedAt(null);
        d.setFailedAt(null);
        d.setDeliveredAt(null);
        d.setFailReason(null);
        d.setDeclinedReason(null);
        d.setOtpCode(null);
        d.setOtpExpiresAt(null);
        d.setOtpSentCount(0);
        d.setOtpFailedAttempts(0);
        d.setCollectedIdentifier(null);
        d.setCollectPhotosJson(null);
        d.setProofPhotosJson(null);
        d.setAmountCollected(null);
        d.setPaymentMethod(null);
        d.setPaymentRef(null);
    }

    private void applyAddress(RepairDelivery d, PlatformRepairBooking b, Ticket t) {
        String addr = null;
        if (b.getPickupAddressId() != null) {
            try {
                Map<String, Object> row = jdbc.queryForMap(
                        "SELECT concat_ws(', ', NULLIF(address_line, ''), NULLIF(COALESCE(area, locality), ''), "
                                + "NULLIF(COALESCE(district, city), ''), NULLIF(state, ''), NULLIF(pincode, '')) AS a, latitude, longitude "
                                + "FROM customer_addresses WHERE id = ?", b.getPickupAddressId());
                addr = (String) row.get("a");
                d.setLatitude((BigDecimal) row.get("latitude"));
                d.setLongitude((BigDecimal) row.get("longitude"));
            } catch (Exception ignored) { /* falls back to the ticket's address */ }
        }
        if (addr == null || addr.isBlank()) addr = t.getCustomerAddress();
        d.setAddress(addr);
    }

    private void applySlot(RepairDelivery d, Map<String, Object> body) {
        try {
            LocalDate date = LocalDate.parse(String.valueOf(body.get("date")));
            LocalTime s = LocalTime.parse(String.valueOf(body.get("slotStart")));
            LocalTime e = LocalTime.parse(String.valueOf(body.get("slotEnd")));
            if (!e.isAfter(s)) throw conflict("The slot end must be after its start.");
            if (date.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))) throw conflict("Pick today or a later date.");
            d.setScheduledDate(date);
            d.setSlotStart(s);
            d.setSlotEnd(e);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date (YYYY-MM-DD), slotStart and slotEnd (HH:mm) are required");
        }
    }

    private Ticket ticket(UUID shopId, UUID ticketId) {
        if (shopId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing shop context");
        return tickets.findByShopIdAndId(shopId, ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found: " + ticketId));
    }

    private RepairDelivery locked(UUID ticketId) {
        return deliveries.lockByTicketId(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("No delivery scheduled for this booking"));
    }

    /** Active employee of THIS shop (owner's own technician row excluded). */
    private Technician employee(UUID shopId, UUID idOrUserId) {
        Technician t = technicians.findByShopIdAndId(shopId, idOrUserId)
                .or(() -> technicians.findByShopIdAndUserId(shopId, idOrUserId))
                .orElseThrow(() -> conflict("This employee is not part of your shop."));
        if (!t.isAvailable() && !t.isOwner()) throw conflict("This employee is inactive.");
        return t;
    }

    private Technician me(UUID shopId, UUID userId) {
        if (shopId == null || userId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing auth context");
        return technicians.findByShopIdAndUserId(shopId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not an employee of this shop"));
    }

    private RepairDelivery mine(UUID shopId, UUID userId, UUID id, boolean lock) {
        Technician me = me(shopId, userId);
        RepairDelivery d = (lock ? deliveries.lockById(id) : deliveries.findById(id))
                .orElseThrow(() -> new ResourceNotFoundException("Delivery not found"));
        if (!shopId.equals(d.getShopId()) || d.getTechnicianId() == null || !d.getTechnicianId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This delivery is not assigned to you");
        }
        return d;
    }

    private static void requireStatus(RepairDelivery d, String want) {
        if (!want.equals(d.getStatus())) {
            throw conflict("Not allowed while the delivery is " + LABEL.getOrDefault(d.getStatus(), d.getStatus()) + ".");
        }
    }

    private void save(RepairDelivery d, String status, String label, String note) {
        deliveries.save(d);
        event(d, status, label, note, "EMPLOYEE");
    }

    private void event(RepairDelivery d, String status, String label, String note, String actor) {
        events.save(RepairDeliveryEvent.builder().deliveryId(d.getId()).status(status).label(label).note(note).actor(actor).build());
    }

    private List<Map<String, Object>> eventViews(UUID deliveryId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RepairDeliveryEvent e : events.findByDeliveryIdOrderByCreatedAtAsc(deliveryId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("status", e.getStatus());
            m.put("label", e.getLabel());
            m.put("note", e.getNote());
            m.put("actor", e.getActor());
            m.put("at", e.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    /** Shared delivery fields (never the OTP). */
    Map<String, Object> view(RepairDelivery d, boolean employee) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", d.getId());
        v.put("ticketId", d.getTicketId());
        v.put("status", d.getStatus());
        v.put("statusLabel", LABEL.getOrDefault(d.getStatus(), d.getStatus()));
        v.put("attempt", d.getAttempt());
        v.put("returnPath", d.isReturnPath());
        v.put("scheduledDate", d.getScheduledDate() == null ? null : d.getScheduledDate().toString());
        v.put("slotStart", d.getSlotStart() == null ? null : d.getSlotStart().toString());
        v.put("slotEnd", d.getSlotEnd() == null ? null : d.getSlotEnd().toString());
        v.put("address", d.getAddress());
        v.put("amountDue", d.getAmountDue());
        v.put("amountCollected", d.getAmountCollected());
        v.put("paymentMethod", d.getPaymentMethod());
        v.put("paymentRef", d.getPaymentRef());
        v.put("proofPhotos", read(d.getProofPhotosJson()));
        v.put("declinedReason", d.getDeclinedReason());
        v.put("failReason", d.getFailReason());
        v.put("releasedAt", d.getReleasedAt());
        v.put("collectedAt", d.getCollectedAt());
        v.put("deliveredAt", d.getDeliveredAt());
        v.put("returnedAt", d.getReturnedAt());
        if (d.getTechnicianId() != null) {
            technicians.findById(d.getTechnicianId()).ifPresent(t -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", t.getId());
                e.put("name", t.getName());
                e.put("phone", t.getPhone());
                v.put("employee", e);
            });
        }
        return v;
    }

    private Map<String, Object> employeeView(RepairDelivery d) {
        Map<String, Object> v = view(d, true);
        Ticket t = tickets.findById(d.getTicketId()).orElse(null);
        if (t != null) {
            v.put("bookingNumber", t.getTrackingId());
            v.put("customerName", t.getCustomerName());
            v.put("customerPhone", t.getCustomerPhone());
            v.put("customerAddress", d.getAddress());
            v.put("device", t.getDeviceDisplayName());
            v.put("deviceImageUrl", t.getDeviceImageUrl());
            String hint = t.getImei() != null && t.getImei().length() >= 4 ? "IMEI ending " + t.getImei().substring(t.getImei().length() - 4)
                    : t.getSerialNumber() != null && t.getSerialNumber().length() >= 4 ? "Serial ending " + t.getSerialNumber().substring(t.getSerialNumber().length() - 4) : null;
            v.put("deviceIdentifierHint", hint);
        }
        v.put("accessories", read(d.getAccessoriesJson()));
        v.put("shopAddress", shopAddress(d.getShopId()));
        v.put("events", eventViews(d.getId()));
        return v;
    }

    private String shopAddress(UUID shopId) {
        try {
            return jdbc.queryForObject("SELECT address FROM shops WHERE id = ?", String.class, shopId);
        } catch (Exception e) {
            return null;
        }
    }

    private String techName(RepairDelivery d) {
        return d.getTechnicianId() == null ? "the employee"
                : technicians.findById(d.getTechnicianId()).map(Technician::getName).orElse("the employee");
    }

    private List<String> read(String s) {
        if (s == null || s.isBlank()) return List.of();
        try { return json.readValue(s, new TypeReference<List<String>>() {}); } catch (Exception e) { return List.of(); }
    }

    private String write(List<String> l) {
        try { return json.writeValueAsString(l == null ? List.of() : l); } catch (Exception e) { return "[]"; }
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) for (Object o : c) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
        return out;
    }

    private static List<String> urls(Object raw) {
        return strings(raw).stream().filter(u -> u.startsWith("http")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static UUID uuid(Object o) {
        String s = str(o);
        try { return s == null ? null : UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static BigDecimal amount(Object o) {
        String s = str(o);
        try { return s == null ? null : new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
