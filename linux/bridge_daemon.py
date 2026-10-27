#!/usr/bin/env python3
"""Bridge daemon (Linux side): a TLS WebSocket hub with a plugin system.

Usage:
    bridge_daemon.py                start the daemon
    bridge_daemon.py --pair         open a 2 minute pairing window and show the code
    bridge_daemon.py --devices      list paired devices
    bridge_daemon.py --revoke ID    remove a paired device
"""
import asyncio
import hashlib
import hmac
import json
import logging
import os
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
import tomllib
from datetime import datetime
from pathlib import Path

import websockets

PROTOCOL_VERSION = 1
CONFIG_PATH = (
    Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    / "bridge" / "config.toml"
)
CONFIG_DIR = CONFIG_PATH.parent
DEVICES_PATH = CONFIG_DIR / "devices.json"
MDNS_TYPE = "_bridge._tcp.local."

# Pairing parameters (the app uses the same constants)
CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # Crockford base32, 8 chars = 40 bits
CODE_LENGTH = 8
PBKDF2_ITERATIONS = 100_000
PAIR_WINDOW_SECONDS = 120
PAIR_MAX_ATTEMPTS = 3

log = logging.getLogger("bridge")


def load_config() -> dict:
    if not CONFIG_PATH.exists():
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text('host = "0.0.0.0"\nport = 8765\n')
        log.info("created %s", CONFIG_PATH)
    return tomllib.loads(CONFIG_PATH.read_text())


def ensure_cert() -> tuple[Path, Path]:
    """Create a self-signed certificate on first run (requires the openssl CLI)."""
    cert, key = CONFIG_DIR / "cert.pem", CONFIG_DIR / "key.pem"
    if not (cert.exists() and key.exists()):
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "ec",
             "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
             "-keyout", str(key), "-out", str(cert),
             "-days", "3650", "-subj", "/CN=bridge"],
            check=True, capture_output=True,
        )
        key.chmod(0o600)
        log.info("generated a self-signed certificate")
    return cert, key


def fingerprint(cert: Path) -> str:
    """SHA-256 of the DER certificate. Paired phones pin this value."""
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return hashlib.sha256(der).hexdigest()


def local_ip() -> str:
    # Connecting a UDP socket sends nothing; it only selects the outgoing interface
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def make_service_info(port: int, fp: str):
    from zeroconf import ServiceInfo

    host = socket.gethostname().split(".")[0]
    return ServiceInfo(
        MDNS_TYPE,
        f"{host}.{MDNS_TYPE}",
        addresses=[socket.inet_aton(local_ip())],
        port=port,
        properties={"v": "1", "fp": fp[:16]},  # fp prefix lets the phone pick the right host
        server=f"{host}.local.",
    )


async def advertise(port: int, fp: str):
    """Announce the service over mDNS. Returns the zeroconf instance or None."""
    try:
        from zeroconf import IPVersion
        from zeroconf.asyncio import AsyncZeroconf
    except ImportError:
        log.warning("python-zeroconf is not installed: mDNS discovery is disabled")
        return None
    try:
        info = make_service_info(port, fp)
        zc = AsyncZeroconf(ip_version=IPVersion.V4Only)
        await zc.async_register_service(info)
        log.info("mDNS: advertising %s on %s:%d", MDNS_TYPE, local_ip(), port)
        return zc
    except Exception as e:  # discovery is optional, never fatal
        log.warning("mDNS advertising failed: %s", e)
        return None


async def watch_address(zc, port: int, fp: str, interval: float = 10.0) -> None:
    """Re-announce the mDNS record when this machine's address changes
    (Wi-Fi reconnect, DHCP renewal, resume from suspend), so the phone can
    find the new address when it reconnects."""
    current = local_ip()
    offline = current == "127.0.0.1"
    while True:
        await asyncio.sleep(interval)
        ip = local_ip()
        if ip == "127.0.0.1":  # no route: wait until the network is back
            offline = True
            continue
        if ip == current and not offline:
            continue
        try:
            await zc.async_update_service(make_service_info(port, fp))
        except Exception as e:  # retried on the next tick
            log.warning("mDNS re-announce failed: %s", e)
            continue
        log.info("mDNS: address is now %s (was %s), re-announced", ip, current)
        current, offline = ip, False


