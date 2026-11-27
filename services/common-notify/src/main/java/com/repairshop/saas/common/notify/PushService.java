package com.repairshop.saas.common.notify;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Push notifications to the three NearGFix apps — Partner (by shop), Customer
 * and Employee (by user) — over FCM (native Android tokens, Firebase Admin SDK)
 * or Expo (Expo push tokens), chosen per device from {@code push_device_tokens}.
 *
 * <h2>Never breaks the caller</h2>
 * A push is a side effect of something that already happened, so it must not
 * be able to undo it:
 * <ul>
 *   <li>Sending runs on a small executor of its own, never on the request thread.</li>
 *   <li>Inside a transaction it waits for the commit, so a rolled-back write
 *       never reaches anyone's phone.</li>
 *   <li>Every failure is caught and logged.</li>
 * </ul>
 * {@code PUSH_ENABLED=false} is a kill switch that keeps registration working.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    /** Set inside {@link #quiet()} — see there. */
    private static final ThreadLocal<Boolean> QUIET = new ThreadLocal<>();

    private final PushTokenRegistry registry;
    private final FcmSender fcm;
    private final ExpoSender expo;
    private final boolean enabled;
    private final ThreadPoolExecutor executor;

    public PushService(PushTokenRegistry registry, FcmSender fcm, ExpoSender expo,
                       @Value("${push.enabled:${PUSH_ENABLED:true}}") String enabled) {
        this.registry = registry;
        this.fcm = fcm;
        this.expo = expo;
        // Parsed by hand: a blank PUSH_ENABLED= in .env must not stop the service booting.
        this.enabled = !"false".equalsIgnoreCase(enabled == null ? "" : enabled.trim());
        // Registration keeps working while sending is off, so say so once at startup —
        // otherwise a disabled box looks healthy and every push silently goes nowhere.
        if (!this.enabled) log.warn("Push sending is OFF (PUSH_ENABLED=false): device tokens register, but no push is delivered");
        // Two senders and a bounded queue: a burst (a sell request fanned out to
        // every shop) queues up behind them instead of spawning threads, and a
        // backlog beyond that is dropped and logged rather than eating memory.
        AtomicInteger n = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r, "push-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (r, ex) -> log.warn("Push queue full — dropping a push task"));
        this.executor.allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    // ── Registration ────────────────────────────────────────────────────────

    public void register(PushApp app, String token, UUID userId, UUID shopId, String loginType, String platform) {
        registry.register(app, token, userId, shopId, loginType, platform);
    }

    public void unregister(String token) {
        registry.unregister(token);
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /** Customer or Employee app: the same push to each user's devices. */
    public void pushToUsers(PushApp app, Collection<UUID> userIds, String title, String body, Map<String, Object> data) {
        if (app == null || app == PushApp.PARTNER || userIds == null || userIds.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        ids.removeIf(java.util.Objects::isNull);
        Map<String, Object> d = data == null ? Map.of() : new HashMap<>(data);
        schedule(() -> deliver(registry.forUsers(app, ids), title, body, k -> d));
    }

    public void pushToUser(PushApp app, UUID userId, String title, String body, Map<String, Object> data) {
        if (userId == null) return;
        pushToUsers(app, List.of(userId), title, body, data);
    }

    /** Partner app: same title/body to several shops, each with its own data. */
    void pushToShops(Map<UUID, Map<String, Object>> dataByShop, String title, String body) {
        if (dataByShop == null || dataByShop.isEmpty()) return;
        // Snapshot now: the caller may reuse or mutate its maps after we return.
        Map<UUID, Map<String, Object>> snapshot = new LinkedHashMap<>();
        dataByShop.forEach((shop, d) -> {
            if (shop != null) snapshot.put(shop, d == null ? Map.of() : new HashMap<>(d));
        });
        schedule(() -> deliver(registry.forShops(snapshot.keySet()), title, body, snapshot::get));
    }

    private void schedule(Runnable send) {
        try {
            if (!enabled || Boolean.TRUE.equals(QUIET.get())) return;
            afterCommit(send);
        } catch (Exception e) {
            log.warn("Push not scheduled: {}", e.getMessage());
        }
    }

    private void deliver(Map<UUID, List<PushTokenRegistry.Device>> devices, String title, String body,
                         java.util.function.Function<UUID, Map<String, Object>> dataFor) {
        List<FcmSender.FcmMessage> viaFcm = new ArrayList<>();
        List<ExpoSender.ExpoMessage> viaExpo = new ArrayList<>();
        devices.forEach((key, list) -> {
            Map<String, Object> data = dataFor.apply(key);
            for (PushTokenRegistry.Device d : list) {
                if (d.provider() == PushTokenRegistry.Provider.FCM) {
                    viaFcm.add(new FcmSender.FcmMessage(d.token(), title, body, data));
                } else {
                    viaExpo.add(new ExpoSender.ExpoMessage(d.token(), title, body, data));
                }
            }
        });
        List<String> dead = new ArrayList<>();
        if (!viaFcm.isEmpty()) dead.addAll(fcm.send(viaFcm));
        if (!viaExpo.isEmpty()) dead.addAll(expo.send(viaExpo));
        for (String token : dead) registry.unregister(token);
        if (!dead.isEmpty()) log.info("Removed {} unregistered push token(s)", dead.size());
    }

    /**
     * Runs {@code task} on the push executor once the caller's transaction
     * commits — straight away when there is none — and drops it if the
     * transaction rolls back. Errors are logged, never thrown.
     *
     * <p>Also for notification work that must not share the caller's
     * transaction: a failed statement aborts a Postgres transaction, so writing
     * a best-effort row inside it could take the real write down with it.
     */
    public void afterCommit(Runnable task) {
        Runnable submit = () -> {
            try {
                executor.execute(() -> {
                    try { task.run(); }
                    catch (Exception e) { log.warn("Push task failed: {}", e.getMessage(), e); }
                });
            } catch (Exception e) {
                log.warn("Push task not queued: {}", e.getMessage());
            }
        };
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { submit.run(); }
                });
            } else {
                submit.run();
            }
        } catch (Exception e) {
            log.warn("Push task not scheduled: {}", e.getMessage());
        }
    }

    /**
     * Pushes raised on this thread inside the returned scope are dropped; the
     * callers' notification rows are still written. For read-time self-heal
     * that back-fills timeline steps which happened long ago — a phone buzzing
     * for them the next time someone opens the job would be late and wrong.
     * Use with try-with-resources.
     */
    public Quiet quiet() {
        Boolean previous = QUIET.get();
        QUIET.set(Boolean.TRUE);
        return () -> {
            if (previous == null) QUIET.remove();
            else QUIET.set(previous);
        };
    }

    /** {@link AutoCloseable} without the checked exception. */
    public interface Quiet extends AutoCloseable {
        @Override void close();
    }
}
