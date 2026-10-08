import asyncio
import json

from localdrop.system.single_instance import FILE, InstanceServer, forward


class App:
    def __init__(self, ddir):
        self.ddir = ddir
        self.requests = []

    def handle_request(self, req):
        self.requests.append(req)


async def test_forward_to_running_instance(tmp_path):
    app = App(tmp_path)
    srv = InstanceServer(app)
    await srv.start()
    loop = asyncio.get_running_loop()
    ok = await loop.run_in_executor(None, forward, tmp_path, {"send": ["C:/x.txt"]})
    assert ok and app.requests[0]["send"] == ["C:/x.txt"]
    # a wrong token is ignored
    info = json.loads((tmp_path / FILE).read_text())
    info["token"] = "bad"
    (tmp_path / FILE).write_text(json.dumps(info))
    assert not await loop.run_in_executor(None, forward, tmp_path, {"show": True})
    assert len(app.requests) == 1
    await srv.stop()
    assert not await loop.run_in_executor(None, forward, tmp_path, {"show": True})
