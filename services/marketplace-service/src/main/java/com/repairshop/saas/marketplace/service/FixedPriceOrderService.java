package com.repairshop.saas.marketplace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.marketplace.entity.MarketplaceOrder;
import com.repairshop.saas.marketplace.entity.MarketplaceOrderEvent;
import com.repairshop.saas.marketplace.entity.MarketplaceProduct;
import com.repairshop.saas.marketplace.repository.MarketplaceOrderEventRepository;
import com.repairshop.saas.marketplace.repository.MarketplaceOrderRepository;
import com.repairshop.saas.marketplace.repository.MarketplaceProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * Fixed-price Buy flow: customer buys a shop listing at its published price,
 * shop confirms, optionally assigns an employee who picks up from the shop and
 * delivers (COD collected + customer OTP). One order id across all three apps.
 *
 * Status: PLACED -> CONFIRMED -> [HOME_DELIVERY: ASSIGNED -> ACCEPTED -> PICKED_UP ->
 * OUT_FOR_DELIVERY -> DELIVERED] | [VISIT_SHOP: COMPLETED]. Side exits: REJECTED,
 * CANCELLED, DELIVERY_FAILED -> RETURNED. Stock is reserved at PLACED and
 * released on every side exit.
 */
@Service
@RequiredArgsConstructor
public class FixedPriceOrderService {

    public static final String HOME = "HOME_DELIVERY", VISIT = "VISIT_SHOP";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> OPEN_FOR_CANCEL = Set.of("PLACED", "CONFIRMED", "ASSIGNED", "ACCEPTED");
    // Orders still holding stock (not yet handed over, cancelled or returned).
    private static final Set<String> OPEN = Set.of("PLACED", "CONFIRMED", "ASSIGNED", "ACCEPTED", "PICKED_UP", "OUT_FOR_DELIVERY", "DELIVERY_FAILED");

    private final MarketplaceOrderRepository orders;
    private final com.repairshop.saas.common.presence.PresenceAvailability presenceAvailability;
    private final MarketplaceOrderEventRepository events;
    private final MarketplaceProductRepository products;
    private final ObjectMapper json;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ChatSnapshotService snapshots;

    // ---------- customer ----------

