import base64

from androidtv.compat import AndroidExecutor, android_account


def test_default_executor_cannot_use_shell_to_read_credentials(tmp_path):
    secret = tmp_path / "musegadget" / "pairing.json"
    secret.parent.mkdir()
    secret.write_text("dummy-only")
    executor = AndroidExecutor(android_account(str(tmp_path)))
    result = executor.run("system.run", {"command": f"cat '{secret}'"})
    assert not result["ok"]
    assert "developer" in result["error"].lower()


def test_workspace_denies_siblings_and_symlinks(tmp_path):
    executor = AndroidExecutor(android_account(str(tmp_path)))
    outside = tmp_path / "settings.json"
    outside.write_text("dummy-only")
    workspace = tmp_path / "workspace"
    workspace.mkdir(exist_ok=True)
    (workspace / "alias").symlink_to(outside)
    for path in [str(outside), "../settings.json", "alias"]:
        assert not executor.file_op("read", {"path": path})["ok"]


def test_workspace_roundtrip_and_partial_symlink(tmp_path):
    executor = AndroidExecutor(android_account(str(tmp_path)))
    body = base64.b64encode(b"hello").decode()
    assert executor.file_op("write", {"path": "notes/x.txt", "data_b64": body,
                                      "create_parents": True, "final": True})["ok"]
    assert executor.file_op("read", {"path": "notes/x.txt"})["payload"]["data_b64"] == body
    secret = tmp_path / "secret"
    secret.write_text("unchanged")
    (tmp_path / "workspace" / ".y.txt.musegadget-partial").symlink_to(secret)
    assert not executor.file_op("write", {"path": "y.txt", "data_b64": body, "final": True})["ok"]
    assert secret.read_text() == "unchanged"
