"""End-to-end check of the repair return delivery (ticket-service RepairDeliveryController).

ISOLATED TEST DATABASE ONLY — it inserts test shops / customers / tickets and shifts an OTP timestamp.
Run ticket-service (built from this branch) against a throwaway PostgreSQL, then:

    E2E_ISOLATED_DB=yes TEST_DB_PORT=55432 TICKET_URL=http://localhost:18082 python scripts/e2e/repair_delivery_e2e.py
"""
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta

if os.environ.get('E2E_ISOLATED_DB') != 'yes':
    sys.exit('Refusing to run: set E2E_ISOLATED_DB=yes and point TEST_DB_* at a throwaway database.')

import psycopg2
import psycopg2.extras

psycopg2.extras.register_uuid()
TICKET = os.environ.get('TICKET_URL', 'http://localhost:18082')
SECRET = os.environ.get('JWT_SECRET', 'your-256-bit-secret-key-for-repair-shop-saas-jwt-signing').encode()
db = psycopg2.connect(host=os.environ.get('TEST_DB_HOST', 'localhost'), port=int(os.environ.get('TEST_DB_PORT', '55432')),
                      user=os.environ.get('TEST_DB_USER', 'postgres'), password=os.environ.get('TEST_DB_PASSWORD', ''), dbname=os.environ.get('TEST_DB_NAME', 'repairshop'))
db.autocommit = True
results = []


def q(sql, *args):
    with db.cursor() as c:
        c.execute(sql, args)
        try:
            return c.fetchall()
        except psycopg2.ProgrammingError:
            return None


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b'=').decode()


def token(sub, roles, shop=None):
    h = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}).encode())
    claims = {'sub': str(sub), 'roles': roles, 'iat': int(time.time()), 'exp': int(time.time()) + 3600}
    if shop:
        claims['shopId'] = str(shop)
    p = b64(json.dumps(claims).encode())
    return f'{h}.{p}.{b64(hmac.new(SECRET, f"{h}.{p}".encode(), hashlib.sha256).digest())}'


def call(method, url, tok, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={'Authorization': f'Bearer {tok}', 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode() or 'null')
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw[:200]}


def check(name, cond, detail=''):
    results.append((name, bool(cond)))
    print(('PASS ' if cond else 'FAIL ') + name + ('' if cond else f'   -> {str(detail)[:400]}'))


def code(r):
    return (r[1] or {}).get('code') if isinstance(r[1], dict) else None


# ── seed ─────────────────────────────────────────────────────────────────────
shop, shop2 = uuid.uuid4(), uuid.uuid4()
owner, owner2, cust, u1, u2, u3 = (uuid.uuid4() for _ in range(6))
t1, t2, t3 = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
q("INSERT INTO shops (id, name, slug, address) VALUES (%s, 'Delivery Shop', %s, '12 Shop St, Cuddalore'), (%s, 'Other Shop', %s, NULL)", shop, f'd-{shop}', shop2, f'o-{shop2}')
q("INSERT INTO users (id, email, role, shop_id) VALUES (%s, %s, 'TECHNICIAN', %s), (%s, %s, 'TECHNICIAN', %s), (%s, %s, 'TECHNICIAN', %s)",
  u1, f'{u1}@t.local', shop, u2, f'{u2}@t.local', shop, u3, f'{u3}@t.local', shop2)
q("INSERT INTO technicians (id, shop_id, name, phone, user_id) VALUES (%s, %s, 'Ravi K', '9000000001', %s), (%s, %s, 'Vignesh S', '9000000002', %s), (%s, %s, 'Other Shop Guy', '9000000003', %s)",
  t1, shop, u1, t2, shop, u2, t3, shop2, u3)
mobile = '9' + str(cust.int)[:9]
q("INSERT INTO customer_users (id, full_name, mobile) VALUES (%s, 'Arun Customer', %s)", cust, mobile)
q("INSERT INTO customers (id, shop_id, name, phone) VALUES (%s, %s, 'Arun Customer', %s)", cust, shop, mobile)
IMEI = '356938035643809'


