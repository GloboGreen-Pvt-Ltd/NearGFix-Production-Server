package com.repairshop.saas.ticket.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.ticket.dto.TicketRequest;
import com.repairshop.saas.ticket.dto.TicketResponse;
import com.repairshop.saas.ticket.entity.Ticket;
import com.repairshop.saas.ticket.exception.ResourceNotFoundException;
import com.repairshop.saas.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Re-Service + Service Status against a REAL PostgreSQL built from
 * services/postgres/init (schema.sql, numbered migrations incl. 129).
 * ddl-auto=validate also checks every entity against that schema.
 *
 * Opt-in: set NGF_IT_DB_URL (jdbc:postgresql://…), NGF_IT_DB_USER, NGF_IT_DB_PASSWORD.
 * Never point it at production — each test writes (and rolls back / cleans up) its own rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReServiceLinkService.class, ServiceStatusReportService.class, InvoiceService.class})
@EnabledIfEnvironmentVariable(named = "NGF_IT_DB_URL", matches = ".+")
class ReServicePostgresIT {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("NGF_IT_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("NGF_IT_DB_USER", "postgres"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("NGF_IT_DB_PASSWORD", ""));
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired ReServiceLinkService links;
    @Autowired ServiceStatusReportService report;
    @Autowired TicketRepository tickets;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @MockitoBean TicketService ticketService;
    @MockitoBean ReEstimateWorkflowService reEstimateWorkflow;
    @Autowired InvoiceService invoices;
    @Autowired jakarta.persistence.EntityManager em;

    /** Raw SQL change + drop JPA's cached copies (each app request is a fresh transaction). */
    private void update(String sql, Object... args) { em.flush(); jdbc.update(sql, args); em.clear(); }

    private static final ObjectMapper JSON = new ObjectMapper();
    private UUID shop, otherShop, customer, customerUser;
    private String prefix;

    @BeforeEach
    void fixtures() {
        when(ticketService.responsesFor(any(), anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).stream()
                .map(id -> TicketResponse.builder().id((UUID) id).build()).toList());
        shop = UUID.randomUUID();
        otherShop = UUID.randomUUID();
        customer = UUID.randomUUID();
        customerUser = UUID.randomUUID();
        prefix = "IT" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO shops (id, name, slug) VALUES (?, 'IT Shop', ?), (?, 'IT Other', ?)",
                shop, "it-" + shop, otherShop, "it-" + otherShop);
        jdbc.update("INSERT INTO customers (id, shop_id, name, phone) VALUES (?, ?, 'Rajamuthu', '9025639296')", customer, shop);
        jdbc.update("INSERT INTO customer_users (id) VALUES (?)", customerUser);
    }

    /** A delivered service: walk-in hand-over `deliveredDaysAgo` days ago, optional invoice. */
    private UUID delivered(String tracking, String type, String itemsJson, int deliveredDaysAgo, Integer invoiceAmount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tickets (id, shop_id, customer_id, customer_name, customer_phone, tracking_id, booking_number, status,"
                        + " final_price, price_items_json, issue_description, imei, service_type, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'Rajamuthu', '9025639296', ?, ?, 'DELIVERED', ?, ?, 'Vibrate Only/No Power', '863799078049975', ?,"
                        + " now() - make_interval(days => ?), now() - make_interval(days => ?))",
                id, shop, customer, tracking, tracking, invoiceAmount, itemsJson, type, deliveredDaysAgo + 5, deliveredDaysAgo);
        UUID booking = UUID.randomUUID();
        jdbc.update("INSERT INTO repair_bookings (id, booking_number, customer_user_id, ticket_id) VALUES (?, ?, ?, ?)", booking, tracking, customerUser, id);
        jdbc.update("INSERT INTO repair_booking_events (booking_id, status, note, actor, created_at) VALUES (?, 'DELIVERED', 'Delivered by Owner', 'OWNER',"
                + " now() - make_interval(days => ?))", booking, deliveredDaysAgo);
        if (invoiceAmount != null) {
            jdbc.update("INSERT INTO invoices (id, ticket_id, shop_id, invoice_no, final_payable_amount, amount_paid, credit_amount, generated_at, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, 0, now(), now(), now())", UUID.randomUUID(), id, shop, tracking, invoiceAmount, invoiceAmount);
        }
        return id;
    }

