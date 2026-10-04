"""Durable, bounded events and offline scenes. Cloud delivery is opt-in."""
from __future__ import annotations

import json
import re
import sqlite3
import threading
import time
import uuid

SAFE_ACTIONS = frozenset({"screen.show", "screen.clear", "media.play", "media.control", "speech.say",
                          "speech.stop", "home.call", "mqtt.publish"})


class EventStore:
    def __init__(self, path, clock=time.time):
        self.clock = clock
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS events (
              id TEXT PRIMARY KEY, type TEXT, data TEXT, created REAL, expires REAL,
              delivery TEXT, attempts INTEGER DEFAULT 0, next_try REAL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS rules (id TEXT PRIMARY KEY, body TEXT, last_run REAL DEFAULT 0);
            CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, body TEXT);
            CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY, command TEXT, ok INTEGER, duration_ms INTEGER, created REAL);
        """)
        if "last_error" not in {row[1] for row in self.db.execute("PRAGMA table_info(events)")}:
            self.db.execute("ALTER TABLE events ADD COLUMN last_error TEXT")
        # Resume interval timers from startup, without replaying offline time.
        with self.db:
            for row in self.db.execute("SELECT id,body FROM rules").fetchall():
                if "interval_s" in json.loads(row["body"]):
                    self.db.execute("UPDATE rules SET last_run=? WHERE id=?", (self.clock(), row["id"]))

    def emit(self, kind, data=None, notify=False, ttl_s=300):
        if not isinstance(kind, str) or not re.fullmatch(r"[a-zA-Z0-9_.-]{1,80}", kind):
            raise ValueError("invalid event type")
        if not isinstance(data or {}, dict) or len(json.dumps(data or {})) > 8192:
            raise ValueError("event data must be an object up to 8 KiB")
        if not 1 <= ttl_s <= 86400:
            raise ValueError("ttl_s must be 1..86400")
        now, ident = self.clock(), str(uuid.uuid4())
        with self.lock, self.db:
            self.db.execute("INSERT INTO events(id,type,data,created,expires,delivery) VALUES (?,?,?,?,?,?)",
                            (ident, kind, json.dumps(data or {}), now, now + ttl_s,
                             "queued" if notify else "local"))
            self.db.execute("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY created DESC,rowid DESC LIMIT 200)")
        return {"id": ident, "type": kind, "data": data or {}, "delivery": "queued" if notify else "local"}

    def recent(self):
        with self.lock:
            rows = self.db.execute("SELECT * FROM events ORDER BY created DESC,rowid DESC LIMIT 50").fetchall()
            result, size = [], 0
            for row in rows:
                item = {**dict(row), "data": json.loads(row["data"])}
                cost = len(json.dumps(item).encode())
                if size + cost > 96 * 1024:
                    break
                result.append(item)
                size += cost
            return result

    def pending(self):
        with self.lock, self.db:
            now = self.clock()
            self.db.execute("UPDATE events SET delivery='expired' WHERE delivery='queued' AND expires<=?", (now,))
            row = self.db.execute("SELECT * FROM events WHERE delivery='queued' AND next_try<=? ORDER BY created LIMIT 1", (now,)).fetchone()
            return dict(row) if row else None

    def delivered(self, ident, ok, error=None):
        with self.lock, self.db:
            if ok:
                self.db.execute("UPDATE events SET delivery='delivered',last_error=NULL WHERE id=?", (ident,))
            else:
                self.db.execute("UPDATE events SET attempts=attempts+1,next_try=?,last_error=? WHERE id=?",
                                (self.clock()+30, str(error)[:160] if error else None, ident))

    def audit(self, command, ok, duration_ms):
        # Never store command parameters, output, tokens, URLs or transcripts.
        with self.lock, self.db:
            self.db.execute("INSERT INTO audit(command,ok,duration_ms,created) VALUES(?,?,?,?)",
                            (command, bool(ok), duration_ms, self.clock()))
            self.db.execute("DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT 200)")

    def history(self):
        with self.lock:
            return [dict(r) for r in self.db.execute("SELECT * FROM audit ORDER BY id DESC LIMIT 30")]

    def put_rule(self, params, validate_action):
        ident = params.get("id", "")
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", ident):
            raise ValueError("invalid automation id")
        actions = params.get("actions")
        if not isinstance(actions, list) or not 1 <= len(actions) <= 10:
            raise ValueError("actions must contain 1..10 items")
        for action in actions:
            if not isinstance(action, dict) or action.get("command") not in SAFE_ACTIONS:
                raise ValueError("this command cannot run in an automation")
            validate_action(action["command"], action.get("params", {}))
        if "event" in params and "interval_s" in params:
            raise ValueError("choose an event or an interval")
        if "interval_s" in params and not 60 <= params["interval_s"] <= 86400 * 7:
            raise ValueError("interval_s must be 60..604800")
        if not 1 <= params.get("cooldown_s", 30) <= 86400:
            raise ValueError("cooldown_s must be 1..86400")
        if len(json.dumps(params)) > 32768:
            raise ValueError("automation exceeds 32 KiB")
        with self.lock, self.db:
            count = self.db.execute("SELECT count(*) FROM rules WHERE id != ?", (ident,)).fetchone()[0]
            if count >= 100:
                raise ValueError("maximum 100 automations")
            # Intervals start from now; a restart never replays missed runs.
            self.db.execute("INSERT OR REPLACE INTO rules VALUES (?,?,?)", (ident, json.dumps(params), self.clock() if "interval_s" in params else 0))
        return {"saved": ident}

    def rules(self):
        with self.lock:
            return [{**json.loads(r["body"]), "last_run": r["last_run"]} for r in self.db.execute("SELECT * FROM rules ORDER BY id")]

    def delete_rule(self, ident):
        with self.lock, self.db:
            return self.db.execute("DELETE FROM rules WHERE id=?", (ident,)).rowcount == 1

    def claim(self, ident, expected):
        with self.lock, self.db:
            return self.db.execute("UPDATE rules SET last_run=? WHERE id=? AND last_run=?", (self.clock(), ident, expected)).rowcount == 1

    def due(self, event=None):
        now, result = self.clock(), []
        for rule in self.rules():
            if not rule.get("enabled", True):
                continue
            if event is not None:
                match = (rule.get("event") == event["type"] and
                         all(event["data"].get(k) == v for k, v in rule.get("match", {}).items()) and
                         now - rule["last_run"] >= rule.get("cooldown_s", 30))
            else:
                match = "interval_s" in rule and now - rule["last_run"] >= rule["interval_s"]
            if match and self.claim(rule["id"], rule["last_run"]):
                result.append(rule)
        return result

    def remember_devices(self, devices):
        with self.lock, self.db:
            for device in devices[:50]:
                old = self.db.execute("SELECT body FROM devices WHERE id=?", (device["id"],)).fetchone()
                previous = json.loads(old[0]) if old else {}
                body = {**previous, **device}
                for key in ("alias", "room"):
                    if key in previous:
                        body[key] = previous[key]
                self.db.execute("INSERT OR REPLACE INTO devices VALUES(?,?)", (device["id"], json.dumps(body)))

    def devices(self):
        with self.lock:
            return [json.loads(row[0]) for row in self.db.execute("SELECT body FROM devices")]

    def name_device(self, ident, name, room=""):
        with self.lock, self.db:
            row = self.db.execute("SELECT body FROM devices WHERE id=?", (ident,)).fetchone()
            if not row:
                raise ValueError("discover this receiver first")
            body = {**json.loads(row[0]), "alias": name[:80], "room": room[:80]}
            self.db.execute("UPDATE devices SET body=? WHERE id=?", (json.dumps(body), ident))
            return body

    def close(self):
        with self.lock:
            self.db.close()
