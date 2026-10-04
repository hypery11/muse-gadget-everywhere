"""Service entry point, called from GadgetService on a worker thread."""

from __future__ import annotations


def main(files_dir: str, cache_dir: str, display_name: str, tv_control=None) -> None:
    import logging

    from androidtv.env import configure

    configure(files_dir, cache_dir)
    log = logging.getLogger("androidtv.service")

    from musegadget import config, identity
    from musegadget.service import Service
    from androidtv.compat import AndroidExecutor, android_account
    from androidtv.tv import TV_COMMAND_SPECS

    # Register tv.* alongside upstream commands. Upstream holds the same dict
    # object, so in-place update is visible to link.register. No files touched.
    import musegadget.executor as upstream_executor

    upstream_executor.COMMAND_SPECS.update(TV_COMMAND_SPECS)

    try:
        sdk_token = config.sdk_token()
    except ValueError as exc:
        log.warning("running without an SDK token: %s", exc)
        sdk_token = None

    import asyncio

    account = android_account(files_dir)
    log.info("commands run as %s (uid=%d home=%s)", account.name, account.uid, account.home)
    service = Service(
        identity=identity.load_or_create(),
        executor=AndroidExecutor(account, tv_control),
        sdk_token=sdk_token,
        display_name=display_name,
    )
    asyncio.run(_serve(service))


async def _serve(service) -> None:
    from musegadget import config

    server = await service.serve_local(config.socket_path())
    try:
        await service.run()
    finally:
        server.close()