def normalize_code(code: str) -> str:
    code = "".join(ch for ch in code.upper() if ch.isalnum())
    return code.translate(str.maketrans("OIL", "011"))


class DeviceStore:
    """Paired devices. Only a hash of each device token is stored."""

    @staticmethod
    def _load() -> dict:
        try:
            return json.loads(DEVICES_PATH.read_text())
        except (OSError, ValueError):
            return {}

    @staticmethod
    def _save(data: dict) -> None:
        DEVICES_PATH.parent.mkdir(parents=True, exist_ok=True)
        DEVICES_PATH.write_text(json.dumps(data, indent=2))
        DEVICES_PATH.chmod(0o600)

    def all(self) -> dict:
        return self._load()

    def add(self, name: str) -> tuple[str, str]:
        device_id, token = secrets.token_hex(8), secrets.token_urlsafe(24)
        data = self._load()
        data[device_id] = {
            "name": name,
            "token_sha256": hashlib.sha256(token.encode()).hexdigest(),
            "added": datetime.now().isoformat(timespec="seconds"),
        }
        self._save(data)
        return device_id, token

    def verify(self, device_id, token) -> bool:
        if not isinstance(device_id, str) or not isinstance(token, str):
            return False
        entry = self._load().get(device_id)
        if not entry:
            return False
        digest = hashlib.sha256(token.encode()).hexdigest()
        return hmac.compare_digest(digest, entry["token_sha256"])

    def remove(self, device_id: str) -> bool:
        data = self._load()
        if device_id not in data:
            return False
        del data[device_id]
        self._save(data)
        return True


class PairingManager:
    """One-time pairing code with an expiry and a limited number of attempts."""

    def __init__(self):
        self.code: str | None = None
        self.expires = 0.0
        self.attempts = 0

    def open(self) -> str:
        self.code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
        self.expires = time.monotonic() + PAIR_WINDOW_SECONDS
        self.attempts = 0
        return self.code

    def is_open(self) -> bool:
        return self.code is not None and time.monotonic() < self.expires

    def close(self) -> None:
        self.code = None

    def fail(self) -> None:
        self.attempts += 1
        if self.attempts >= PAIR_MAX_ATTEMPTS:
            self.close()


class Plugin:
    """Base class. A plugin owns every message whose type starts with `prefix.`."""

    prefix = ""
    hub: "Hub"

    async def start(self) -> None: ...
    async def on_connect(self, ws) -> None: ...
    async def on_message(self, ws, type_: str, data: dict) -> None: ...


def encode(type_: str, data: dict | None = None) -> str:
    return json.dumps({"v": PROTOCOL_VERSION, "type": type_, "data": data or {}})


