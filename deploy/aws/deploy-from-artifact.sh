#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/repair-shop-saas}"
APP_USER="${APP_USER:-repairshop}"
# All 12 services. NOTE: each Spring Boot service needs ~300 MB RAM, so the full set
# requires a ~4 GB instance (t3.medium). On a 1 GB t3.micro only ~3 fit before the
# kernel OOM-kills them - upsize the instance before enabling the whole list, or trim
# SERVICES in /opt/repair-shop-saas/.env to the subset the instance can hold.
SERVICES_DEFAULT="auth-service ticket-service user-service shop-service technician-service inventory-service marketplace-service pickup-service notification-service subscription-service master-data-service order-service"

# Production database lives on AWS RDS and is managed outside this deployment.
# We connect to it via JDBC only - we never provision or migrate it from here.
#
# The password is deliberately NOT defaulted here: this repository is public, so a
# literal would publish the production credential. It comes from the DB_PASSWORD
# environment variable (the GitHub Actions secret of the same name), or is carried
# over from the .env written by a previous deploy.
RDS_DB_HOST="${DB_HOST:-ggfixservice.cdaiqaog82ho.ap-south-1.rds.amazonaws.com}"
RDS_DB_PORT="${DB_PORT:-5432}"
RDS_DB_NAME="${DB_NAME:-ggfixservice}"
RDS_DB_USER="${DB_USER:-postgres}"
RDS_DB_PASSWORD="${DB_PASSWORD:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PAYLOAD_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1. Run deploy/aws/install-ec2.sh on the EC2 instance first." >&2
    exit 1
  fi
}

random_secret() {
  openssl rand -base64 48 | tr -d '\n'
}

# Pull a single value out of the existing .env without sourcing the whole file.
read_env_value() {
  local key="$1"
  if [[ -f "$APP_DIR/.env" ]]; then
    sudo sed -n "s/^${key}=//p" "$APP_DIR/.env" | head -n1
  fi
}

