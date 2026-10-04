"""Lifecycle changes around the unmodified upstream cloud service."""
import hashlib
import threading
from musegadget import config
from musegadget.service import Service

# Reset waits for an in-flight rotation to persist, so it cannot resurrect a
# pairing after the user has asked to remove it.
PAIRING_LOCK = threading.Lock()
REPORT_FILE = 'android-sdk-report.json'


class AndroidService(Service):
    def __post_report_key(self):
        return hashlib.sha256((self.identity.node_id + ':' + (self.sdk_token or '')).encode()).hexdigest()

    async def _maybe_refresh(self, pairing, force=False):
        with PAIRING_LOCK:
            key = self.__post_report_key()
            report = config.load_json(REPORT_FILE) or {}
            if self.sdk_token and report.get('fingerprint') == key:
                self._sdk_token_report_attempted = True
            updated = await super()._maybe_refresh(pairing, force=force)
            if self.sdk_token and updated and updated.get('refresh_token') != pairing.get('refresh_token'):
                config.save_json(REPORT_FILE, {'fingerprint': key})
            return updated