class Hub:
    def __init__(self, devices: DeviceStore, pairing: PairingManager, fp_bytes: bytes):
        self.devices = devices
        self.pairing = pairing
        self.fp_bytes = fp_bytes
        self.clients: set = set()
        self.plugins: dict[str, Plugin] = {}

    def register(self, plugin: Plugin) -> None:
        plugin.hub = self
        self.plugins[plugin.prefix] = plugin

    async def broadcast(self, type_: str, data: dict, exclude=None) -> None:
        msg = encode(type_, data)
        for ws in list(self.clients):
            if ws is not exclude:
                try:
                    await ws.send(msg)
                except websockets.ConnectionClosed:
                    pass

    def open_pairing(self) -> str:
        code = self.pairing.open()
        shown = f"{code[:4]}-{code[4:]}"
        log.info("pairing code: %s (valid for %d seconds)", shown, PAIR_WINDOW_SECONDS)
        asyncio.create_task(notify("Bridge pairing code", f"{shown}  (valid for 2 minutes)"))
        return shown

    async def _pair(self, ws, req: dict) -> None:
        """Code-based pairing. The proof is bound to this server's certificate
        fingerprint, so a man-in-the-middle presenting another certificate fails."""
        code = self.pairing.code
        if not self.pairing.is_open() or code is None:
            await ws.send(encode("pair.denied", {"reason": "pairing mode is off"}))
            await ws.close(4403, "pairing mode is off")
            return
        try:
            cn = bytes.fromhex(req["client_nonce"])
            name = str(req.get("device_name", "device"))[:64]
            if not 8 <= len(cn) <= 64:
                raise ValueError
        except (KeyError, ValueError, TypeError):
            await ws.close(4400, "bad request")
            return

        sn = secrets.token_bytes(16)
        await ws.send(encode("pair.challenge", {"server_nonce": sn.hex()}))
        try:
            reply = json.loads(await asyncio.wait_for(ws.recv(), 30))
            proof = bytes.fromhex(reply["data"]["proof"])
        except Exception:
            self.pairing.fail()
            await ws.close(4400, "bad proof")
            return

        key = await asyncio.to_thread(
            hashlib.pbkdf2_hmac, "sha256", code.encode(), sn, PBKDF2_ITERATIONS, 32
        )
        expected = hmac.new(key, b"client" + cn + sn + self.fp_bytes, hashlib.sha256).digest()
        if not hmac.compare_digest(proof, expected):
            self.pairing.fail()
            log.warning("pairing failed (wrong code or man-in-the-middle), attempt %d/%d",
                        self.pairing.attempts, PAIR_MAX_ATTEMPTS)
            await ws.send(encode("pair.denied", {"reason": "wrong code"}))
            await ws.close(4403, "wrong code")
            return

        device_id, token = self.devices.add(name)
        self.pairing.close()  # single use
        server_proof = hmac.new(key, b"server" + cn + sn + self.fp_bytes, hashlib.sha256).hexdigest()
        await ws.send(encode("pair.ok", {
            "device_id": device_id, "token": token, "server_proof": server_proof,
        }))
        log.info("paired device %r (%s)", name, device_id)

    async def handler(self, ws) -> None:
        try:
            first = json.loads(await asyncio.wait_for(ws.recv(), 5))
            kind, data = first["type"], first.get("data", {})
            if not isinstance(data, dict):
                raise ValueError
        except Exception:
            await ws.close(4400, "bad request")
            return

        if kind == "pair.request":
            await self._pair(ws, data)
            return
        if kind != "hello" or not self.devices.verify(data.get("device_id"), data.get("token")):
            log.warning("authentication failed (unknown device or wrong token)")
            await ws.close(4401, "unauthorized")
            return

        await ws.send(encode("hello.ok"))
        log.info("client connected")
        self.clients.add(ws)
        try:
            for p in self.plugins.values():
                await p.on_connect(ws)
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                    type_ = msg["type"]
                except (ValueError, KeyError, TypeError):
                    continue
                plugin = self.plugins.get(type_.split(".", 1)[0])
                if plugin:
                    await plugin.on_message(ws, type_, msg.get("data", {}))
        except websockets.ConnectionClosed:
            pass
        finally:
            self.clients.discard(ws)
            log.info("client disconnected")


async def notify(title: str, body: str) -> None:
    if not shutil.which("notify-send"):
        return
    proc = await asyncio.create_subprocess_exec(
        "notify-send", "-t", "120000", title, body,
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
    )
    await proc.wait()


def control_path() -> Path:
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if runtime:
        return Path(runtime) / "bridge.sock"
    return Path(f"/tmp/bridge-{os.getuid()}.sock")


async def start_control(hub: Hub):
    """Unix socket used by `bridge_daemon.py --pair` to talk to the running daemon."""
    path = control_path()
    path.unlink(missing_ok=True)

    async def on_client(reader, writer):
        cmd = (await reader.readline()).decode().strip()
        if cmd == "pair":
            writer.write(f"{hub.open_pairing()}\n".encode())
        await writer.drain()
        writer.close()

    server = await asyncio.start_unix_server(on_client, path=str(path))
    path.chmod(0o600)
    return server


