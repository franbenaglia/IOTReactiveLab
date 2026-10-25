#!/bin/sh
# Genera el archivo de contraseñas desde variables de entorno y arranca
# Mosquitto. Así ninguna contraseña (ni su hash) vive en el repositorio.
set -eu
umask 077   # passwd y acl no legibles por otros desde el primer byte

PASSWD=/mosquitto/run/passwd
mkdir -p /mosquitto/run
rm -f "$PASSWD"
touch "$PASSWD"

add_user() {
  if [ -z "$2" ]; then
    echo "entrypoint: falta la contraseña de '$1' (ver .env.example)" >&2
    exit 1
  fi
  mosquitto_passwd -b "$PASSWD" "$1" "$2"
}

add_user backend     "${MQTT_BACKEND_PASSWORD:-}"
add_user simulator   "${MQTT_SIMULATOR_PASSWORD:-}"
add_user healthcheck "${MQTT_HEALTHCHECK_PASSWORD:-}"

# Dispositivos físicos: "deviceId:clave,deviceId2:clave2".
if [ -n "${MQTT_DEVICE_CREDENTIALS:-}" ]; then
  echo "$MQTT_DEVICE_CREDENTIALS" | tr ',' '\n' | while IFS= read -r cred; do
    [ -n "$cred" ] || continue
    add_user "${cred%%:*}" "${cred#*:}"
  done
fi

# Mosquitto exige que passwd y acl sean del usuario mosquitto y no legibles
# por otros. El montaje del repo es de solo lectura: se copian aquí.
cp /iotlab/mosquitto/acl /iotlab/mosquitto/mosquitto.conf /mosquitto/run/
chown mosquitto:mosquitto /mosquitto/run/*
chmod 0700 /mosquitto/run/passwd /mosquitto/run/acl
echo "entrypoint: $(wc -l < "$PASSWD") usuarios MQTT cargados"

exec /docker-entrypoint.sh /usr/sbin/mosquitto -c /mosquitto/run/mosquitto.conf