    @Transactional
    public Map<String, Object> place(UUID customerUserId, Map<String, Object> body) {
        String key = str(body.get("idempotencyKey"));
        if (key == null || key.length() > 64) throw bad("idempotencyKey required (max 64 chars)");
        Optional<MarketplaceOrder> prior = orders.findByCustomerUserIdAndIdempotencyKey(customerUserId, key);
        if (prior.isPresent()) return view(prior.get(), true);

        UUID productId = uuid(body.get("productId"), "productId");
        int qty;
        try { qty = body.get("quantity") == null ? 1 : Integer.parseInt(body.get("quantity").toString()); }
        catch (NumberFormatException e) { throw bad("quantity must be a number"); }
        if (qty < 1 || qty > 10) throw bad("quantity must be 1-10");
        String fulfilment = Optional.ofNullable(str(body.get("fulfilmentType"))).orElse("").toUpperCase();
        if (!HOME.equals(fulfilment) && !VISIT.equals(fulfilment)) throw bad("fulfilmentType must be HOME_DELIVERY or VISIT_SHOP");
        String pay = Optional.ofNullable(str(body.get("paymentMethod"))).orElse(HOME.equals(fulfilment) ? "COD" : "PAY_AT_SHOP").toUpperCase();
        if (HOME.equals(fulfilment) && !"COD".equals(pay) || VISIT.equals(fulfilment) && !"PAY_AT_SHOP".equals(pay)) {
            throw bad("Online payment is not enabled yet; use COD (home delivery) or PAY_AT_SHOP (visit shop)");
        }
        String address = str(body.get("deliveryAddress"));
        if (HOME.equals(fulfilment) && address == null) throw bad("deliveryAddress required for home delivery");

        MarketplaceProduct p = products.findByIdForUpdate(productId).orElseThrow(() -> notFound("Product not found"));
        if (p.getShopId() == null || !"ACTIVE".equals(p.getStatus())) throw conflict("Product is not available");
        if (HOME.equals(fulfilment) && !hasOption(p, "SHIPPING")) throw conflict("Home delivery is not available for this product; use VISIT_SHOP");
        // Server-side price: the client price is only checked, never trusted.
        Object clientPrice = body.get("expectedUnitPrice");
        if (clientPrice != null && new BigDecimal(clientPrice.toString()).compareTo(p.getPrice()) != 0) {
            throw conflict("Price changed to " + p.getPrice() + "; refresh and retry");
        }
        int available = nz(p.getQuantity()) - nz(p.getSoldQuantity());
        if (available < qty) throw conflict("Only " + Math.max(available, 0) + " in stock");
        p.setSoldQuantity(nz(p.getSoldQuantity()) + qty);
        // Stock is held, not sold: the listing reads SOLD only once an order is handed over.
        if (nz(p.getSoldQuantity()) >= nz(p.getQuantity())) p.setStatus("RESERVED");
        products.save(p);

        MarketplaceOrder o = orders.save(MarketplaceOrder.builder()
                .shopId(p.getShopId()).customerUserId(customerUserId).productId(productId)
                .orderNumber(orderNumber()).status("PLACED").quantity(qty).unitPrice(p.getPrice())
                .totalAmount(p.getPrice().multiply(BigDecimal.valueOf(qty)))
                .fulfilmentType(fulfilment).paymentMethod(pay).paymentStatus("PENDING")
                .deliveryAddress(address).customerPhone(str(body.get("customerPhone")))
                .idempotencyKey(key).handoverOtp(otp()).stockReleased(false).build());
        log(o, "PLACED", customerUserId, "CUSTOMER", null);
        return view(o, true);
    }

    public List<Map<String, Object>> customerOrders(UUID customerUserId) {
        return orders.findByCustomerUserIdOrderByCreatedAtDesc(customerUserId).stream().map(o -> view(o, true)).toList();
    }

    public Map<String, Object> customerOrder(UUID customerUserId, UUID id) {
        MarketplaceOrder o = load(id);
        if (!customerUserId.equals(o.getCustomerUserId())) throw notFound("Order not found");
        return detail(o, true);
    }

    @Transactional
    public Map<String, Object> customerCancel(UUID customerUserId, UUID id) {
        MarketplaceOrder o = lock(id);
        if (!customerUserId.equals(o.getCustomerUserId())) throw notFound("Order not found");
        if ("CANCELLED".equals(o.getStatus())) return view(o, true);
        if (!OPEN_FOR_CANCEL.contains(o.getStatus())) throw conflict("Order can no longer be cancelled");
        release(o);
        return move(o, "CANCELLED", customerUserId, "CUSTOMER", null, true);
    }

    // ---------- shop ----------

    public List<Map<String, Object>> shopOrders(UUID shopId) {
        return orders.findByShopIdAndProductIdIsNotNullOrderByCreatedAtDesc(shopId).stream().map(o -> view(o, false)).toList();
    }

    public Map<String, Object> shopOrder(UUID shopId, UUID id) {
        MarketplaceOrder o = shopOwned(shopId, load(id));
        Map<String, Object> m = detail(o, false);
        // Only the shop sees its Pickup PIN, and only while a pickup is pending.
        boolean usable = o.getPickupPin() != null && PIN_STAGES.contains(o.getStatus())
                && o.getPickupPinExpiresAt() != null && o.getPickupPinExpiresAt().isAfter(Instant.now());
        m.put("pickupPin", usable ? o.getPickupPin() : null);
        m.put("pickupPinExpiresAt", usable ? o.getPickupPinExpiresAt() : null);
        return m;
    }

