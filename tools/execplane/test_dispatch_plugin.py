import asyncio
import importlib.util
import pathlib
import tempfile
import unittest

from runtime import RpcFault

PLUGIN = pathlib.Path(__file__).with_name("dispatch-plugin-example.py")
_spec = importlib.util.spec_from_file_location("dispatch_plugin", PLUGIN)
plugin = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(plugin)


class DispatchPluginTest(unittest.IsolatedAsyncioTestCase):
    async def collect(self, payload):
        events = []

        async def emit(text, stream="output"):
            events.append((stream, text))

        result = await plugin.dispatch(payload, emit)
        return result, events

    async def test_help_and_status(self):
        help_result, _ = await self.collect("help")
        self.assertIn("exec <shell script>", help_result["output"])
        status, _ = await self.collect("status")
        self.assertIn("dslRevision=vcpminis-dsl-1", status["output"])
        self.assertIn("commands=help,status,exec", status["output"])

    async def test_single_and_multiline_exec(self):
        single, events = await self.collect("exec printf hello")
        self.assertEqual("".join(text for _, text in events), "hello")
        self.assertIn("hello", single["output"])
        self.assertIn("[exit=0", single["output"])

        multiline, _ = await self.collect("exec\nprintf one\nprintf two")
        self.assertIn("onetwo", multiline["output"])

    async def test_stdout_stderr_and_nonzero_exit(self):
        result, events = await self.collect("exec printf out; printf err >&2; exit 7")
        self.assertEqual({stream for stream, _ in events}, {"stdout", "stderr"})
        self.assertIn("[exit=7", result["output"])

    async def test_unknown_and_missing_body_are_rejected(self):
        with self.assertRaises(RpcFault) as unknown:
            await self.collect("made-up value")
        self.assertEqual("EXEC_METHOD_NOT_FOUND", unknown.exception.code)
        with self.assertRaises(RpcFault) as missing:
            await self.collect("exec")
        self.assertEqual("EXEC_INVALID_PARAMS", missing.exception.code)

    async def test_cancellation_kills_process_group(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = pathlib.Path(directory) / "orphan"
            payload = f"exec (sleep 1; printf orphan > {marker}) & wait"
            task = asyncio.create_task(self.collect(payload))
            await asyncio.sleep(0.1)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            await asyncio.sleep(1.2)
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
