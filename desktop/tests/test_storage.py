from localdrop.config import Settings
from localdrop.storage import CLIP_LIMIT, Storage


def test_settings_roundtrip(tmp_path):
    s = Settings(tmp_path / "settings.json")
    assert s.get("device_name")
    s.update({"audio": {"buffer": 120}})
    s2 = Settings(tmp_path / "settings.json")
    assert s2.get("audio")["buffer"] == 120
    assert s2.get("audio")["quality"] == "balance"


def test_clip_limit_evicts_oldest(tmp_path):
    st = Storage(tmp_path / "db.sqlite")
    for i in range(CLIP_LIMIT + 3):
        _, evicted = st.add_clip("image" if i == 0 else "text", text=str(i), image="a.png" if i == 0 else "")
    assert len(st.clips()) == CLIP_LIMIT
    assert st.clips()[-1]["text"] == "3"


def test_devices_upsert(tmp_path):
    st = Storage(tmp_path / "db.sqlite")
    st.upsert_device("abc", name="PC", paired=1)
    st.upsert_device("abc", trusted=1)
    d = st.device("abc")
    assert d["name"] == "PC" and d["paired"] == 1 and d["trusted"] == 1