    @Transactional
    public Map<String, Object> shopAction(UUID shopId, UUID actor, UUID id, String action, Map<String, Object> body) {
        MarketplaceOrder o = shopOwned(shopId, lock(id));
        String s = o.getStatus();
        switch (action) {
            case "confirm" -> {
                if ("CONFIRMED".equals(s)) return view(o, false);
                require(o, "PLACED");
                return move(o, "CONFIRMED", actor, "SHOP", null, false);
            }
            case "reject" -> {
                if ("REJECTED".equals(s)) return view(o, false);
                require(o, "PLACED", "CONFIRMED");
                release(o);
                return move(o, "REJECTED", actor, "SHOP", str(body.get("reason")), false);
            }
            case "assign" -> {
                if (!HOME.equals(o.getFulfilmentType())) throw conflict("Visit-shop orders have no delivery employee");
                UUID staff = uuid(body.get("staffUserId"), "staffUserId");
                if (staff.equals(o.getAssignedStaffUserId()) && "ASSIGNED".equals(s)) return view(o, false);
                require(o, "CONFIRMED", "ASSIGNED", "ACCEPTED", "DELIVERY_FAILED");
                if (!isShopEmployee(staff, o.getShopId())) throw bad("Employee does not belong to this shop");
                if (!staff.equals(o.getAssignedStaffUserId())) presenceAvailability.requireAssignable(o.getShopId(), staff); // no-op unless enforcement is enabled
                boolean re = o.getAssignedStaffUserId() != null;
                o.setAssignedStaffUserId(staff);
                newPickupPin(o); // a new PIN per (re)assignment: an old one never works
                return move(o, "ASSIGNED", actor, "SHOP", re ? "Reassigned" : null, false);
            }
            case "handover" -> { // visit shop: customer paid in store and showed the OTP
                if ("COMPLETED".equals(s)) return view(o, false);
                if (!VISIT.equals(o.getFulfilmentType())) throw conflict("Home-delivery orders are completed by the employee");
                require(o, "CONFIRMED");
                verifyPaymentAndOtp(o, body);
                markSoldIfFullyHandedOver(o);
                return move(o, "COMPLETED", actor, "SHOP", "Paid & handed over in store", false);
            }
            case "return-received" -> {
                if ("RETURNED".equals(s)) return view(o, false);
                require(o, "DELIVERY_FAILED");
                release(o);
                return move(o, "RETURNED", actor, "SHOP", null, false);
            }
            default -> throw notFound("Unknown action");
        }
    }

    // ---------- employee ----------

    public List<Map<String, Object>> staffOrders(UUID staffUserId) {
        return orders.findByAssignedStaffUserIdOrderByCreatedAtDesc(staffUserId).stream().map(o -> view(o, false)).toList();
    }

    public Map<String, Object> staffOrder(UUID staffUserId, UUID shopId, UUID id) {
        return detail(staffOwned(staffUserId, shopId, load(id)), false);
    }

