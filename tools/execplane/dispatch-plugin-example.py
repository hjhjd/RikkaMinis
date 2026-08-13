"""Minimal opaque dispatch plugin example.

The Android client never imports this module and never parses payload. Replace
this function with any server-owned DSL/router implementation.
"""

async def dispatch(payload, emit):
    await emit(f"received {len(payload.encode('utf-8'))} bytes\n")
    return {"output": payload, "truncated": False}
