package com.repairshop.saas.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repairshop.saas.common.notify.ShopNotificationWriter;
import com.repairshop.saas.common.notify.ShopPushService;
import com.repairshop.saas.common.subscription.SubscriptionFeature;
import com.repairshop.saas.common.subscription.SubscriptionLimitService;
import com.repairshop.saas.order.dto.SellOrderDtos.*;
import com.repairshop.saas.order.entity.*;
import com.repairshop.saas.order.exception.ForbiddenException;
import com.repairshop.saas.order.exception.ResourceNotFoundException;
import com.repairshop.saas.order.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/sell-orders")
@RequiredArgsConstructor
@Slf4j
public class SellOrderController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final SellOrderRepository sellRepo;
    private final SellOrderScreeningAnswerRepository answerRepo;
    private final SellOrderConditionRepository conditionRepo;
    private final SellOrderIssueRepository issueRepo;
    private final SellOrderAccessoryRepository accRepo;
    private final SellOrderQuotationRepository quoteRepo;
    private final CustomerOrderRepository customerOrderRepo;
    private final ObjectMapper objectMapper;
    private final SubscriptionLimitService subscriptionLimits;
    private final ShopNotificationRepository shopNotificationRepo;
    private final ShopNotificationWriter shopNotificationWriter;
    private final ShopPushService shopPush;
    private final JdbcTemplate jdbc;

    @PostMapping
    @Transactional
    public ResponseEntity<SellOrderResponse> create(HttpServletRequest req, @RequestBody SellOrderRequest body) {
        UUID userId = callerId(req);
        String sellNumber = uniqueSellNumber();
        ImageBundle imgs = body.getImages() != null ? body.getImages() : ImageBundle.builder().build();
        SellOrder saved = sellRepo.save(SellOrder.builder()
                .sellNumber(sellNumber)
                .customerUserId(userId)
                .addressId(body.getAddressId())
                .brandId(body.getBrandId())
                .modelId(body.getModelId())
                .ramOptionId(body.getRamOptionId())
                .storageOptionId(body.getStorageOptionId())
                .color(body.getColor())
                .imei(body.getImei())
                .workingCondition(body.getWorkingCondition())
                .warrantyCode(body.getWarrantyCode())
                .frontImageUrl(imgs.getFront())
                .backImageUrl(imgs.getBack())
                .sideImageUrl(imgs.getSide())
                .cameraImageUrl(imgs.getCamera())
                .otherImageUrl(imgs.getOther())
                .pickupDate(body.getPickupDate())
                .pickupSlotStart(body.getPickupSlotStart())
                .pickupSlotEnd(body.getPickupSlotEnd())
                .status("AWAITING_QUOTATION")
                .build());

        if (body.getScreeningAnswers() != null) for (ScreeningAnswerRow a : body.getScreeningAnswers()) {
            answerRepo.save(SellOrderScreeningAnswer.builder()
                    .sellOrderId(saved.getId()).questionId(a.getQuestionId())
                    .question(a.getQuestion()).answer(a.getAnswer()).build());
        }
        if (body.getConditions() != null) for (ConditionRow c : body.getConditions()) {
            conditionRepo.save(SellOrderCondition.builder()
                    .sellOrderId(saved.getId()).groupCode(c.getGroupCode()).groupName(c.getGroupName())
                    .optionId(c.getOptionId()).optionLabel(c.getOptionLabel()).build());
        }
        if (body.getIssues() != null) for (IssueRow i : body.getIssues()) {
            issueRepo.save(SellOrderIssue.builder()
                    .sellOrderId(saved.getId()).issueId(i.getIssueId()).issueCode(i.getIssueCode()).build());
        }
        if (body.getAccessories() != null) for (AccessoryRow a : body.getAccessories()) {
            accRepo.save(SellOrderAccessory.builder()
                    .sellOrderId(saved.getId()).accessoryId(a.getAccessoryId())
                    .accessoryCode(a.getAccessoryCode()).label(a.getLabel()).build());
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("title", "Sell Device");
        payload.put("sellOrderId", saved.getId());
        payload.put("brandId", body.getBrandId());
        payload.put("modelId", body.getModelId());
        payload.put("workingCondition", body.getWorkingCondition());
        String payloadJson;
        try { payloadJson = objectMapper.writeValueAsString(payload); }
        catch (Exception e) { payloadJson = null; }
        customerOrderRepo.save(CustomerOrder.builder()
                .orderNumber(sellNumber)
                .customerUserId(userId)
                .orderType("SELL")
                .referenceId(saved.getId())
                .status("PENDING")
                .payloadJson(payloadJson)
                .build());

        // Any active shop may quote, so every one of them hears about it. Runs
        // after commit, off the request thread: one feed row per shop is a
        // best-effort side effect and must not be able to fail (or slow) the
        // customer's sell request.
        UUID sellerShopId = optionalShopId(req);
        shopPush.afterCommit(() -> notifyShopsOfSellRequest(saved, userId, sellerShopId));

        return ResponseEntity.ok(toResponseWithQuotes(saved));
    }

    @GetMapping
    public ResponseEntity<List<SellOrderResponse>> list(HttpServletRequest req) {
        UUID userId = callerId(req);
        return ResponseEntity.ok(sellRepo.findByCustomerUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SellOrderResponse> get(HttpServletRequest req, @PathVariable UUID id) {
        UUID userId = callerId(req);
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sell order not found"));
        if (!s.getCustomerUserId().equals(userId)) throw new ForbiddenException("Not your sell order");
        return ResponseEntity.ok(toDetails(s));
    }

    /**
     * A shop quotes on a customer's sell order.
     *
     * <p>This is where the "up to N sell orders" allowance is enforced, because
     * it is the only step in the sell flow the SHOP performs — the order itself
     * is created by a customer, and the winning quotation is picked by a
     * customer. Blocking either of those would take the plan limit of one shop
     * out on someone else.
     *
     * <p>Usage counts orders the shop has already WON
     * ({@code sell_orders.shop_id}), not quotations submitted: a quote the
     * customer declined earned the shop nothing and should not burn allowance.
     * So a shop at its limit may not bid for more work, while everything it has
     * already taken on runs to completion.
     */
    @PostMapping("/{id}/quotations")
    @Transactional
    public ResponseEntity<SellQuotationResponse> addQuote(HttpServletRequest req, @PathVariable UUID id, @RequestBody SellOrderQuotationRequest body) {
        // The quoting shop is the token's shop, never a shopId from the body.
        Object tokenShop = req.getAttribute("shopId");
        if (tokenShop == null) throw new ForbiddenException("A shop session is required to quote");
        UUID shopId = UUID.fromString(tokenShop.toString());
        if (!sellRepo.existsById(id)) throw new ResourceNotFoundException("Sell order not found");
        subscriptionLimits.requireUsageCapacity(null, shopId, SubscriptionFeature.SELL_ORDERS);
        SellOrderQuotation q = quoteRepo.save(SellOrderQuotation.builder()
                .sellOrderId(id).shopId(shopId)
                .shopName(body.getShopName()).shopPhone(body.getShopPhone()).shopCity(body.getShopCity())
                .quotationPrice(body.getQuotationPrice()).note(body.getNote()).status("PROPOSED").build());
        return ResponseEntity.ok(toQuoteResp(q));
    }

    @GetMapping("/{id}/quotations")
    public ResponseEntity<List<SellQuotationResponse>> listQuotes(HttpServletRequest req, @PathVariable UUID id) {
        // The order's customer sees every quote; a shop sees only its own
        // (other shops' prices stay private).
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sell order not found"));
        UUID userId = callerId(req);
        Object tokenShop = req.getAttribute("shopId");
        UUID shopId = tokenShop == null ? null : UUID.fromString(tokenShop.toString());
        if (!s.getCustomerUserId().equals(userId) && shopId == null) throw new ForbiddenException("Not your sell order");
        boolean customer = s.getCustomerUserId().equals(userId);
        return ResponseEntity.ok(quoteRepo.findBySellOrderId(id).stream()
                .filter(q -> customer || q.getShopId().equals(shopId))
                .map(this::toQuoteResp).toList());
    }

    @PostMapping("/{id}/choose-quotation")
    @Transactional
    public ResponseEntity<SellOrderResponse> choose(HttpServletRequest req, @PathVariable UUID id, @RequestBody ChooseQuotationRequest body) {
        UUID userId = callerId(req);
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sell order not found"));
        if (!s.getCustomerUserId().equals(userId)) throw new ForbiddenException("Not your sell order");
        List<SellOrderQuotation> quotes = quoteRepo.findBySellOrderId(id);
        SellOrderQuotation chosen = quotes.stream().filter(q -> q.getId().equals(body.getQuotationId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Quotation not found"));
        for (SellOrderQuotation q : quotes) {
            q.setStatus(q.getId().equals(chosen.getId()) ? "ACCEPTED" : "REJECTED");
            quoteRepo.save(q);
        }
        s.setShopId(chosen.getShopId());
        s.setFinalPrice(chosen.getQuotationPrice());
        s.setStatus("SELL_CONFIRMED");
        sellRepo.save(s);
        customerOrderRepo.findByOrderNumber(s.getSellNumber()).ifPresent(co -> {
            co.setStatus("CONFIRMED");
            co.setShopId(chosen.getShopId());
            co.setTotalAmount(chosen.getQuotationPrice());
            customerOrderRepo.save(co);
        });
        notifyQuoteAccepted(s, chosen);
        return ResponseEntity.ok(toResponseWithQuotes(s));
    }

    // ── Partner app notifications ───────────────────────────────────────────

    /** Feed row + push for every active shop except the seller's own. */
    private void notifyShopsOfSellRequest(SellOrder s, UUID sellerUserId, UUID sellerShopId) {
        String title = "New sell request";
        String device = deviceName(s.getBrandId(), s.getModelId());
        String body = (device != null ? device : "A device") + " is up for sale ("
                + s.getSellNumber() + ") - tap to send your quote.";
        Map<UUID, UUID> rows = shopNotificationWriter.insertForActiveShops(
                sellerUserId, sellerShopId, "sell", "SELL_REQUESTED", title, body, s.getSellNumber());
        Map<UUID, Map<String, Object>> dataByShop = new HashMap<>();
        rows.forEach((shopId, notificationId) -> {
            Map<String, Object> data = sellPushData(s, "SELL_REQUESTED");
            data.put("notificationId", notificationId.toString());
            dataByShop.put(shopId, data);
        });
        shopPush.pushEach(title, body, dataByShop);
    }

    /** The customer picked this shop's quotation — tell the shop it won the order. */
    private void notifyQuoteAccepted(SellOrder s, SellOrderQuotation chosen) {
        if (chosen.getShopId() == null) return;
        String title = "Your quote was accepted";
        String body = "Sell request " + s.getSellNumber() + " - the customer accepted your quote"
                + (chosen.getQuotationPrice() != null ? " of ₹" + plain(chosen.getQuotationPrice()) : "")
                + ". Tap to view details.";
        ShopNotification n = shopNotificationRepo.save(ShopNotification.builder()
                .shopId(chosen.getShopId())
                .bookingNumber(s.getSellNumber())
                .statusKey("SELL_CONFIRMED")
                .title(title)
                .body(body)
                .type("sell")
                .read(false)
                .build());
        Map<String, Object> data = sellPushData(s, "SELL_CONFIRMED");
        data.put("notificationId", String.valueOf(n.getId()));
        data.put("quotationId", String.valueOf(chosen.getId()));
        shopPush.pushToShop(chosen.getShopId(), title, body, data);
    }

    private static Map<String, Object> sellPushData(SellOrder s, String statusKey) {
        Map<String, Object> data = new HashMap<>();
        data.put("kind", "SELL_REQUEST");
        data.put("sellOrderId", String.valueOf(s.getId()));
        data.put("sellNumber", s.getSellNumber());
        data.put("statusKey", statusKey);
        return data;
    }

    /** "Samsung Galaxy S21" from the master tables; null when neither resolves. */
    private String deviceName(UUID brandId, UUID modelId) {
        String brand = masterName("master_brands", brandId);
        String model = masterName("master_models", modelId);
        if (model == null) return brand;
        if (brand == null || model.toLowerCase().startsWith(brand.toLowerCase())) return model;
        return brand + " " + model;
    }

    private String masterName(String table, UUID id) {
        if (id == null) return null;
        try {
            List<String> rows = jdbc.query("SELECT name FROM " + table + " WHERE id = ?",
                    (rs, i) -> rs.getString("name"), id);
            return rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank() ? null : rows.get(0).trim();
        } catch (Exception e) {
            log.debug("{} lookup failed: {}", table, e.getMessage());
            return null;
        }
    }

    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    // Set for shop-owner / SHOP-login tokens; null for a customer account.
    private UUID optionalShopId(HttpServletRequest req) {
        Object s = req.getAttribute("shopId");
        try { return s != null ? UUID.fromString(s.toString()) : null; }
        catch (IllegalArgumentException e) { return null; }
    }

    // Customer-side edit. Allowed only while the sell order is still
    // "editable" (no shop has committed to a quotation yet). Replaces device
    // fields and the child rows (screening answers / conditions / issues /
    // accessories) when those arrays are present in the body.
    private static final java.util.Set<String> CUSTOMER_LOCKED = java.util.Set.of(
            "PICKED_UP", "DEVICE_RECEIVED", "INSPECTED", "PRICE_REVISION_PENDING", "REVISION_REJECTED",
            "PRICE_CONFIRMED", "PAYMENT_PENDING", "PAID", "COMPLETED"
    );

    private static final java.util.Set<String> EDITABLE_STATUSES = java.util.Set.of(
            "PENDING", "AWAITING_QUOTATION", "DRAFT"
    );

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<SellOrderResponse> update(HttpServletRequest req,
                                                    @PathVariable UUID id,
                                                    @RequestBody SellOrderRequest body) {
        UUID userId = callerId(req);
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sell order not found"));
        if (!s.getCustomerUserId().equals(userId)) throw new ForbiddenException("Not your sell order");
        String statusUpper = s.getStatus() == null ? "" : s.getStatus().toUpperCase();
        if (!EDITABLE_STATUSES.contains(statusUpper)) {
            throw new ForbiddenException("This sell order can no longer be edited (status: " + statusUpper + ")");
        }

        // Replace only the fields the caller actually sent. UUID fields are
        // applied unconditionally when non-null; string fields treat empty
        // string as a clear (so the customer can wipe IMEI/color).
        if (body.getBrandId() != null) s.setBrandId(body.getBrandId());
        if (body.getModelId() != null) s.setModelId(body.getModelId());
        if (body.getRamOptionId() != null) s.setRamOptionId(body.getRamOptionId());
        if (body.getStorageOptionId() != null) s.setStorageOptionId(body.getStorageOptionId());
        if (body.getAddressId() != null) s.setAddressId(body.getAddressId());
        if (body.getColor() != null) s.setColor(body.getColor().isBlank() ? null : body.getColor());
        if (body.getImei() != null) s.setImei(body.getImei().isBlank() ? null : body.getImei());
        if (body.getWorkingCondition() != null) s.setWorkingCondition(body.getWorkingCondition());
        if (body.getWarrantyCode() != null) s.setWarrantyCode(body.getWarrantyCode());
        if (body.getPickupDate() != null) s.setPickupDate(body.getPickupDate());
        if (body.getPickupSlotStart() != null) s.setPickupSlotStart(body.getPickupSlotStart());
        if (body.getPickupSlotEnd() != null) s.setPickupSlotEnd(body.getPickupSlotEnd());

        ImageBundle imgs = body.getImages();
        if (imgs != null) {
            if (imgs.getFront() != null) s.setFrontImageUrl(imgs.getFront().isBlank() ? null : imgs.getFront());
            if (imgs.getBack() != null) s.setBackImageUrl(imgs.getBack().isBlank() ? null : imgs.getBack());
            if (imgs.getSide() != null) s.setSideImageUrl(imgs.getSide().isBlank() ? null : imgs.getSide());
            if (imgs.getCamera() != null) s.setCameraImageUrl(imgs.getCamera().isBlank() ? null : imgs.getCamera());
            if (imgs.getOther() != null) s.setOtherImageUrl(imgs.getOther().isBlank() ? null : imgs.getOther());
        }

        sellRepo.save(s);

        // Replace child collections only when the caller explicitly sent them.
        if (body.getScreeningAnswers() != null) {
            answerRepo.deleteBySellOrderId(s.getId());
            for (ScreeningAnswerRow a : body.getScreeningAnswers()) {
                answerRepo.save(SellOrderScreeningAnswer.builder()
                        .sellOrderId(s.getId()).questionId(a.getQuestionId())
                        .question(a.getQuestion()).answer(a.getAnswer()).build());
            }
        }
        if (body.getConditions() != null) {
            conditionRepo.deleteBySellOrderId(s.getId());
            for (ConditionRow c : body.getConditions()) {
                conditionRepo.save(SellOrderCondition.builder()
                        .sellOrderId(s.getId()).groupCode(c.getGroupCode()).groupName(c.getGroupName())
                        .optionId(c.getOptionId()).optionLabel(c.getOptionLabel()).build());
            }
        }
        if (body.getIssues() != null) {
            issueRepo.deleteBySellOrderId(s.getId());
            for (IssueRow i : body.getIssues()) {
                issueRepo.save(SellOrderIssue.builder()
                        .sellOrderId(s.getId()).issueId(i.getIssueId()).issueCode(i.getIssueCode()).build());
            }
        }
        if (body.getAccessories() != null) {
            accRepo.deleteBySellOrderId(s.getId());
            for (AccessoryRow a : body.getAccessories()) {
                accRepo.save(SellOrderAccessory.builder()
                        .sellOrderId(s.getId()).accessoryId(a.getAccessoryId())
                        .accessoryCode(a.getAccessoryCode()).label(a.getLabel()).build());
            }
        }

        // Keep the matching customer_orders row consistent if anything that's
        // surfaced there changed (we only echo title-side fields here).
        customerOrderRepo.findByOrderNumber(s.getSellNumber()).ifPresent(co -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("title", "Sell Device");
            payload.put("sellOrderId", s.getId());
            payload.put("brandId", s.getBrandId());
            payload.put("modelId", s.getModelId());
            payload.put("workingCondition", s.getWorkingCondition());
            try { co.setPayloadJson(objectMapper.writeValueAsString(payload)); }
            catch (Exception ignored) {}
            customerOrderRepo.save(co);
        });

        return ResponseEntity.ok(toDetails(s));
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<SellOrderResponse> cancel(HttpServletRequest req, @PathVariable UUID id) {
        UUID userId = callerId(req);
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sell order not found"));
        if (!s.getCustomerUserId().equals(userId)) throw new ForbiddenException("Not your sell order");
        // Once the shop has the device (or money moved), the shop handles it (Partner purchase workflow).
        if (CUSTOMER_LOCKED.contains(String.valueOf(s.getStatus()).toUpperCase())) {
            throw new com.repairshop.saas.order.exception.ConflictException("Your device is already with the shop — contact the shop to cancel");
        }
        // Paid at the door (Employee App, still OUT_FOR_PICKUP): the money already moved.
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'sell_orders' AND column_name = 'payout_recorded_at')", Boolean.class))
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT payout_recorded_at IS NOT NULL FROM sell_orders WHERE id = ?", Boolean.class, id))) {
            throw new com.repairshop.saas.order.exception.ConflictException("You've already been paid for this device — contact the shop to cancel");
        }
        boolean wasCancelled = "CANCELLED".equalsIgnoreCase(s.getStatus());
        s.setStatus("CANCELLED");
        sellRepo.save(s);
        customerOrderRepo.findByOrderNumber(s.getSellNumber()).ifPresent(co -> {
            co.setStatus("CANCELLED");
            customerOrderRepo.save(co);
        });
        if (!wasCancelled && s.getShopId() != null) {
            shopNotificationRepo.save(ShopNotification.builder()
                    .shopId(s.getShopId()).bookingId(s.getId()).bookingNumber(s.getSellNumber())
                    .statusKey("SELL_CANCELLED_BY_CUSTOMER").title("Sale cancelled by the customer")
                    .body(String.format("The customer cancelled %s.", s.getSellNumber())).type("purchase").read(false).build());
        }
        return ResponseEntity.ok(toResponseWithQuotes(s));
    }

    /** Statuses where the customer may still set / move the pickup time (before the journey starts). */
    private static final java.util.Set<String> CUSTOMER_SCHEDULABLE = java.util.Set.of(
            "SELL_CONFIRMED", "PICKUP_SCHEDULED", "PICKUP_ASSIGNED");
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    /**
     * Customer app "Schedule Pickup": saves the chosen date + slot on the sell
     * order so the shop (Partner Buy → Assign Pickup / Tracking) sees the same
     * time. Body: { pickupDate: "yyyy-MM-dd", slotStart: "HH:mm", slotEnd?: "HH:mm" }.
     */
    @PostMapping("/{id}/schedule")
    @Transactional
    public ResponseEntity<SellOrderResponse> schedule(HttpServletRequest req, @PathVariable UUID id,
                                                      @RequestBody Map<String, String> body) {
        UUID userId = callerId(req);
        SellOrder s = sellRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sell order not found"));
        if (!s.getCustomerUserId().equals(userId)) throw new ForbiddenException("Not your sell order");
        if (!CUSTOMER_SCHEDULABLE.contains(String.valueOf(s.getStatus()).toUpperCase())) {
            throw new com.repairshop.saas.order.exception.ConflictException("The pickup time can't be changed any more");
        }
        java.time.LocalDate date;
        java.time.LocalTime start;
        java.time.LocalTime end;
        try {
            date = java.time.LocalDate.parse(java.util.Objects.requireNonNull(body == null ? null : body.get("pickupDate")));
            start = java.time.LocalTime.parse(java.util.Objects.requireNonNull(body.get("slotStart")));
            String e = body.get("slotEnd");
            end = e == null || e.isBlank() ? start.plusHours(2) : java.time.LocalTime.parse(e);
        } catch (java.time.format.DateTimeParseException | NullPointerException ex) {
            throw new IllegalArgumentException("Pick a valid date and time");
        }
        java.time.LocalDate today = java.time.LocalDate.now(IST);
        if (date.isBefore(today)) throw new IllegalArgumentException("Pick a date from today");
        if (date.isAfter(today.plusDays(60))) throw new IllegalArgumentException("Pick a date within the next 60 days");
        if (!end.isAfter(start)) end = start.plusHours(2);

        boolean moved = s.getPickupDate() != null;
        s.setPickupDate(date);
        s.setPickupSlotStart(start);
        s.setPickupSlotEnd(end);
        if ("SELL_CONFIRMED".equalsIgnoreCase(s.getStatus())) s.setStatus("PICKUP_SCHEDULED");
        sellRepo.save(s);
        if (s.getShopId() != null) {
            String when = date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")) + ", "
                    + start.format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"));
            shopNotificationRepo.save(ShopNotification.builder()
                    .shopId(s.getShopId()).bookingId(s.getId()).bookingNumber(s.getSellNumber())
                    .statusKey(moved ? "SELL_PICKUP_RESCHEDULED_BY_CUSTOMER" : "SELL_PICKUP_SCHEDULED_BY_CUSTOMER")
                    .title(moved ? "Customer changed the pickup time" : "Customer chose a pickup time")
                    .body(String.format("%s: %s.", s.getSellNumber(), when)).type("purchase").read(false).build());
        }
        return ResponseEntity.ok(toResponseWithQuotes(s));
    }

    private SellOrderResponse toResponse(SellOrder s) {
        return SellOrderResponse.builder()
                .id(s.getId()).sellNumber(s.getSellNumber()).shopId(s.getShopId()).addressId(s.getAddressId())
                .brandId(s.getBrandId()).modelId(s.getModelId())
                .ramOptionId(s.getRamOptionId()).storageOptionId(s.getStorageOptionId())
                .color(s.getColor()).imei(s.getImei())
                .workingCondition(s.getWorkingCondition()).warrantyCode(s.getWarrantyCode())
                .status(s.getStatus()).finalPrice(s.getFinalPrice())
                .pickupDate(s.getPickupDate()).pickupSlotStart(s.getPickupSlotStart()).pickupSlotEnd(s.getPickupSlotEnd())
                .createdAt(s.getCreatedAt()).updatedAt(s.getUpdatedAt())
                .build();
    }

    private SellOrderResponse toResponseWithQuotes(SellOrder s) {
        SellOrderResponse r = toResponse(s);
        r.setQuotations(quoteRepo.findBySellOrderId(s.getId()).stream().map(this::toQuoteResp).toList());
        return r;
    }

    private SellOrderResponse toDetails(SellOrder s) {
        SellOrderResponse r = toResponseWithQuotes(s);
        r.setDeviceConditionSummary(s.getDeviceConditionSummary());
        r.setImages(ImageBundle.builder()
                .front(s.getFrontImageUrl()).back(s.getBackImageUrl()).side(s.getSideImageUrl())
                .camera(s.getCameraImageUrl()).other(s.getOtherImageUrl()).build());
        r.setScreeningAnswers(answerRepo.findBySellOrderId(s.getId()).stream()
                .map(a -> ScreeningAnswerRow.builder()
                        .questionId(a.getQuestionId()).question(a.getQuestion()).answer(a.getAnswer()).build())
                .toList());
        r.setConditions(conditionRepo.findBySellOrderId(s.getId()).stream()
                .map(c -> ConditionRow.builder()
                        .groupCode(c.getGroupCode()).groupName(c.getGroupName())
                        .optionId(c.getOptionId()).optionLabel(c.getOptionLabel()).build())
                .toList());
        r.setIssues(issueRepo.findBySellOrderId(s.getId()).stream()
                .map(i -> IssueRow.builder().issueId(i.getIssueId()).issueCode(i.getIssueCode()).build())
                .toList());
        r.setAccessories(accRepo.findBySellOrderId(s.getId()).stream()
                .map(a -> AccessoryRow.builder()
                        .accessoryId(a.getAccessoryId()).accessoryCode(a.getAccessoryCode()).label(a.getLabel()).build())
                .toList());
        return r;
    }

    private SellQuotationResponse toQuoteResp(SellOrderQuotation q) {
        return SellQuotationResponse.builder()
                .id(q.getId()).sellOrderId(q.getSellOrderId()).shopId(q.getShopId())
                .shopName(q.getShopName()).shopPhone(q.getShopPhone()).shopCity(q.getShopCity())
                .quotationPrice(q.getQuotationPrice()).note(q.getNote()).status(q.getStatus()).createdAt(q.getCreatedAt())
                .build();
    }

    private String uniqueSellNumber() {
        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder("#CSPQX");
            for (int j = 0; j < 8; j++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String c = sb.toString();
            if (sellRepo.findBySellNumber(c).isEmpty()) return c;
        }
        throw new IllegalStateException("Could not generate unique sell number");
    }

    private UUID callerId(HttpServletRequest req) {
        Object u = req.getAttribute("userId");
        if (u == null) throw new ForbiddenException("Missing userId");
        return UUID.fromString(u.toString());
    }
}