    @Transactional
    public Map<String, Object> staffAction(UUID staffUserId, UUID shopId, UUID id, String action, Map<String, Object> body) {
        MarketplaceOrder o = staffOwned(staffUserId, shopId, lock(id));
        String target = switch (action) {
            case "accept" -> "ACCEPTED";
            case "decline" -> "CONFIRMED";
            case "pickup" -> "PICKED_UP";
            case "start-delivery" -> "OUT_FOR_DELIVERY";
            case "deliver" -> "DELIVERED";
            case "fail" -> "DELIVERY_FAILED";
            default -> throw notFound("Unknown action");
        };
        if (target.equals(o.getStatus())) return view(o, false); // idempotent replay
        switch (action) {
            case "accept" -> require(o, "ASSIGNED");
            case "decline" -> { require(o, "ASSIGNED"); o.setAssignedStaffUserId(null); }
            case "pickup" -> {
                require(o, "ACCEPTED");
                if (body.get("pin") != null) {
                    // PIN flow: product / quantity / condition checked + shop PIN; no IMEI or photos at this step.
                    if (!Boolean.TRUE.equals(body.get("productVerified"))) throw bad("Verify product, quantity and condition first");
                    verifyPickupPin(o, str(body.get("pin")));
                } else { // older Employee apps: unchanged photo + IMEI proof
                    if (!Boolean.TRUE.equals(body.get("productVerified"))) throw bad("Verify product, IMEI, quantity and condition first");
                    o.setVerifiedImei(verifyImei(o, str(body.get("imei"))));
                    savePhotos(o, body, 1);
                }
                o.setPickupPin(null); // single use
                o.setPickupPinExpiresAt(null);
            }
            case "start-delivery" -> require(o, "PICKED_UP");
            case "deliver" -> {
                require(o, "OUT_FOR_DELIVERY");
                verifyPaymentAndOtp(o, body);
                markSoldIfFullyHandedOver(o);
                savePhotos(o, body, 1);
            }
            case "fail" -> {
                require(o, "ACCEPTED", "PICKED_UP", "OUT_FOR_DELIVERY");
                if (str(body.get("reason")) == null) throw bad("reason required");
            }
            default -> { }
        }
        return move(o, target, staffUserId, "STAFF", str(body.get("reason")), false);
    }

    // ---------- helpers ----------

    private static final Set<String> PIN_STAGES = Set.of("ASSIGNED", "ACCEPTED");
    private static final int PIN_MAX_TRIES = 5;
    private static final java.time.Duration PIN_TTL = java.time.Duration.ofHours(24);
    // Wrong tries per order (a thrown error rolls the transaction back, so not in the row).
    private final Map<UUID, int[]> pinTries = new java.util.concurrent.ConcurrentHashMap<>();

    private void newPickupPin(MarketplaceOrder o) {
        o.setPickupPin(String.format("%04d", RANDOM.nextInt(10_000)));
        o.setPickupPinExpiresAt(Instant.now().plus(PIN_TTL));
        pinTries.remove(o.getId());
    }

    private void verifyPickupPin(MarketplaceOrder o, String pin) {
        if (o.getPickupPin() == null || o.getPickupPinExpiresAt() == null || o.getPickupPinExpiresAt().isBefore(Instant.now()))
            throw bad("Pickup PIN expired. Ask the shop to reassign the pickup.");
        int[] t = pinTries.computeIfAbsent(o.getId(), k -> new int[1]);
        synchronized (t) {
            if (t[0] >= PIN_MAX_TRIES) throw bad("Too many wrong PINs. Ask the shop to reassign the pickup.");
            if (pin == null || !java.security.MessageDigest.isEqual(pin.getBytes(), o.getPickupPin().getBytes())) {
                t[0]++;
                throw bad("Invalid pickup PIN (" + (PIN_MAX_TRIES - t[0]) + " tries left)");
            }
        }
        pinTries.remove(o.getId());
    }

    private void verifyPaymentAndOtp(MarketplaceOrder o, Map<String, Object> body) {
        Object amt = body.get("amountCollected");
        BigDecimal collected;
        try { collected = amt == null ? null : new BigDecimal(amt.toString()); }
        catch (NumberFormatException e) { collected = null; }
        if (collected == null || collected.compareTo(o.getTotalAmount()) != 0) {
            throw bad("amountCollected must equal the order total " + o.getTotalAmount());
        }
        if (!Objects.equals(o.getHandoverOtp(), str(body.get("otp")))) throw bad("Invalid customer OTP");
        o.setPaymentStatus("PAID");
        o.setPaymentRef(str(body.get("paymentRef")));
    }

    @SuppressWarnings("unchecked")
    private void savePhotos(MarketplaceOrder o, Map<String, Object> body, int min) {
        if (!(body.get("photos") instanceof List<?> photos) || photos.size() < min
                || photos.stream().anyMatch(u -> !String.valueOf(u).startsWith("http"))) {
            throw bad("At least " + min + " photo URL(s) required");
        }
        try {
            List<Object> all = new ArrayList<>(o.getPhotosJson() == null ? List.of() : json.readValue(o.getPhotosJson(), List.class));
            all.addAll(photos);
            o.setPhotosJson(json.writeValueAsString(all));
        } catch (Exception e) { throw bad("Invalid photos"); }
    }