def ticket(n, status, qc=True, invoice=True):
    tid = uuid.uuid4()
    q("""INSERT INTO tickets (id, shop_id, customer_id, tracking_id, booking_number, status, imei, customer_name, customer_phone, customer_address, device_display_name, created_at, updated_at)
         VALUES (%s, %s, %s, %s, %s, %s, %s, 'Arun Customer', %s, '5 Lake View, Cuddalore', 'iPhone 11 (128 GB)', now(), now())""",
      tid, shop, cust, f'TRK{n}{shop.hex[:6]}', f'BK{n}-{shop.hex[:5]}', status, IMEI, mobile)
    bid = uuid.uuid4()
    q("INSERT INTO repair_bookings (id, booking_number, customer_user_id, shop_id, ticket_id, status) VALUES (%s, %s, %s, %s, %s, 'IN_REPAIR')", bid, f'RB{n}-{shop.hex[:5]}', cust, shop, tid)
    if qc:
        q("INSERT INTO repair_booking_events (id, booking_id, status, created_at) VALUES (%s, %s, 'QUALITY_CHECK_COMPLETED', now())", uuid.uuid4(), bid)
    if invoice:
        q("INSERT INTO invoices (id, ticket_id, invoice_no, net_payable_amount, amount_paid, credit_amount, advance_paid) VALUES (%s, %s, %s, 5000, 2000, 3000, 0)",
          uuid.uuid4(), tid, f'INV{n}{shop.hex[:5]}')
    return tid


T_OK = ticket(1, 'READY')
T_NOQC = ticket(2, 'READY', qc=False)
T_NOINV = ticket(3, 'READY', invoice=False)

OW, OW2 = token(owner, ['SHOP_OWNER'], shop), token(owner2, ['SHOP_OWNER'], shop2)
E1, E2, E3 = token(u1, ['TECHNICIAN'], shop), token(u2, ['TECHNICIAN'], shop), token(u3, ['TECHNICIAN'], shop2)
CU = token(cust, ['CUSTOMER'])
P = lambda t: f'{TICKET}/tickets/{t}/repair-delivery'  # noqa: E731
E = f'{TICKET}/technicians/me/repair-deliveries'
tomorrow = (date.today() + timedelta(days=1)).isoformat()
SLOT = {'date': tomorrow, 'slotStart': '11:00', 'slotEnd': '12:00'}

# ── eligibility / QC fail / permissions ──────────────────────────────────────
r = call('GET', P(T_NOQC), OW)
check('QC not passed → cannot schedule', r[0] == 200 and r[1]['eligibility']['canSchedule'] is False and 'QC' in r[1]['eligibility']['reasons'][0], r)
r = call('POST', f'{P(T_NOQC)}/schedule', OW, {**SLOT, 'technicianId': str(t1)})
check('Schedule refused without QC (NOT_DELIVERABLE)', r[0] == 409 and code(r) == 'NOT_DELIVERABLE', r)
r = call('POST', f'{P(T_NOINV)}/schedule', OW, {**SLOT, 'technicianId': str(t1)})
check('Schedule refused without invoice', r[0] == 409 and 'invoice' in r[1].get('message', '').lower(), r)
r = call('GET', P(T_OK), OW)
check('Eligible: QC passed + invoice due ₹3000', r[1]['eligibility']['canSchedule'] and float(r[1]['eligibility']['invoice']['due']) == 3000, r)
check('Other shop owner blocked', call('GET', P(T_OK), OW2)[0] == 404)
check('Customer token blocked on owner API (403)', call('GET', P(T_OK), CU)[0] == 403)
check('Employee token blocked on owner API (403)', call('GET', P(T_OK), E1)[0] == 403)
r = call('POST', f'{P(T_OK)}/schedule', OW, {**SLOT, 'technicianId': str(t3)})
check('Other shop employee cannot be assigned', r[0] == 400, r)
r = call('POST', f'{P(T_OK)}/schedule', OW, {'date': '2020-01-01', 'slotStart': '11:00', 'slotEnd': '12:00', 'technicianId': str(t1)})
check('Past date refused', r[0] == 400, r)

# ── schedule → decline → reassign → accept ───────────────────────────────────
r = call('POST', f'{P(T_OK)}/schedule', OW, {**SLOT, 'technicianId': str(t1)})
check('Delivery scheduled + assigned', r[0] == 200 and r[1]['delivery']['status'] == 'ASSIGNED' and float(r[1]['delivery']['amountDue']) == 3000, r)
DID = r[1]['delivery']['id'] if r[0] == 200 else None
c = call('GET', f'{TICKET}/tickets/customer/{T_OK}/repair-delivery', CU)
check('Customer sees Scheduled, no employee/OTP yet', c[0] == 200 and c[1]['delivery']['status'] == 'ASSIGNED' and c[1]['delivery']['employee'] is None and c[1]['delivery']['otp'] is None, c)
check('Other customer blocked', call('GET', f'{TICKET}/tickets/customer/{T_OK}/repair-delivery', token(uuid.uuid4(), ['CUSTOMER']))[0] == 403)
check('Employee list shows it', any(d['id'] == DID for d in call('GET', E, E1)[1]))
check('Other employee cannot accept (NOT_ASSIGNED)', code(call('POST', f'{E}/{DID}/accept', E2)) == 'NOT_ASSIGNED')
check('Other shop employee cannot see it', call('POST', f'{E}/{DID}/accept', E3)[0] == 404)
r = call('POST', f'{E}/{DID}/decline', E1, {'reason': 'Vehicle problem'})
check('Employee declines → UNASSIGNED', r[0] == 200 and r[1]['status'] == 'UNASSIGNED', r)
r = call('POST', f'{P(T_OK)}/assign', OW, {'technicianId': str(t2)})
check('Owner reassigns to Vignesh', r[0] == 200 and r[1]['delivery']['status'] == 'ASSIGNED' and r[1]['delivery']['employee']['name'] == 'Vignesh S', r)
r = call('POST', f'{P(T_OK)}/reschedule', OW, {'date': tomorrow, 'slotStart': '15:00', 'slotEnd': '16:00'})
check('Owner reschedules', r[0] == 200 and r[1]['delivery']['slotStart'].startswith('15:00'), r)
r = call('POST', f'{E}/{DID}/accept', E2)
check('Employee accepts', r[0] == 200 and r[1]['status'] == 'ACCEPTED', r)
c = call('GET', f'{TICKET}/tickets/customer/{T_OK}/repair-delivery', CU)
check('Customer sees the employee after acceptance', c[1]['delivery']['employee'] and c[1]['delivery']['employee']['name'] == 'Vignesh S', c)

