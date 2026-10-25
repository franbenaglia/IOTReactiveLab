import json
import random

import pytest

from iotsim import contract
from iotsim.config import Config
from iotsim.device import VirtualDevice, device_ids


def test_device_ids_are_unique_and_match_contract_format():
    ids = device_ids("esp32-sim-", 5000)
    assert len(set(ids)) == 5000
    assert ids[0] == "esp32-sim-00001" and ids[-1] == "esp32-sim-05000"
    assert all(contract.DEVICE_ID_RE.match(i) for i in ids)


def test_id_offset_avoids_collisions_between_instances():
    a = set(device_ids("esp32-sim-", 1000, offset=0))
    b = set(device_ids("esp32-sim-", 1000, offset=1000))
    assert not a & b


def test_sequence_starts_at_one_and_increments():
    dev = VirtualDevice.create("esp32-sim-00001", Config(), random.Random(1))
    assert [dev.next_telemetry()["sequence"] for _ in range(3)] == [1, 2, 3]


@pytest.mark.parametrize("humidity", [False, True])
def test_generated_telemetry_respects_contract(humidity):
    cfg = Config()
    cfg.humidity.enabled = humidity
    dev = VirtualDevice.create("esp32-sim-00042", cfg, random.Random(1))
    msg = dev.next_telemetry()
    assert set(msg) == set(contract.TELEMETRY_FIELDS)
    assert msg["source"] == "SIMULATED"
    assert (msg["humidity"] is None) is (not humidity)
    assert contract.validate_telemetry(dev.topic, contract.encode(msg)) == []


def test_topics():
    assert contract.telemetry_topic("esp32-sim-00001") == "iot/devices/esp32-sim-00001/telemetry"
    assert contract.status_topic("esp32-sim-00001") == "iot/devices/esp32-sim-00001/status"


def test_timestamp_is_utc_with_z():
    ts = contract.utc_now_iso()
    assert ts.endswith("Z") and "T" in ts


@pytest.mark.parametrize("kind", contract.INVALID_KINDS)
def test_each_invalid_kind_is_detected(kind):
    valid = contract.telemetry("esp32-sim-00001", 24.5, None, 1)
    _, payload = contract.invalid_payload(valid, random.Random(1), kind)
    assert contract.validate_telemetry("iot/devices/esp32-sim-00001/telemetry", payload) != []


def test_wrong_topic_is_invalid():
    payload = contract.encode(contract.telemetry("esp32-sim-00001", 24.5, None, 1))
    assert contract.validate_telemetry("iot/devices/esp32-sim-00001/status", payload)


def test_status_and_will_payloads():
    dev = VirtualDevice.create("esp32-sim-00001", Config(), random.Random(1))
    assert json.loads(dev.status_payload("online"))["status"] == "online"
    will = json.loads(dev.will_payload())
    assert will == {"deviceId": "esp32-sim-00001", "status": "offline"}