class ClipboardPlugin(Plugin):
    """Wayland clipboard sync using wl-clipboard."""

    prefix = "clipboard"
    MAX_BYTES = 1 << 20

    def __init__(self):
        self.last_hash: str | None = None
        self.available = shutil.which("wl-paste") and shutil.which("wl-copy")

    @staticmethod
    def _hash(text: str) -> str:
        return hashlib.sha256(text.encode()).hexdigest()

    async def start(self) -> None:
        if not self.available:
            log.error("wl-clipboard is not installed: clipboard plugin is disabled")
            return
        asyncio.create_task(self._watch())

    async def _read(self) -> str | None:
        proc = await asyncio.create_subprocess_exec(
            "wl-paste", "--no-newline", "--type", "text",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
        )
        out, _ = await proc.communicate()
        if proc.returncode != 0 or not out or len(out) > self.MAX_BYTES:
            return None
        return out.decode(errors="replace")

    async def _write(self, text: str) -> None:
        proc = await asyncio.create_subprocess_exec(
            "wl-copy", stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        await proc.communicate(text.encode())

    async def _watch(self) -> None:
        # `wl-paste --watch echo` prints one line per clipboard change
        while True:
            proc = await asyncio.create_subprocess_exec(
                "wl-paste", "--watch", "echo",
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
            )
            while await proc.stdout.readline():
                text = await self._read()
                if text is None:
                    continue
                h = self._hash(text)
                if h == self.last_hash:
                    continue  # echo of a value we just applied
                self.last_hash = h
                log.info("local clipboard changed (%d chars), %d client(s)", len(text), len(self.hub.clients))
                await self.hub.broadcast("clipboard.update", {"text": text})
            log.warning("wl-paste exited, restarting")
            await asyncio.sleep(2)

    async def on_message(self, ws, type_: str, data: dict) -> None:
        if type_ != "clipboard.update" or not self.available:
            return
        text = data.get("text")
        if not isinstance(text, str) or len(text.encode()) > self.MAX_BYTES:
            return
        h = self._hash(text)
        if h == self.last_hash:
            return
        self.last_hash = h
        log.info("received clipboard from client (%d chars)", len(text))
        await self._write(text)
        await self.hub.broadcast("clipboard.update", data, exclude=ws)


def run_cli(argv: list[str]) -> bool:
    """Handle management commands. Returns True if one was handled."""
    if "--pair" in argv:
        try:
            with socket.socket(socket.AF_UNIX) as s:
                s.connect(str(control_path()))
                s.sendall(b"pair\n")
                code = s.makefile().readline().strip()
        except OSError:
            print("The daemon is not running. Start it first.", file=sys.stderr)
            sys.exit(1)
        print(f"Pairing code: {code}  (valid for {PAIR_WINDOW_SECONDS // 60} minutes)")
        return True
    if "--devices" in argv:
        devices = DeviceStore().all()
        if not devices:
            print("No paired devices.")
        for device_id, d in devices.items():
            print(f"{device_id}  {d['name']}  (added {d['added']})")
        return True
    if "--revoke" in argv:
        idx = argv.index("--revoke")
        if idx + 1 >= len(argv):
            print("usage: bridge_daemon.py --revoke ID", file=sys.stderr)
            sys.exit(2)
        ok = DeviceStore().remove(argv[idx + 1])
        print("Removed." if ok else "No such device.")
        return True
    return False


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    cfg = load_config()
    cert, key = ensure_cert()
    fp = fingerprint(cert)

    devices = DeviceStore()
    hub = Hub(devices, PairingManager(), bytes.fromhex(fp))
    # Register new features here
    hub.register(ClipboardPlugin())
    for p in hub.plugins.values():
        await p.start()

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, key)

    async with websockets.serve(hub.handler, cfg["host"], cfg["port"], ssl=ctx):
        log.info("listening (TLS) on %s:%s", cfg["host"], cfg["port"])
        zc = await advertise(cfg["port"], fp)
        watcher = asyncio.create_task(watch_address(zc, cfg["port"], fp)) if zc else None
        control = await start_control(hub)
        if not devices.all():
            hub.open_pairing()  # first run: nothing is paired yet
        else:
            log.info("%d paired device(s). Run `bridge_daemon.py --pair` to add another.", len(devices.all()))
        try:
            await asyncio.Future()
        finally:
            if watcher:
                watcher.cancel()
            control.close()
            control_path().unlink(missing_ok=True)
            if zc:
                await zc.async_close()


if __name__ == "__main__":
    if not run_cli(sys.argv[1:]):
        asyncio.run(main())