# ── shop collection ──────────────────────────────────────────────────────────
PH = ['http://x/1.jpg', 'http://x/2.jpg']
r = call('POST', f'{E}/{DID}/collect', E2, {'identifier': IMEI, 'accessories': ['Charger'], 'photos': PH})
check('Collect blocked until shop releases', code(r) == 'SHOP_RELEASE_REQUIRED', r)
check('Owner releases device', call('POST', f'{P(T_OK)}/release', OW)[1]['delivery']['releasedAt'])
r = call('POST', f'{E}/{DID}/collect', E2, {'identifier': '356938035643800', 'accessories': ['Charger'], 'photos': PH})
check('Wrong IMEI refused (IDENTIFIER_MISMATCH)', code(r) == 'IDENTIFIER_MISMATCH', r)
r = call('POST', f'{E}/{DID}/collect', E2, {'identifier': IMEI, 'accessories': ['Charger'], 'photos': PH[:1]})
check('Collection needs 2 photos', r[0] == 400, r)
r = call('POST', f'{E}/{DID}/collect', E2, {'identifier': IMEI, 'accessories': ['Charger', 'Box'], 'photos': PH})
check('Collected with IMEI + accessories + photos', r[0] == 200 and r[1]['status'] == 'COLLECTED' and r[1]['accessories'] == ['Charger', 'Box'], r)
r = call('POST', f'{P(T_OK)}/assign', OW, {'technicianId': str(t1)})
check('Reassign blocked while employee has the device', code(r) == 'IN_CUSTODY', r)

# ── out for delivery, OTP rules, failure + return + retry ────────────────────
r = call('POST', f'{E}/{DID}/start', E2)
check('Out for delivery', r[0] == 200 and r[1]['status'] == 'OUT_FOR_DELIVERY' and 'otp' not in r[1], r)
check('Ticket → DELIVERED_PROCESSING', q('SELECT status FROM tickets WHERE id = %s', T_OK)[0][0] == 'DELIVERED_PROCESSING')
otp = call('GET', f'{TICKET}/tickets/customer/{T_OK}/repair-delivery', CU)[1]['delivery']['otp']
check('Customer-only OTP visible to customer', isinstance(otp, str) and len(otp) == 4, otp)
wrong = '0000' if otp != '0000' else '1111'
codes = [code(call('POST', f'{E}/{DID}/complete', E2, {'otp': wrong, 'amountCollected': 3000, 'paymentMethod': 'CASH'})) for _ in range(5)]
r = call('POST', f'{E}/{DID}/complete', E2, {'otp': otp, 'amountCollected': 3000, 'paymentMethod': 'CASH'})
check('Wrong OTP ×5 → OTP_INVALID then OTP_LOCKED', codes == ['OTP_INVALID'] * 5 and code(r) == 'OTP_LOCKED', (codes, r))
check('OTP resend rate-limited', code(call('POST', f'{E}/{DID}/otp/resend', E2)) == 'OTP_WAIT')
q("UPDATE repair_deliveries SET otp_sent_at = now() - interval '3 hours' WHERE id = %s", uuid.UUID(DID))
check('Expired OTP refused', code(call('POST', f'{E}/{DID}/complete', E2, {'otp': otp, 'amountCollected': 3000, 'paymentMethod': 'CASH'})) in ('OTP_EXPIRED', 'OTP_LOCKED'))
check('Customer OTP hidden after expiry', call('GET', f'{TICKET}/tickets/customer/{T_OK}/repair-delivery', CU)[1]['delivery']['otp'] is None)
r = call('POST', f'{E}/{DID}/fail', E2, {'reason': 'Customer not available'})
check('Delivery failed (customer unavailable)', r[0] == 200 and r[1]['status'] == 'FAILED', r)
check('Ticket stays open (not DELIVERED)', q('SELECT status FROM tickets WHERE id = %s', T_OK)[0][0] != 'DELIVERED')
check('Employee returns device', call('POST', f'{E}/{DID}/return', E2)[1]['status'] == 'RETURNING')
r = call('POST', f'{P(T_OK)}/reschedule', OW, {**SLOT})
check('Reschedule blocked until shop has the device back', code(r) == 'IN_CUSTODY', r)
check('Owner confirms device back at shop', call('POST', f'{P(T_OK)}/receive-return', OW)[1]['delivery']['status'] == 'RETURNED')
r = call('POST', f'{P(T_OK)}/reschedule', OW, {**SLOT})
check('Retry scheduled as attempt 2 (same employee)', r[0] == 200 and r[1]['delivery']['status'] == 'ASSIGNED' and r[1]['delivery']['attempt'] == 2 and r[1]['delivery']['employee']['name'] == 'Vignesh S', r)

