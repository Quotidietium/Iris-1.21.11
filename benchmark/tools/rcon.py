#!/usr/bin/env python3
"""Minimal RCON client for the smoke-test server (benchmark/tools/rcon.py).

Usage: python rcon.py <command...>   (host 127.0.0.1, port 25575, password
from RCON_PASSWORD env or 'smoketest321')

Source-protocol RCON: https://wiki.vg/RCON (length-prefixed packets,
type 3 = auth, type 2 = command; responses are type 0).
"""
import os
import socket
import struct
import sys

HOST = "127.0.0.1"
PORT = 25575
PASSWORD = os.environ.get("RCON_PASSWORD", "smoketest321")


def send_packet(sock, req_id, ptype, payload):
    body = struct.pack("<ii", req_id, ptype) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def recv_packet(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError("rcon closed")
        raw += chunk
    (length,) = struct.unpack("<i", raw)
    body = b""
    while len(body) < length:
        chunk = sock.recv(length - len(body))
        if not chunk:
            raise ConnectionError("rcon closed mid-packet")
        body += chunk
    req_id, ptype = struct.unpack("<ii", body[:8])
    return req_id, ptype, body[8:-2].decode("utf-8", "replace")


def command(cmd):
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        send_packet(sock, 1, 3, PASSWORD)
        rid, _, _ = recv_packet(sock)
        if rid == -1:
            raise SystemExit("rcon auth failed")
        send_packet(sock, 2, 2, cmd)
        _, _, payload = recv_packet(sock)
        return payload


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    out = command(" ".join(sys.argv[1:]))
    print(out if out.strip() else "(empty response)")