    private static final String SIX_MONTHS = "[{\"name\":\"Vibrate Only/No Power\",\"price\":900,\"warranty\":\"6 Months\"}]";

    private TicketRequest req(UUID of, String type, String reason, String key) {
        TicketRequest r = new TicketRequest();
        r.setReServiceOf(of);
        r.setServiceType(type);
        r.setReturnReason(reason);
        r.setReServiceRequestId(key);
        r.setReServiceNotes("{\"complaint\":\"No power again\"}");
        r.setIssueDescription("No power again");
        return r;
    }

    /** What TicketService.create does around the link: fresh ticket + link + save. */
    private Ticket createReService(UUID shopId, TicketRequest r) {
        ReServiceLinkService.Prepared p = links.prepare(shopId, r);
        if (p.existing() != null) return p.existing();
        Ticket t = Ticket.builder().shopId(shopId).customerId(UUID.randomUUID()).issueDescription(r.getIssueDescription())
                .trackingId(p.number().trackingId()).partnerId(p.number().partnerId())
                .bookingNumber(p.number().bookingNumber()).status("CREATED").build();
        links.apply(t, p, r);
        return tickets.saveAndFlush(t);
    }

    private String row(UUID id) {
        em.flush();
        return jdbc.queryForObject("SELECT to_jsonb(t)::text FROM tickets t WHERE id = ?", String.class, id);
    }