# Always (re)write the env file so the DB connection points at RDS, while
# preserving any secrets that were generated/set on a previous deploy.
ensure_env_file() {
  local jwt_secret cloud_name cloud_key cloud_secret cloud_folder google_vision_key

  jwt_secret="$(read_env_value JWT_SECRET)"
  if [[ -z "$jwt_secret" ]]; then
    jwt_secret="$(random_secret)"
  fi

  # Fall back to the value a previous deploy already wrote, so an operator who
  # set it once by hand on the box does not have to re-supply it every time.
  if [[ -z "$RDS_DB_PASSWORD" ]]; then
    RDS_DB_PASSWORD="$(read_env_value DB_PASSWORD)"
  fi

  if [[ -z "$RDS_DB_PASSWORD" ]]; then
    echo "ERROR: DB_PASSWORD is not set and $APP_DIR/.env has no previous value." >&2
    echo "Set the DB_PASSWORD repository secret, or write it into $APP_DIR/.env on the host." >&2
    exit 1
  fi

  cloud_name="$(read_env_value CLOUDINARY_CLOUD_NAME)"
  cloud_key="$(read_env_value CLOUDINARY_API_KEY)"
  cloud_secret="$(read_env_value CLOUDINARY_API_SECRET)"
  cloud_folder="$(read_env_value CLOUDINARY_FOLDER)"
  cloud_folder="${cloud_folder:-ggfix/master}"

  # S3 media (cdn.neargfix.com). Read back from the existing .env first so a value set
  # on the box survives — this heredoc REWRITES .env on every deploy, so anything not
  # carried through here is silently lost and uploads start failing again.
  # Credentials are deliberately absent: the SDK resolves them from the EC2 instance
  # role, which needs s3:PutObject/s3:DeleteObject on the bucket.
  aws_region="$(read_env_value AWS_REGION)"
  aws_region="${aws_region:-ap-south-1}"
  aws_bucket="$(read_env_value AWS_S3_BUCKET)"
  aws_bucket="${aws_bucket:-neargfix-platform-media-files}"
  aws_base_url="$(read_env_value AWS_S3_BASE_URL)"
  aws_base_url="${aws_base_url:-https://cdn.neargfix.com}"

  # Google Cloud Vision (master-data /master/device-identify). From the
  # GOOGLE_VISION_API_KEY secret when the workflow passes one, else the value
  # already on the box — this heredoc rewrites .env, so it must be carried.
  google_vision_key="${GOOGLE_VISION_API_KEY:-}"
  if [[ -z "$google_vision_key" ]]; then
    google_vision_key="$(read_env_value GOOGLE_VISION_API_KEY)"
  fi

  # Google Places server key (auth-service /auth/places/*). Same rule.
  google_places_key="${GOOGLE_PLACES_SERVER_API_KEY:-}"
  if [[ -z "$google_places_key" ]]; then
    google_places_key="$(read_env_value GOOGLE_PLACES_SERVER_API_KEY)"
  fi

  # Razorpay (subscription-service UPI checkout). Same rule: the workflow
  # secret when passed, else the value already on the box. Empty keys are fine
  # — the service then answers "Payments are not configured".
  rzp_key_id="${RAZORPAY_KEY_ID:-}"
  if [[ -z "$rzp_key_id" ]]; then
    rzp_key_id="$(read_env_value RAZORPAY_KEY_ID)"
  fi
  rzp_key_secret="${RAZORPAY_KEY_SECRET:-}"
  if [[ -z "$rzp_key_secret" ]]; then
    rzp_key_secret="$(read_env_value RAZORPAY_KEY_SECRET)"
  fi
  rzp_webhook_secret="${RAZORPAY_WEBHOOK_SECRET:-}"
  if [[ -z "$rzp_webhook_secret" ]]; then
    rzp_webhook_secret="$(read_env_value RAZORPAY_WEBHOOK_SECRET)"
  fi
  # TEST | LIVE; must match the key prefix (rzp_test_ / rzp_live_) or payments stay off.
  rzp_mode="${RAZORPAY_MODE:-}"
  if [[ -z "$rzp_mode" ]]; then
    rzp_mode="$(read_env_value RAZORPAY_MODE)"
  fi
  rzp_mode="${rzp_mode:-TEST}"

  # Partner app push (common-notify, via Expo). Both optional and carried the
  # same way: the workflow's value when it passes one, else what is already on
  # the box. An empty EXPO_ACCESS_TOKEN still sends (Expo only requires it when
  # enhanced push security is on); PUSH_ENABLED=false is the kill switch.
  expo_access_token="${EXPO_ACCESS_TOKEN:-}"
  if [[ -z "$expo_access_token" ]]; then
    expo_access_token="$(read_env_value EXPO_ACCESS_TOKEN)"
  fi
  push_enabled="${PUSH_ENABLED:-}"
  if [[ -z "$push_enabled" ]]; then
    push_enabled="$(read_env_value PUSH_ENABLED)"
  fi
  push_enabled="${push_enabled:-false}"
  # A false already on the box is a kill switch the workflow cannot undo.
  [[ "$(read_env_value PUSH_ENABLED)" == "false" ]] && push_enabled=false

  # FCM (Firebase Admin SDK, common-notify). The service-account key never
  # touches this box's disk or .env: the services read it from AWS Secrets
  # Manager with the EC2 instance role. Only the secret's NAME is carried here.
  # Blank secret id = FCM sends are skipped (Expo tokens still work).
  firebase_secret_id="${FIREBASE_CREDENTIALS_SECRET_ID:-}"
  if [[ -z "$firebase_secret_id" ]]; then
    firebase_secret_id="$(read_env_value FIREBASE_CREDENTIALS_SECRET_ID)"
  fi
  firebase_secret_region="${FIREBASE_CREDENTIALS_SECRET_REGION:-}"
  if [[ -z "$firebase_secret_region" ]]; then
    firebase_secret_region="$(read_env_value FIREBASE_CREDENTIALS_SECRET_REGION)"
  fi
  firebase_secret_region="${firebase_secret_region:-${aws_region:-ap-south-1}}"
  fcm_enabled="${FCM_ENABLED:-}"
  if [[ -z "$fcm_enabled" ]]; then
    fcm_enabled="$(read_env_value FCM_ENABLED)"
  fi
  fcm_enabled="${fcm_enabled:-false}"
  [[ "$(read_env_value FCM_ENABLED)" == "false" ]] && fcm_enabled=false

  # BotBee WhatsApp booking confirmations (ticket-service). Same rule: the
  # workflow's value when passed, else what is already on the box. Sending
  # stays off unless BOTBEE_WHATSAPP_ENABLED=true (after Meta template approval).
  botbee_api_key="${BOTBEE_API_KEY:-}"
  if [[ -z "$botbee_api_key" ]]; then
    botbee_api_key="$(read_env_value BOTBEE_API_KEY)"
  fi
  botbee_base_url="${BOTBEE_BASE_URL:-}"
  if [[ -z "$botbee_base_url" ]]; then
    botbee_base_url="$(read_env_value BOTBEE_BASE_URL)"
  fi
  botbee_base_url="${botbee_base_url:-https://nexa.botbee.io}"
  botbee_channel_id="${BOTBEE_CHANNEL_ID:-}"
  if [[ -z "$botbee_channel_id" ]]; then
    botbee_channel_id="$(read_env_value BOTBEE_CHANNEL_ID)"
  fi
  botbee_enabled="${BOTBEE_WHATSAPP_ENABLED:-}"
  if [[ -z "$botbee_enabled" ]]; then
    botbee_enabled="$(read_env_value BOTBEE_WHATSAPP_ENABLED)"
  fi
  botbee_enabled="${botbee_enabled:-false}"
  [[ "$(read_env_value BOTBEE_WHATSAPP_ENABLED)" == "false" ]] && botbee_enabled=false

  # Auth security settings: the workflow's value when it passes one, else the
  # value already on the host (so a manual edit survives redeploys).
  # OTP_DEV_MODE defaults to false; OTP_TEST_NUMBERS empty = test OTP disabled.
  otp_dev_mode="${OTP_DEV_MODE:-$(read_env_value OTP_DEV_MODE)}"; otp_dev_mode="${otp_dev_mode:-false}"
  otp_test_numbers="${OTP_TEST_NUMBERS:-$(read_env_value OTP_TEST_NUMBERS)}"
  device_limit_test_accounts="${DEVICE_LIMIT_TEST_ACCOUNTS:-$(read_env_value DEVICE_LIMIT_TEST_ACCOUNTS)}"
  otp_fixed_test_code="${OTP_FIXED_TEST_CODE_ENABLED:-$(read_env_value OTP_FIXED_TEST_CODE_ENABLED)}"; otp_fixed_test_code="${otp_fixed_test_code:-false}"
  pin_pepper="${PIN_PEPPER:-$(read_env_value PIN_PEPPER)}"
  tpin_enforced="${TPIN_ENFORCED:-$(read_env_value TPIN_ENFORCED)}"; tpin_enforced="${tpin_enforced:-false}"
  tpin_enforced_actions="${TPIN_ENFORCED_ACTIONS:-$(read_env_value TPIN_ENFORCED_ACTIONS)}"
  require_device_session="${REQUIRE_DEVICE_SESSION:-$(read_env_value REQUIRE_DEVICE_SESSION)}"; require_device_session="${require_device_session:-false}"

  sudo tee "$APP_DIR/.env" >/dev/null <<EOF
DB_HOST=$RDS_DB_HOST
DB_PORT=$RDS_DB_PORT
DB_NAME=$RDS_DB_NAME
DB_USER=$RDS_DB_USER
DB_PASSWORD=$RDS_DB_PASSWORD
JWT_SECRET=$jwt_secret
JWT_EXPIRY_MS=86400000
CLOUDINARY_CLOUD_NAME=$cloud_name
CLOUDINARY_API_KEY=$cloud_key
CLOUDINARY_API_SECRET=$cloud_secret
CLOUDINARY_FOLDER=$cloud_folder
AWS_REGION=$aws_region
AWS_S3_BUCKET=$aws_bucket
AWS_S3_BASE_URL=$aws_base_url
GOOGLE_VISION_API_KEY=$google_vision_key
GOOGLE_PLACES_SERVER_API_KEY=$google_places_key
RAZORPAY_KEY_ID=$rzp_key_id
RAZORPAY_KEY_SECRET=$rzp_key_secret
RAZORPAY_WEBHOOK_SECRET=$rzp_webhook_secret
RAZORPAY_MODE=$rzp_mode

EXPO_ACCESS_TOKEN=$expo_access_token
PUSH_ENABLED=$push_enabled
FIREBASE_CREDENTIALS_SECRET_ID=$firebase_secret_id
FIREBASE_CREDENTIALS_SECRET_REGION=$firebase_secret_region
FCM_ENABLED=$fcm_enabled
BOTBEE_API_KEY=$botbee_api_key
BOTBEE_BASE_URL=$botbee_base_url
BOTBEE_CHANNEL_ID=$botbee_channel_id
BOTBEE_WHATSAPP_ENABLED=$botbee_enabled
OTP_DEV_MODE=$otp_dev_mode
OTP_TEST_NUMBERS=$otp_test_numbers
DEVICE_LIMIT_TEST_ACCOUNTS=$device_limit_test_accounts
OTP_FIXED_TEST_CODE_ENABLED=$otp_fixed_test_code
PIN_PEPPER=$pin_pepper
TPIN_ENFORCED=$tpin_enforced
TPIN_ENFORCED_ACTIONS=$tpin_enforced_actions
REQUIRE_DEVICE_SESSION=$require_device_session
JAVA_OPTS="-Xms64m -Xmx160m"
SERVICES="$SERVICES_DEFAULT"
EOF
}