    // RESERVED → SOLD once no other order still holds this listing's stock.
    private void markSoldIfFullyHandedOver(MarketplaceOrder o) {
        products.findByIdForUpdate(o.getProductId()).ifPresent(p -> {
            if (!"RESERVED".equals(p.getStatus())) return;
            boolean othersOpen = orders.findByProductId(p.getId()).stream()
                    .anyMatch(x -> !x.getId().equals(o.getId()) && OPEN.contains(x.getStatus()));
            if (!othersOpen) { p.setStatus("SOLD"); products.save(p); }
        });
    }

    private void release(MarketplaceOrder o) {
        if (Boolean.TRUE.equals(o.getStockReleased())) return;
        products.findByIdForUpdate(o.getProductId()).ifPresent(p -> {
            p.setSoldQuantity(Math.max(0, nz(p.getSoldQuantity()) - nz(o.getQuantity())));
            if (("SOLD".equals(p.getStatus()) || "RESERVED".equals(p.getStatus())) && nz(p.getSoldQuantity()) < nz(p.getQuantity())) p.setStatus("ACTIVE");
            products.save(p);
        });
        o.setStockReleased(true);
    }

    private Map<String, Object> move(MarketplaceOrder o, String status, UUID actor, String role, String note, boolean customer) {
        o.setStatus(status);
        orders.save(o);
        log(o, status, actor, role, note);
        return view(o, customer);
    }

    private void log(MarketplaceOrder o, String status, UUID actor, String role, String note) {
        events.save(MarketplaceOrderEvent.builder().orderId(o.getId()).status(status).actorUserId(actor).actorRole(role).note(note).build());
    }

