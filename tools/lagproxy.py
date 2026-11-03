"""A TCP proxy that adds latency, to test against a local server under 9b9t-like ping.

python lagproxy.py [--listen 25566] [--target 127.0.0.1:25565] [--delay 100] [--jitter 20]

Every chunk is held --delay ms (plus up to --jitter ms) in each direction, so the round trip grows by
about twice --delay. Order is kept: a chunk is never sent before the one in front of it.
"""
import argparse
import asyncio
import random
import time


async def pipe(reader, writer, delay, jitter):
    queue = asyncio.Queue()

    async def sender():
        while True:
            due, data = await queue.get()
            if data is None:
                break
            wait = due - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            writer.write(data)
            await writer.drain()

    task = asyncio.create_task(sender())
    last_due = 0.0
    try:
        while data := await reader.read(65536):
            due = max(time.monotonic() + (delay + random.uniform(0, jitter)) / 1000, last_due)
            last_due = due
            await queue.put((due, data))
    except (ConnectionError, asyncio.IncompleteReadError):
        pass
    await queue.put((0, None))
    try:
        await task
    except ConnectionError:
        pass
    writer.close()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=25566)
    ap.add_argument("--target", default="127.0.0.1:25565")
    ap.add_argument("--delay", type=float, default=100)
    ap.add_argument("--jitter", type=float, default=20)
    a = ap.parse_args()
    host, port = a.target.rsplit(":", 1)

    async def handle(c_reader, c_writer):
        s_reader, s_writer = await asyncio.open_connection(host, int(port))
        print(f"connection from {c_writer.get_extra_info('peername')}", flush=True)
        await asyncio.gather(pipe(c_reader, s_writer, a.delay, a.jitter), pipe(s_reader, c_writer, a.delay, a.jitter))
        print("connection closed", flush=True)

    server = await asyncio.start_server(handle, "127.0.0.1", a.listen)
    print(f"lag proxy 127.0.0.1:{a.listen} -> {a.target}, +{a.delay:g} ms (+0-{a.jitter:g} ms jitter) each way", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
