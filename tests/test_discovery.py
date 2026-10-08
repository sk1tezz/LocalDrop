import json

from localdrop.net import ifaces
from localdrop.net.discovery import Discovery


class Peers:
    def __init__(self, ident):
        self.ident = ident
        self.seen_calls = []

    def seen(self, dev_id, info):
        self.seen_calls.append((dev_id, info))


class App:
    def __init__(self, fake):
        self.__dict__.update(fake.__dict__)
        self._fake = fake

    def __getattr__(self, name):
        return getattr(self._fake, name)

    def add_slice(self, *a):
        pass

    def expose(self, *a):
        pass


def test_beacon_parsing(make_app):
    fake = make_app("A")
    peers = Peers(fake.ident)
    d = Discovery(App(fake), peers)
    d.on_beacon(json.dumps({"ld": 1, "id": "abc", "name": "B", "port": 53318, "os": "Windows 11"}).encode(), ("10.0.0.5", 53317))
    d.on_beacon(json.dumps({"ld": 1, "id": fake.ident.id, "name": "me"}).encode(), ("10.0.0.6", 53317))
    d.on_beacon(b"garbage", ("10.0.0.7", 53317))
    assert peers.seen_calls == [("abc", {"name": "B", "os": "Windows 11", "port": 53318, "ip": "10.0.0.5", "hidden": False})]


def test_broadcast_and_subnet():
    assert ifaces.broadcast_addr("192.168.3.77", 24) == "192.168.3.255"
    assert ifaces.same_subnet("192.168.3.10", "192.168.3.77", 24)
    assert not ifaces.same_subnet("192.168.4.10", "192.168.3.77", 24)