    private Map<String, Object> view(MarketplaceOrder o, boolean customer) {
        Optional<MarketplaceProduct> p = products.findById(o.getProductId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("orderNumber", o.getOrderNumber());
        m.put("shopId", o.getShopId());
        m.put("productId", o.getProductId());
        m.put("productTitle", p.map(MarketplaceProduct::getTitle).orElse(null));
        m.put("productImage", p.map(MarketplaceProduct::getImageUrl).orElse(null));
        m.put("quantity", o.getQuantity());
        m.put("unitPrice", o.getUnitPrice());
        m.put("totalAmount", o.getTotalAmount());
        m.put("status", o.getStatus());
        m.put("fulfilmentType", o.getFulfilmentType());
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("paymentStatus", o.getPaymentStatus());
        m.put("deliveryAddress", o.getDeliveryAddress());
        m.put("customerPhone", o.getCustomerPhone());
        m.put("assignedStaffUserId", o.getAssignedStaffUserId());
        m.put("createdAt", o.getCreatedAt());
        m.put("updatedAt", o.getUpdatedAt());
        if (customer) m.put("handoverOtp", o.getHandoverOtp()); // only the customer ever sees the OTP
        return m;
    }

    private Map<String, Object> detail(MarketplaceOrder o, boolean customer) {
        Map<String, Object> m = view(o, customer);
        m.put("verifiedImei", o.getVerifiedImei());
        if (!customer) { // shop + assigned employee: who to deliver to, where to pick up
            m.put("customerName", snapshots.loadCustomer(o.getCustomerUserId()).name());
            ChatSnapshotService.ShopSnapshot shop = snapshots.loadShop(o.getShopId());
            m.put("shopName", shop.name());
            m.put("shopPhone", shop.phone());
            m.put("shopAddress", shopAddress(o.getShopId()));
            m.put("productImeiRequired", products.findById(o.getProductId()).map(p -> p.getImei() != null && !p.getImei().isBlank()).orElse(false));
        }
        m.put("photos", o.getPhotosJson());
        m.put("events", events.findByOrderIdOrderByCreatedAtAsc(o.getId()).stream()
                .map(e -> Map.of("status", e.getStatus(), "at", e.getCreatedAt(), "role", String.valueOf(e.getActorRole()),
                        "note", e.getNote() == null ? "" : e.getNote())).toList());
        return m;
    }

    private MarketplaceOrder load(UUID id) { return orders.findById(id).filter(o -> o.getProductId() != null).orElseThrow(() -> notFound("Order not found")); }
    private MarketplaceOrder lock(UUID id) { return orders.findByIdForUpdate(id).filter(o -> o.getProductId() != null).orElseThrow(() -> notFound("Order not found")); }

    private MarketplaceOrder shopOwned(UUID shopId, MarketplaceOrder o) {
        if (!shopId.equals(o.getShopId())) throw notFound("Order not found");
        return o;
    }

    private MarketplaceOrder staffOwned(UUID staff, UUID shopId, MarketplaceOrder o) {
        if (!staff.equals(o.getAssignedStaffUserId()) || shopId == null || !shopId.equals(o.getShopId())) throw notFound("Order not found");
        return o;
    }

    /** IMEI/serial seen on the device at pickup: must match a listed IMEI; else a valid IMEI or serial if given. */
    private String verifyImei(MarketplaceOrder o, String seen) {
        String listed = products.findById(o.getProductId()).map(MarketplaceProduct::getImei).filter(v -> !v.isBlank()).orElse(null);
        String v = seen == null ? null : seen.replaceAll("\\s", "").toUpperCase();
        if (listed != null) {
            if (v == null) throw bad("Enter the IMEI shown on the device");
            if (!listed.replaceAll("\\s", "").equalsIgnoreCase(v)) throw bad("IMEI does not match the listed device");
            return v;
        }
        if (v == null) return null;
        if (v.matches("\\d{15}") ? !luhn(v) : !v.matches("[A-Z0-9-]{4,30}")) throw bad("Invalid IMEI / serial number");
        return v;
    }

    private static boolean luhn(String d) {
        int sum = 0;
        for (int i = 0; i < d.length(); i++) {
            int n = d.charAt(d.length() - 1 - i) - '0';
            if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9; }
            sum += n;
        }
        return sum % 10 == 0;
    }

    private String shopAddress(UUID shopId) {
        try {
            return jdbc.queryForObject("SELECT address FROM shops WHERE id = ?", String.class, shopId);
        } catch (Exception e) { // H2 tests have no shops table; never fail the order view over it
            return null;
        }
    }

    private boolean isShopEmployee(UUID userId, UUID shopId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ? AND shop_id = ? AND COALESCE(is_active, TRUE)", Integer.class, userId, shopId);
        return n != null && n > 0;
    }

    private static void require(MarketplaceOrder o, String... allowed) {
        if (!Arrays.asList(allowed).contains(o.getStatus())) throw conflict("Not allowed while order is " + o.getStatus());
    }

    private static boolean hasOption(MarketplaceProduct p, String opt) {
        return p.getFulfilmentOptions() != null && Arrays.asList(p.getFulfilmentOptions().toUpperCase().split("\\s*,\\s*")).contains(opt);
    }

    private String orderNumber() { return "NGF-BUY-" + (100000 + RANDOM.nextInt(900000)); }
    private static String otp() { return String.format("%04d", RANDOM.nextInt(10000)); }
    private static int nz(Integer i) { return i == null ? 0 : i; }
    private static String str(Object o) { return o == null || o.toString().isBlank() ? null : o.toString().trim(); }
    private static UUID uuid(Object o, String name) {
        try { return UUID.fromString(String.valueOf(o)); } catch (Exception e) { throw bad(name + " required"); }
    }
    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }
    private static ResponseStatusException notFound(String m) { return new ResponseStatusException(HttpStatus.NOT_FOUND, m); }
}