    @Test
    void reServiceIsANewLinkedRecordAndTheOriginalStaysUntouched() throws Exception {
        UUID original = delivered(prefix + "-231", null, SIX_MONTHS, 2, 900);
        String before = row(original);
        String invoiceBefore = jdbc.queryForObject("SELECT to_jsonb(i)::text FROM invoices i WHERE ticket_id = ?", String.class, original);

        Ticket r01 = createReService(shop, req(original, "WARRANTY", "SAME_ISSUE", "k1"));

        assertNotEquals(original, r01.getId());
        assertEquals(prefix + "-231-R01", r01.getTrackingId());
        assertEquals(original, r01.getParentTicketId());
        assertEquals(original, r01.getRootTicketId());
        assertEquals(1, r01.getServiceCycleNo());
        assertEquals("CREATED", r01.getStatus());
        assertEquals(customer, r01.getCustomerId(), "customer carried from the delivered service, not the request");
        assertEquals("863799078049975", r01.getImei());
        assertNull(r01.getAssignedTechnicianId());
        assertNull(r01.getPaymentAmount());
        assertNull(r01.getDevicePhotosJson());
        var notes = JSON.readTree(r01.getReServiceNotes());
        assertEquals(prefix + "-231", notes.path("warrantyReference").asText());
        assertEquals(prefix + "-231", notes.path("previousInvoiceNo").asText());
        assertEquals("No power again", notes.path("complaint").asText());

        assertEquals(before, row(original), "original ticket row unchanged");
        assertEquals(invoiceBefore, jdbc.queryForObject("SELECT to_jsonb(i)::text FROM invoices i WHERE ticket_id = ?", String.class, original));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM invoices WHERE ticket_id = ?", Integer.class, r01.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM repair_deliveries WHERE ticket_id = ?", Integer.class, r01.getId()));
    }

    @Test
    void retryReturnsTheSameRecordAndConflictsAreRefused() {
        UUID original = delivered(prefix + "-300", null, SIX_MONTHS, 2, 900);
        Ticket first = createReService(shop, req(original, "PAID", "NEW_ISSUE", "key-A"));
        assertEquals(first.getId(), createReService(shop, req(original, "PAID", "NEW_ISSUE", "key-A")).getId());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> createReService(shop, req(original, "FREE_REWORK", "NEW_ISSUE", "key-A"))).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> createReService(shop, req(original, "PAID", "NEW_ISSUE", "key-B"))).getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE parent_ticket_id = ?", Integer.class, original));
    }

    @Test
    void onlyTheLatestDeliveredServiceCanBeReServicedAndNumbersStaySequential() {
        UUID original = delivered(prefix + "-400", null, SIX_MONTHS, 2, 900);
        Ticket r01 = createReService(shop, req(original, "PAID", "NEW_ISSUE", "a"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> createReService(shop, req(r01.getId(), "PAID", "NEW_ISSUE", "b"))).getStatusCode().value(), "R01 still active");
        update("UPDATE tickets SET status = 'DELIVERED' WHERE id = ?", r01.getId());
        Ticket r02 = createReService(shop, req(r01.getId(), "PAID", "NEW_ISSUE", "c"));
        assertEquals(prefix + "-400-R02", r02.getTrackingId());
        assertEquals(original, r02.getRootTicketId());
        assertEquals(r01.getId(), r02.getParentTicketId());
        assertEquals(2, r02.getServiceCycleNo());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> createReService(shop, req(original, "PAID", "NEW_ISSUE", "d"))).getStatusCode().value(), "original already followed");
    }

    @Test
    void warrantyNeedsARelatedIssueAnActiveWarrantyAndIsNeverExtendedByFreeWork() {
        UUID fresh = delivered(prefix + "-500", null, SIX_MONTHS, 2, 900);
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> createReService(shop, req(fresh, "WARRANTY", "NEW_ISSUE", "w1"))).getStatusCode().value(), "new issue not covered");

        UUID expired = delivered(prefix + "-501", null, SIX_MONTHS, 200, 900);
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> createReService(shop, req(expired, "WARRANTY", "SAME_ISSUE", "w2"))).getStatusCode().value(), "expired");

        // A free / warranty Re-Service with its own "6 Months" lines does not restart the warranty.
        UUID root = delivered(prefix + "-502", null, SIX_MONTHS, 200, 900);
        UUID r01 = delivered(prefix + "-502-R01", "FREE_REWORK", SIX_MONTHS, 2, null);
        update("UPDATE tickets SET parent_ticket_id = ?, root_ticket_id = ?, service_cycle_no = 1 WHERE id = ?", root, root, r01);
        assertEquals(422, assertThrows(ResponseStatusException.class,
                () -> createReService(shop, req(r01, "WARRANTY", "SAME_ISSUE", "w3"))).getStatusCode().value(), "free rework doesn't extend");
        // …but a PAID Re-Service does carry its own warranty.
        update("UPDATE tickets SET service_type = 'PAID' WHERE id = ?", r01);
        assertEquals(prefix + "-502-R02", createReService(shop, req(r01, "WARRANTY", "SAME_ISSUE", "w4")).getTrackingId());
    }

    @Test
    void anotherShopCanNeitherLinkNorReadTheChain() {
        UUID original = delivered(prefix + "-600", null, SIX_MONTHS, 2, 900);
        assertThrows(ResourceNotFoundException.class, () -> createReService(otherShop, req(original, "PAID", "NEW_ISSUE", "x")));
        assertThrows(ResourceNotFoundException.class, () -> links.serviceRecords(otherShop, original));
    }

    @Test
    void serviceRecordsKeepEachServicesOwnInvoiceAndDelivery() {
        UUID original = delivered(prefix + "-700", null, SIX_MONTHS, 2, 900);
        Ticket r01 = createReService(shop, req(original, "WARRANTY", "SAME_ISSUE", "s1"));
        update("UPDATE tickets SET status = 'DELIVERED' WHERE id = ?", r01.getId());
        jdbc.update("INSERT INTO repair_deliveries (ticket_id, shop_id, status, attempt, delivered_at, created_at, updated_at)"
                + " VALUES (?, ?, 'DELIVERED', 2, now(), now(), now())", r01.getId(), shop);

        List<Map<String, Object>> recs = links.serviceRecords(shop, r01.getId());
        assertEquals(2, recs.size());
        assertEquals("ORIGINAL", recs.get(0).get("serviceType"));
        assertEquals(0, ((java.math.BigDecimal) ((Map<?, ?>) recs.get(0).get("invoice")).get("amount")).compareTo(new java.math.BigDecimal("900")));
        Map<?, ?> d0 = (Map<?, ?>) recs.get(0).get("delivery");
        assertEquals("WALK_IN", d0.get("method"));
        assertEquals("Owner", d0.get("deliveredBy"));
        assertEquals("WARRANTY", recs.get(1).get("serviceType"));
        assertNull(recs.get(1).get("invoice"), "R01 has no invoice of its own yet — the original's is not borrowed");
        Map<?, ?> d1 = (Map<?, ?>) recs.get(1).get("delivery");
        assertEquals("DOORSTEP", d1.get("method"));
        assertEquals(2, d1.get("attempts"));
    }

    @Test
    void statusCardsAreExclusiveAndEachCardMatchesItsList() {
        UUID original = delivered(prefix + "-800", null, SIX_MONTHS, 2, 900);
        Ticket r01 = createReService(shop, req(original, "WARRANTY", "SAME_ISSUE", "r"));
        UUID tech = UUID.randomUUID();
        jdbc.update("INSERT INTO technicians (id, shop_id, name) VALUES (?, ?, 'Veerapandiyan')", tech, shop);
        update("UPDATE tickets SET assigned_technician_id = ? WHERE id = ?", tech, r01.getId());
        // Legacy reopened booking: QC in the OLD cycle must not count; repair completed in the new one → QC pending.
        UUID legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO tickets (id, shop_id, customer_id, tracking_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, 'IN_REPAIR', now(), now())",
                legacy, shop, customer, prefix + "-801");
        UUID b = UUID.randomUUID();
        jdbc.update("INSERT INTO repair_bookings (id, booking_number, customer_user_id, ticket_id) VALUES (?, ?, ?, ?)", b, prefix + "-801", customerUser, legacy);
        jdbc.update("INSERT INTO repair_booking_events (booking_id, status, created_at) VALUES (?, 'QUALITY_CHECK_COMPLETED', now() - interval '9 days'),"
                + " (?, 'RE_SERVICE', now() - interval '5 days'), (?, 'REPAIR_COMPLETED', now() - interval '1 day')", b, b, b);
        for (String s : List.of("CREATED", "QUOTED", "READY", "INVOICE_GENERATED", "DELIVERED_PROCESSING", "CANCELLED")) {
            jdbc.update("INSERT INTO tickets (id, shop_id, customer_id, tracking_id, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
                    UUID.randomUUID(), shop, customer, prefix + "-" + s, s);
        }

        var f = ServiceStatusReportService.filter("ALL", null, null);
        Map<String, Object> out = report.summary(shop, f);
        Map<String, Long> cards = new HashMap<>();
        for (Object o : (List<?>) out.get("buckets")) {
            Map<?, ?> m = (Map<?, ?>) o;
            cards.put((String) m.get("key"), ((Number) m.get("count")).longValue());
        }
        Map<?, ?> sum = (Map<?, ?>) out.get("summary");
        long visits = ((Number) sum.get("totalVisits")).longValue();
        assertEquals(9, visits);
        assertEquals(visits, cards.values().stream().mapToLong(Long::longValue).sum(), "each record in exactly one card");
        assertEquals(1, cards.get("QC_PENDING"));
        assertEquals(1, cards.get("TECHNICIAN_ASSIGNED"));
        assertEquals(1, cards.get("DELIVERED"));
        assertEquals(1L, ((Number) sum.get("reServiceVisits")).longValue());
        assertEquals(8L, ((Number) sum.get("uniqueBookings")).longValue(), "original + R01 are one booking");
        for (var e : cards.entrySet()) {
            Page<TicketResponse> page = report.list(shop, e.getKey(), f, null, 0, 50);
            assertEquals(e.getValue().longValue(), page.getTotalElements(), "card " + e.getKey() + " = its list total");
        }
        assertEquals(1, report.list(shop, "ALL", ServiceStatusReportService.filter("RE_SERVICE", null, null), null, 0, 50).getTotalElements());
        assertEquals(2, report.list(shop, "ALL", f, prefix + "-800", 0, 50).getTotalElements(), "tracking prefix finds the chain");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCreatesMakeExactlyOneReService() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txm);
        String p = "ITC" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        UUID[] original = new UUID[1];
        // @BeforeEach already committed shop/customer rows (no test transaction here).
        tx.executeWithoutResult(s -> original[0] = delivered(p + "-1", null, SIX_MONTHS, 2, 900));
        UUID shopId = shop;
        AtomicInteger ok = new AtomicInteger(), refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (String key : List.of("t1", "t2")) {
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    tx.executeWithoutResult(s -> createReService(shopId, req(original[0], "PAID", "NEW_ISSUE", key)));
                    ok.incrementAndGet();
                } catch (RuntimeException e) { refused.incrementAndGet(); }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        try {
            assertEquals(1, ok.get());
            assertEquals(1, refused.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE parent_ticket_id = ?", Integer.class, original[0]));
        } finally {
            tx.executeWithoutResult(s -> {
                jdbc.update("DELETE FROM tickets WHERE parent_ticket_id = ?", original[0]);
                jdbc.update("DELETE FROM repair_booking_events WHERE booking_id IN (SELECT id FROM repair_bookings WHERE ticket_id = ?)", original[0]);
                jdbc.update("DELETE FROM repair_bookings WHERE ticket_id = ?", original[0]);
                jdbc.update("DELETE FROM invoices WHERE ticket_id = ?", original[0]);
                jdbc.update("DELETE FROM tickets WHERE id = ?", original[0]);
                jdbc.update("DELETE FROM customers WHERE id = ?", customer);
                jdbc.update("DELETE FROM customer_users WHERE id = ?", customerUser);
                jdbc.update("DELETE FROM shops WHERE id IN (?, ?)", shop, otherShop);
            });
        }
    }

    private com.repairshop.saas.ticket.dto.InvoiceRequest bill(int total, int advance, int paid) {
        var r = new com.repairshop.saas.ticket.dto.InvoiceRequest();
        r.setServiceCharges(java.math.BigDecimal.valueOf(total));
        r.setFinalPayableAmount(java.math.BigDecimal.valueOf(total));
        r.setAdvancePaid(java.math.BigDecimal.valueOf(advance));
        r.setAmountPaid(java.math.BigDecimal.valueOf(paid));
        return r;
    }

    private int ledgerRows(UUID ticketId) {
        em.flush();
        return jdbc.queryForObject("SELECT COUNT(*) FROM shop_ledger_entries WHERE ticket_id = ?", Integer.class, ticketId);
    }

    /** GTS-231 ₹900 → R01 warranty ₹0 → R02 paid ₹450: separate bills, no borrowed money, no duplicates. */
    @Test
    void financialChainKeepsEveryInvoiceSeparateAndCountsMoneyOnce() {
        UUID orig = delivered(prefix + "-231", null, SIX_MONTHS, 2, null);
        update("UPDATE tickets SET status = 'READY' WHERE id = ?", orig);
        var o1 = invoices.upsert(orig, bill(900, 0, 900), null, shop);
        assertEquals(o1.getId(), invoices.upsert(orig, bill(900, 0, 900), null, shop).getId(), "regenerate before delivery = same row");
        update("UPDATE tickets SET status = 'DELIVERED' WHERE id = ?", orig);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> invoices.upsert(orig, bill(1, 0, 1), null, shop)).getStatusCode().value(), "delivered invoice is final");
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> invoices.upsert(orig, bill(900, 0, 900), null, otherShop)).getStatusCode().value(), "other shop can't bill it");
        assertTrue(invoices.findByTicket(orig, otherShop).isEmpty(), "other shop can't read it");
        em.flush();
        String origInvoice = jdbc.queryForObject("SELECT to_jsonb(i)::text FROM invoices i WHERE ticket_id = ?", String.class, orig);

        Ticket r01 = createReService(shop, req(orig, "WARRANTY", "SAME_ISSUE", "f1"));
        var i1 = invoices.upsert(r01.getId(), bill(0, 0, 0), null, shop);
        assertEquals(prefix + "-231-R01", i1.getInvoiceNo());
        assertEquals(0, ledgerRows(r01.getId()), "₹0 warranty work posts nothing to the Cash Book");
        update("UPDATE tickets SET status = 'DELIVERED' WHERE id = ?", r01.getId());

        Ticket r02 = createReService(shop, req(r01.getId(), "PAID", "NEW_ISSUE", "f2"));
        invoices.upsert(r02.getId(), bill(450, 0, 200), null, shop);
        assertEquals(1, ledgerRows(r02.getId()), "credit ₹250 → one Cash Book row");
        invoices.upsert(r02.getId(), bill(450, 0, 300), null, shop);
        assertEquals(1, ledgerRows(r02.getId()), "re-billing updates that row, never adds one");
        em.flush();
        assertEquals(0, new java.math.BigDecimal("150").compareTo(jdbc.queryForObject(
                "SELECT amount FROM shop_ledger_entries WHERE ticket_id = ?", java.math.BigDecimal.class, r02.getId())));
        var i2 = invoices.upsert(r02.getId(), bill(450, 0, 450), null, shop);
        assertEquals(0, ledgerRows(r02.getId()), "settled → credit row removed");
        assertEquals(prefix + "-231-R02", i2.getInvoiceNo());
        update("UPDATE tickets SET status = 'DELIVERED' WHERE id = ?", r02.getId());

        em.flush();
        assertEquals(origInvoice, jdbc.queryForObject("SELECT to_jsonb(i)::text FROM invoices i WHERE ticket_id = ?", String.class, orig),
                "original ₹900 invoice untouched by both Re-Services");
        assertEquals(0, ledgerRows(orig));

        List<Map<String, Object>> recs = links.serviceRecords(shop, r02.getId());
        assertEquals(3, recs.size());
        java.math.BigDecimal invoiced = java.math.BigDecimal.ZERO, received = java.math.BigDecimal.ZERO, outstanding = java.math.BigDecimal.ZERO;
        Set<Object> numbers = new HashSet<>();
        for (Map<String, Object> r : recs) {
            Map<?, ?> inv = (Map<?, ?>) r.get("invoice");
            invoiced = invoiced.add((java.math.BigDecimal) inv.get("amount"));
            received = received.add((java.math.BigDecimal) inv.get("advancePaid")).add((java.math.BigDecimal) inv.get("amountPaid"));
            outstanding = outstanding.add((java.math.BigDecimal) inv.get("creditAmount"));
            numbers.add(inv.get("invoiceNo"));
        }
        assertEquals(0, new java.math.BigDecimal("1350").compareTo(invoiced), "total invoiced ₹1,350");
        assertEquals(0, new java.math.BigDecimal("1350").compareTo(received), "total received ₹1,350");
        assertEquals(0, outstanding.signum(), "outstanding ₹0");
        assertEquals(3, numbers.size(), "three distinct invoice numbers");

        var sum = (Map<?, ?>) report.summary(shop, ServiceStatusReportService.filter("ALL", null, null)).get("summary");
        assertEquals(1L, ((Number) sum.get("uniqueBookings")).longValue());
        assertEquals(3L, ((Number) sum.get("totalVisits")).longValue());
        assertEquals(2L, ((Number) sum.get("reServiceVisits")).longValue());
        assertEquals(0L, ((Number) sum.get("activeServices")).longValue());
    }
}