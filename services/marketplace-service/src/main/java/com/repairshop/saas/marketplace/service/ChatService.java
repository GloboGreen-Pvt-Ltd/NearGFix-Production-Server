package com.repairshop.saas.marketplace.service;

import com.repairshop.saas.common.notify.ShopNotificationWriter;
import com.repairshop.saas.common.notify.ShopPushService;
import com.repairshop.saas.marketplace.dto.ChatMessageResponse;
import com.repairshop.saas.marketplace.dto.ChatSendRequest;
import com.repairshop.saas.marketplace.dto.ChatThreadResponse;
import com.repairshop.saas.marketplace.entity.CustomerChatMessage;
import com.repairshop.saas.marketplace.entity.CustomerChatThread;
import com.repairshop.saas.marketplace.exception.ResourceNotFoundException;
import com.repairshop.saas.marketplace.repository.CustomerChatMessageRepository;
import com.repairshop.saas.marketplace.repository.CustomerChatThreadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for chat behaviour, shared by ChatController
 * (customer side) and ShopChatController (shop side). Centralizing here keeps
 * the unread-counter / read-receipt / preview bookkeeping in one place so the
 * two sides cannot drift.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    public enum Side { CUSTOMER, SHOP }

    // Counterpart is "online" if last_seen_at within this window.
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(60);

    // First line of a Customer-app buy order message ("🛒 New order #ORD123").
    // Same rule as the Partner app's utils/buyOrders.js (ORDER_HEAD) — keep the
    // two in step, or a message the app lists as an order is pushed as chat.
    private static final Pattern ORDER_HEAD = Pattern.compile(
            "^\uD83D\uDED2\\s*New order\\s*(#\\S+)?", Pattern.UNICODE_CHARACTER_CLASS);

    private final CustomerChatThreadRepository threadRepo;
    private final CustomerChatMessageRepository msgRepo;
    private final ChatSnapshotService snapshots;
    private final ShopPushService shopPush;
    private final ShopNotificationWriter shopNotifications;

    // -------------------------------------------------------------- thread ops

    /** Customer opens a thread with a shop — idempotent. */
    @Transactional
    public CustomerChatThread openCustomerThread(UUID customerUserId, UUID shopId) {
        if (customerUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user context");
        }
        if (shopId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "shopId is required");
        }
        // Snapshot lookups are best-effort and tolerate missing tables — the
        // chat still creates with a placeholder name when the auth-service
        // tables aren't visible from this datasource (e.g. H2 dev profile).
        return threadRepo.findByCustomerUserIdAndShopId(customerUserId, shopId)
            .orElseGet(() -> {
                ChatSnapshotService.CustomerSnapshot cs = snapshots.loadCustomer(customerUserId);
                ChatSnapshotService.ShopSnapshot ss = snapshots.loadShop(shopId);
                return threadRepo.save(CustomerChatThread.builder()
                    .customerUserId(customerUserId)
                    .shopId(shopId)
                    .subject("Conversation")
                    .customerName(cs.name())
                    .customerMobile(cs.mobile())
                    .customerAvatarUrl(cs.avatarUrl())
                    .shopName(ss.name() != null ? ss.name() : "Shop")
                    .shopImageUrl(ss.imageUrl())
                    .unreadCustomerCount(0)
                    .unreadShopCount(0)
                    .build());
            });
    }

    public List<CustomerChatThread> listForCustomer(UUID customerUserId) {
        return threadRepo.findByCustomerUserIdOrderByLastMessageAtDesc(customerUserId);
    }

    public List<CustomerChatThread> listForShop(UUID shopId) {
        return threadRepo.findByShopIdOrderByLastMessageAtDesc(shopId);
    }

    public CustomerChatThread requireThread(UUID threadId) {
        return threadRepo.findById(threadId)
            .orElseThrow(() -> new ResourceNotFoundException("Thread not found: " + threadId));
    }

    public CustomerChatThread requireOwnedByCustomer(UUID threadId, UUID customerUserId) {
        CustomerChatThread t = requireThread(threadId);
        if (!t.getCustomerUserId().equals(customerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your thread");
        }
        return t;
    }

    public CustomerChatThread requireOwnedByShop(UUID threadId, UUID shopId) {
        CustomerChatThread t = requireThread(threadId);
        if (t.getShopId() == null || !t.getShopId().equals(shopId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your thread");
        }
        return t;
    }

    // -------------------------------------------------------------- messaging

    public List<CustomerChatMessage> messagesFor(UUID threadId) {
        return msgRepo.findByThreadIdOrderByCreatedAtAsc(threadId);
    }

    @Transactional
    public CustomerChatMessage send(UUID threadId, Side from, ChatSendRequest body) {
        return send(threadId, from, body, null);
    }

    /** {@code senderShopId}: the sending account's own shop, when its token
     *  carries one — that shop is never notified about its own message. */
    @Transactional
    public CustomerChatMessage send(UUID threadId, Side from, ChatSendRequest body, UUID senderShopId) {
        if ((body.getBody() == null || body.getBody().isBlank())
            && (body.getAttachmentUrl() == null || body.getAttachmentUrl().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty message");
        }
        CustomerChatThread t = requireThread(threadId);
        String senderTag = from == Side.CUSTOMER ? "CUSTOMER" : "SHOP";
        CustomerChatMessage m = msgRepo.save(CustomerChatMessage.builder()
            .threadId(threadId)
            .sender(senderTag)
            .body(body.getBody() != null ? body.getBody() : "")
            .attachmentUrl(body.getAttachmentUrl())
            .attachmentType(body.getAttachmentType())
            .build());

        t.setLastMessageAt(Instant.now());
        t.setLastMessagePreview(buildPreview(body));
        // Bump the other side's unread counter.
        if (from == Side.CUSTOMER) {
            t.setUnreadShopCount((t.getUnreadShopCount() == null ? 0 : t.getUnreadShopCount()) + 1);
            // Sender clears their own typing flag.
            t.setCustomerTypingUntil(null);
        } else {
            t.setUnreadCustomerCount((t.getUnreadCustomerCount() == null ? 0 : t.getUnreadCustomerCount()) + 1);
            t.setShopTypingUntil(null);
        }
        threadRepo.save(t);
        if (from == Side.CUSTOMER) notifyShop(t, m, body, senderShopId);
        return m;
    }

    /**
     * Partner app alert for a message the shop received. A buy order message
     * (see ORDER_HEAD) gets a "buy" feed row plus a BUY_ORDER push; anything
     * else is a CHAT push only — the Chats tab's unread badge already covers
     * plain messages in-app. Both go out after this transaction commits; the
     * feed row is written then too, so it can never fail the message itself.
     */
    private void notifyShop(CustomerChatThread t, CustomerChatMessage m, ChatSendRequest body, UUID senderShopId) {
        UUID shopId = t.getShopId();
        if (shopId == null || shopId.equals(senderShopId)) return;
        String sender = t.getCustomerName() != null && !t.getCustomerName().isBlank()
                ? t.getCustomerName().trim() : "Customer";
        Map<String, Object> data = new HashMap<>();
        data.put("threadId", String.valueOf(t.getId()));
        data.put("messageId", String.valueOf(m.getId()));
        data.put("senderName", sender);

        List<String> lines = Arrays.stream(String.valueOf(m.getBody()).split("\n")).map(String::trim).toList();
        Matcher head = ORDER_HEAD.matcher(lines.get(0));
        if (!head.find()) {
            data.put("kind", "CHAT");
            String preview = buildPreview(body);
            if (preview.length() > 100) preview = preview.substring(0, 99) + "…";
            shopPush.pushToShop(shopId, "New message from " + sender, preview, data);
            return;
        }

        String orderNo = head.group(1);
        List<String> titles = lines.stream().filter(l -> l.startsWith("•"))
                .map(l -> l.replaceFirst("^•\\s*", "").replaceFirst("\\s×\\s.*$", "").trim())
                .filter(x -> !x.isEmpty()).toList();
        String buyer = lines.stream().filter(l -> l.startsWith("Buyer:"))
                .map(l -> l.substring("Buyer:".length()).trim()).filter(x -> !x.isEmpty())
                .findFirst().orElse(sender);
        String item = titles.isEmpty() ? "an item"
                : titles.get(0) + (titles.size() > 1 ? " +" + (titles.size() - 1) + " more" : "");
        String title = "New order placed";
        String text = buyer + " ordered " + item + (orderNo != null ? " (" + orderNo + ")" : "") + ".";
        data.put("kind", "BUY_ORDER");
        if (orderNo != null) data.put("orderNumber", orderNo);
        if (!titles.isEmpty()) data.put("productTitle", titles.get(0));
        shopPush.afterCommit(() -> {
            // One row + push per order number (a re-sent order message must not repeat it).
            UUID notificationId = shopNotifications.insertOnce(shopId, "buy", "BUY_ORDER_PLACED",
                    title, text, null, orderNo, null, null, orderNo == null ? null : "BUY_ORDER_PLACED:" + orderNo);
            if (notificationId == null) return;
            data.put("notificationId", notificationId.toString());
            shopPush.pushToShop(shopId, title, text, data);
        });
    }

    /** Mark messages from the other side as read; zero this side's unread. */
    @Transactional
    public void markRead(UUID threadId, Side reader) {
        CustomerChatThread t = requireThread(threadId);
        Instant now = Instant.now();
        if (reader == Side.CUSTOMER) {
            msgRepo.markRead(threadId, "SHOP", now);
            t.setCustomerLastReadAt(now);
            t.setUnreadCustomerCount(0);
        } else {
            msgRepo.markRead(threadId, "CUSTOMER", now);
            t.setShopLastReadAt(now);
            t.setUnreadShopCount(0);
        }
        threadRepo.save(t);
    }

    /** Set "typing until now+5s" for the caller's side. */
    @Transactional
    public void setTyping(UUID threadId, Side from, boolean typing) {
        CustomerChatThread t = requireThread(threadId);
        Instant until = typing ? Instant.now().plusSeconds(5) : null;
        if (from == Side.CUSTOMER) t.setCustomerTypingUntil(until);
        else t.setShopTypingUntil(until);
        threadRepo.save(t);
    }

    // -------------------------------------------------------------- response mapping

    public ChatThreadResponse toThreadResponse(CustomerChatThread t, Side viewer) {
        Instant now = Instant.now();
        ChatThreadResponse.ChatThreadResponseBuilder b = ChatThreadResponse.builder()
            .id(t.getId())
            .customerUserId(t.getCustomerUserId())
            .shopId(t.getShopId())
            .subject(t.getSubject())
            .lastMessagePreview(t.getLastMessagePreview())
            .lastMessageAt(t.getLastMessageAt());

        if (viewer == Side.CUSTOMER) {
            // Customer is looking at the shop on the other end.
            Instant shopSeen = snapshots.getShopLastSeen(t.getShopId());
            b.counterpartName(t.getShopName())
             .counterpartAvatarUrl(t.getShopImageUrl())
             .counterpartLastSeenAt(shopSeen)
             .counterpartOnline(within(shopSeen, now, ONLINE_WINDOW))
             .counterpartTyping(t.getShopTypingUntil() != null && t.getShopTypingUntil().isAfter(now))
             .unreadCount(nz(t.getUnreadCustomerCount()));
        } else {
            // Shop is looking at the customer.
            Instant custSeen = snapshots.getCustomerLastSeen(t.getCustomerUserId());
            b.counterpartName(t.getCustomerName())
             .counterpartAvatarUrl(t.getCustomerAvatarUrl())
             .counterpartPhone(t.getCustomerMobile())
             .counterpartLastSeenAt(custSeen)
             .counterpartOnline(within(custSeen, now, ONLINE_WINDOW))
             .counterpartTyping(t.getCustomerTypingUntil() != null && t.getCustomerTypingUntil().isAfter(now))
             .unreadCount(nz(t.getUnreadShopCount()));
        }
        return b.build();
    }

    public ChatMessageResponse toMessageResponse(CustomerChatMessage m) {
        return ChatMessageResponse.builder()
            .id(m.getId())
            .threadId(m.getThreadId())
            .sender(m.getSender())
            .body(m.getBody())
            .attachmentUrl(m.getAttachmentUrl())
            .attachmentType(m.getAttachmentType())
            .createdAt(m.getCreatedAt())
            .readAt(m.getReadAt())
            .read(m.getReadAt() != null)
            .build();
    }

    // -------------------------------------------------------------- helpers

    private static String buildPreview(ChatSendRequest body) {
        String text = body.getBody();
        if (text != null && !text.isBlank()) {
            return text.length() > 200 ? text.substring(0, 200) : text;
        }
        String at = body.getAttachmentType();
        if ("IMAGE".equalsIgnoreCase(at)) return "📷 Photo";
        if ("AUDIO".equalsIgnoreCase(at)) return "🎤 Voice message";
        if ("FILE".equalsIgnoreCase(at))  return "📎 Attachment";
        return "Attachment";
    }

    private static boolean within(Instant ts, Instant now, Duration window) {
        return ts != null && Duration.between(ts, now).compareTo(window) <= 0;
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
