#!/usr/bin/env python3
"""Gives an offline Boox the laptop's network for live checks (debug builds only).
   usage: scripts/dev_proxy.py [--port 8899] [--any]
   then:  adb reverse tcp:8899 tcp:8899
          adb shell "am start -n com.nisaba/.app.MainActivity --es proxy 127.0.0.1:8899"
   HTTPS CONNECT tunnels only, bound to localhost, to api.deepseek.com unless --any."""
import asyncio, sys

PORT = int(sys.argv[sys.argv.index("--port") + 1]) if "--port" in sys.argv else 8899
ALLOWED = None if "--any" in sys.argv else {("api.deepseek.com", 443)}

async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data); await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()

async def handle(client_r, client_w):
    head = await client_r.readuntil(b"\r\n\r\n")
    method, target, _ = head.split(b"\r\n", 1)[0].decode().split(" ", 2)
    host, _, port = target.rpartition(":")
    if method != "CONNECT" or (ALLOWED is not None and (host, int(port)) not in ALLOWED):
        client_w.write(b"HTTP/1.1 403 Forbidden\r\n\r\n"); await client_w.drain(); client_w.close(); return
    try:
        up_r, up_w = await asyncio.open_connection(host, int(port))
    except OSError as e:
        client_w.write(b"HTTP/1.1 502 Bad Gateway\r\n\r\n"); await client_w.drain(); client_w.close(); print("upstream", e); return
    client_w.write(b"HTTP/1.1 200 Connection established\r\n\r\n"); await client_w.drain()
    print("tunnel", target, flush=True)
    await asyncio.gather(pipe(client_r, up_w), pipe(up_r, client_w))

async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", PORT)
    print(f"dev proxy on 127.0.0.1:{PORT} ({'any host' if ALLOWED is None else 'api.deepseek.com only'})", flush=True)
    async with server: await server.serve_forever()

asyncio.run(main())