prepare_layout() {
  if ! id "$APP_USER" >/dev/null 2>&1; then
    sudo useradd --system --home-dir "$APP_DIR" --shell /sbin/nologin "$APP_USER"
  fi

  sudo mkdir -p "$APP_DIR/bin" "$APP_DIR/services"
  sudo install -m 0755 "$SCRIPT_DIR/run-service.sh" "$APP_DIR/bin/run-service"
  sudo install -m 0644 "$SCRIPT_DIR/repair-shop-saas@.service" /etc/systemd/system/repair-shop-saas@.service
}

load_env() {
  # .env is written by sudo tee (root-owned) and holds secrets, so it is not
  # world-readable. Read it with sudo - like read_env_value above - instead of
  # sourcing it directly, which fails with "Permission denied" for the
  # unprivileged deploy user before copy_jars chowns it to $APP_USER.
  set -a
  # shellcheck disable=SC1091
  source <(sudo cat "$APP_DIR/.env")
  set +a
  SERVICES="${SERVICES:-$SERVICES_DEFAULT}"
}

copy_jars() {
  for service in $SERVICES; do
    source_jar="$PAYLOAD_DIR/services/$service/$service.jar"
    if [[ ! -f "$source_jar" ]]; then
      echo "Deployment bundle is missing $source_jar" >&2
      exit 1
    fi

    sudo mkdir -p "$APP_DIR/services/$service"
    sudo install -m 0644 "$source_jar" "$APP_DIR/services/$service/$service.jar"
  done

  sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR"
}

restart_services() {
  sudo systemctl daemon-reload

  for service in $SERVICES; do
    sudo systemctl enable "repair-shop-saas@$service"
    sudo systemctl restart "repair-shop-saas@$service"
  done

  for service in $SERVICES; do
    for _ in $(seq 1 30); do
      if sudo systemctl is-active --quiet "repair-shop-saas@$service"; then
        echo "$service is active"
        break
      fi

      if sudo systemctl is-failed --quiet "repair-shop-saas@$service"; then
        sudo systemctl --no-pager --full status "repair-shop-saas@$service" || true
        exit 1
      fi

      sleep 2
    done
  done
}

require_command java
require_command openssl

prepare_layout
ensure_env_file
load_env
# Schema first: new jars validate against it (ddl-auto=validate). A failed
# migration exits here, before any jar is replaced or service restarted.
bash "$SCRIPT_DIR/run-migrations.sh" "$PAYLOAD_DIR/migrations"
copy_jars
restart_services

echo "Deployment complete. Services connect to RDS at $RDS_DB_HOST:$RDS_DB_PORT/$RDS_DB_NAME."