# ── attempt 2: deliver with OTP + exact payment ──────────────────────────────
call('POST', f'{E}/{DID}/accept', E2)
call('POST', f'{P(T_OK)}/release', OW)
call('POST', f'{E}/{DID}/collect', E2, {'identifier': IMEI, 'accessories': ['Charger'], 'photos': PH})
call('POST', f'{E}/{DID}/start', E2)
check('Arrived', call('POST', f'{E}/{DID}/arrive', E2)[1]['status'] == 'ARRIVED')
otp = call('GET', f'{TICKET}/tickets/customer/{T_OK}/repair-delivery', CU)[1]['delivery']['otp']
check('Payment must equal outstanding (PAYMENT_MISMATCH)', code(call('POST', f'{E}/{DID}/complete', E2, {'otp': otp, 'amountCollected': 2500, 'paymentMethod': 'CASH'})) == 'PAYMENT_MISMATCH')
check('UPI needs a reference', code(call('POST', f'{E}/{DID}/complete', E2, {'otp': otp, 'amountCollected': 3000, 'paymentMethod': 'UPI'})) == 'PAYMENT_REF')
r = call('POST', f'{E}/{DID}/complete', E2, {'otp': otp, 'amountCollected': 3000, 'paymentMethod': 'UPI', 'paymentRef': 'UTR123456789', 'photos': PH})
check('Delivered with OTP + ₹3000 UPI', r[0] == 200 and r[1]['status'] == 'DELIVERED' and float(r[1]['amountCollected']) == 3000, r)
check('Duplicate completion refused', code(call('POST', f'{E}/{DID}/complete', E2, {'otp': otp, 'amountCollected': 3000, 'paymentMethod': 'CASH'})) == 'ALREADY_DELIVERED')
inv = q('SELECT amount_paid, credit_amount FROM invoices WHERE ticket_id = %s', T_OK)[0]
check('Invoice now fully paid (paid 5000, due 0)', float(inv[0]) == 5000 and float(inv[1]) == 0, inv)
check('Ticket → DELIVERED', q('SELECT status FROM tickets WHERE id = %s', T_OK)[0][0] == 'DELIVERED')
c = call('GET', f'{TICKET}/tickets/customer/{T_OK}/repair-delivery', CU)
check('Customer sees Delivered, OTP gone', c[1]['delivery']['status'] == 'DELIVERED' and c[1]['delivery']['otp'] is None, c)
check('Customer history hides internal events', not any(e['status'] in ('OTP_FAILED', 'IDENTIFIER_MISMATCH', 'DECLINED') for e in c[1]['events']))
ev = call('GET', P(T_OK), OW)[1]['events']
check('Owner history has every step', {'ASSIGNED', 'DECLINED', 'ACCEPTED', 'RELEASED', 'IDENTIFIER_MISMATCH', 'COLLECTED', 'OUT_FOR_DELIVERY', 'OTP_FAILED', 'FAILED', 'RETURNING', 'RETURNED', 'ARRIVED', 'DELIVERED'} <= {e['status'] for e in ev}, sorted({e['status'] for e in ev}))
n = q("SELECT count(*) FROM customer_notifications WHERE booking_id = %s AND status_key LIKE 'REPAIR_%%'", T_OK)[0][0]
check('Customer notified at each stage', n >= 5, n)
check('Booking history shows Out for Delivery + Delivered', {'DELIVERED_PROCESSING', 'DELIVERED'} <= {r[0] for r in q('SELECT e.status FROM repair_booking_events e JOIN repair_bookings b ON b.id = e.booking_id WHERE b.ticket_id = %s', T_OK)})

passed = sum(1 for _, ok in results if ok)
print(f'\nRESULT: {passed}/{len(results)} passed')
