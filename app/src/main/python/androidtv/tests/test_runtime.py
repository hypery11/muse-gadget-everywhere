"""Behavioral tests for the runtime boundary, durability and actual HTTP I/O."""
import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from androidtv.compat import AndroidExecutor, android_account
from androidtv.events import EventStore
from androidtv.runtime import Runtime
from androidtv.commands import catalog, validate
from androidtv.integrations import HomeAssistant, Mqtt, http_url


class Native:
    def __init__(self, **settings):
        self.config = settings
        self.calls = []
        self.events = []
        self.fail = None

    def settings(self):
        return json.dumps(self.config)

    def invoke(self, command, params):
        self.calls.append((command, json.loads(params)))
        return json.dumps({'ok': command != self.fail, 'payload': {'observed': command}})

    def drainEvents(self):
        events, self.events = self.events, []
        return json.dumps(events)


@pytest.fixture
def runtime(tmp_path):
    runtime = Runtime(AndroidExecutor(android_account(str(tmp_path))), Native())
    yield runtime
    runtime.close()


@pytest.mark.parametrize('command,params', [
    ('screen.show', {'text': 3}), ('media.control', {'action': 'seek', 'value': True}),
    ('media.play', {}), ('camera.capture', {'silent': True}),
    ('events.emit', {'type': 'x', 'data': []}), ('no.such.command', {}),
    ('media.control', {'action': 'volume', 'value': float('nan')}),
    ('screen.show', {'text': 'x' * 140000}), ('screen.show', []),
])
def test_invalid_requests_never_reach_native(runtime, command, params):
    assert not runtime.run(command, params)['ok']
    assert runtime.native.calls == []


def test_default_permissions_and_audit_do_not_include_inputs(runtime):
    assert 'system.run' not in runtime.specs()
    assert not runtime.run('system.run', {'command': 'secret-canary'})['ok']
    assert not runtime.run('home.call', {'domain':'light', 'service':'turn_on', 'entity_id':'light.test'})['ok']
    assert not runtime.run('mqtt.publish', {'topic':'muse/test', 'payload':'private-canary'})['ok']
    history = runtime.store.history()
    assert len(history) == 3
    assert 'canary' not in json.dumps(history)
    assert not runtime.run('screen.show', {}, timeout_ms=float('inf'))['ok']


def test_scenes_stop_on_failure_and_cannot_execute_shell(runtime):
    actions = [{'command':'screen.show','params':{'text':'one'}}, {'command':'speech.say','params':{'text':'two'}}]
    assert runtime.run('automation.put', {'id':'demo','actions':actions})['ok']
    runtime.native.fail = 'screen.show'
    result = runtime.run('automation.run', {'id':'demo'})
    assert not result['ok'] and not result['payload']['completed']
    assert [c[0] for c in runtime.native.calls] == ['screen.show']
    assert not runtime.run('automation.put', {'id':'unsafe','actions':[{'command':'system.run','params':{'command':'id'}}]})['ok']
    assert runtime.run('automation.delete', {'id':'demo'})['payload']['deleted']


def test_event_deduplication_retained_and_local_opt_in(runtime):
    runtime.native.config['notify_muse'] = True
    runtime.run('automation.put', {'id':'event', 'event':'mqtt.message', 'actions':[{'command':'screen.show'}]})
    first = runtime.emit('mqtt.message', {'payload':'x','retained':True})
    assert first['delivery'] == 'local'
    assert runtime.emit('mqtt.message', {'payload':'x','retained':True})['status'] == 'deduplicated'
    runtime.tick()
    assert not runtime.native.calls
    runtime.emit('mqtt.message', {'payload':'y','retained':False})
    runtime.tick()
    assert runtime.native.calls[0][0] == 'screen.show'
    assert runtime.store.pending()['type'] == 'mqtt.message'
    runtime.native.config['notify_muse'] = False
    assert runtime.emit('voice.text', {'text':'local-only'}, True)['delivery'] == 'local'


def test_durable_expiry_retry_bounded_storage_and_restart(tmp_path):
    now = [1000.0]
    path = tmp_path/'events.sqlite'
    store = EventStore(path, clock=lambda:now[0])
    event = store.emit('test', notify=True, ttl_s=60)
    store.delivered(event['id'], False)
    assert store.pending() is None
    now[0] += 31
    assert store.pending()['attempts'] == 1
    now[0] += 31
    assert store.pending() is None and store.recent()[0]['delivery'] == 'expired'
    store.put_rule({'id':'timer','interval_s':60,'actions':[{'command':'screen.clear'}]}, lambda c,p:None)
    now[0] += 600
    store.close()
    store = EventStore(path, clock=lambda:now[0])
    assert store.due() == []
    now[0] += 61
    assert len(store.due()) == 1 and store.due() == []
    for i in range(220):
        store.emit('bounded', {'n':i})
        store.audit('screen.show', True, 1)
    assert store.db.execute('SELECT count(*) FROM events').fetchone()[0] == 200
    assert store.db.execute('SELECT count(*) FROM audit').fetchone()[0] == 200
    store.close()


def test_cast_names_survive_rediscovery_and_ambiguity(runtime):
    runtime.store.remember_devices([{'id':'a','name':'TV','host':'10.0.0.1'}, {'id':'b','name':'TV','host':'10.0.0.2'}])
    assert not runtime.run('tv.cast', {'action':'status','device':'TV'})['ok']
    runtime.run('cast.name', {'id':'a','name':'Living','room':'Lounge'})
    runtime.store.remember_devices([{'id':'a','name':'TV','host':'10.0.0.3'}])
    assert next(d for d in runtime.store.devices() if d['id']=='a')['alias'] == 'Living'
    seen=[]
    runtime.executor.run=lambda c,p,t: seen.append(p) or {'ok':True,'payload':{}}
    assert runtime.run('tv.cast', {'action':'status','device':'Lounge'})['ok']
    assert seen == [{'action':'status','host':'10.0.0.3'}]


@pytest.fixture
def http_server():
    requests=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            requests.append((self.path, self.headers.get('Authorization'), None))
            if self.path == '/api/states/redirect.test':
                self.send_response(302); self.send_header('Location','/leaked'); self.end_headers(); return
            self.send_response(200)
            if self.path == '/truncated': self.send_header('Content-Length','100')
            self.end_headers()
            self.wfile.write(b'test media' if self.path=='/media' else b'[{"entity_id":"light.test","state":"off"}]')
        def do_POST(self):
            body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            requests.append((self.path,self.headers.get('Authorization'),body))
            self.send_response(200); self.end_headers(); self.wfile.write(b'[]')
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    worker=threading.Thread(target=server.serve_forever,daemon=True); worker.start()
    yield 'http://127.0.0.1:'+str(server.server_port), requests
    server.shutdown(); server.server_close(); worker.join()


def test_real_ha_http_authorization_redirect_and_target_bounds(http_server):
    url, requests=http_server
    settings={'ha_url':url,'ha_token':'synthetic-test-token','ha_actions':False}
    ha=HomeAssistant(lambda:settings, lambda *a:None)
    assert ha.run('home.states',{})['states'][0]['state']=='off'
    with pytest.raises(ValueError, match='locally'):
        ha.run('home.call',{})
    settings['ha_actions']=True
    assert ha.run('home.call',{'domain':'light','service':'turn_on','entity_id':'light.test'})['status']=='accepted'
    assert requests[-1][1]=='Bearer synthetic-test-token'
    assert requests[-1][2]=={'entity_id':['light.test']}
    with pytest.raises(ValueError, match='redirect'):
        ha.run('home.states',{'entity_id':'redirect.test'})
    assert not any(x[0]=='/leaked' for x in requests)
    with pytest.raises(ValueError, match='targets'):
        ha.run('home.call',{'domain':'light','service':'turn_on','entity_id':'light.test','data':{'area_id':'all'}})


def test_real_media_cache_download_and_workspace_read(runtime,http_server):
    url,_=http_server
    result=runtime.run('media.cache',{'url':url+'/media','path':'offline/sample.bin'})
    assert result['ok'], result
    result=runtime.run('file.read',{'path':'offline/sample.bin'})
    assert base64.b64decode(result['payload']['data_b64'])==b'test media'
    assert not runtime.run('media.cache',{'url':url+'/media','path':'../secret'})['ok']


@pytest.mark.parametrize('url',['file:///etc/passwd','https://user:pass@host/x','javascript:1','http:///x'])
def test_url_protocol_boundary(url):
    with pytest.raises(ValueError): http_url(url)


def test_shell_real_timeout_and_bounded_dual_output(tmp_path, monkeypatch):
    import androidtv.compat as compat
    monkeypatch.setattr(compat,'ANDROID_SHELL','/bin/sh')
    ex=AndroidExecutor(android_account(str(tmp_path)))
    ex.developer_enabled=lambda:True
    assert not ex.system_run({'command':'echo x','timeout_ms':-1})['ok']
    result=ex.system_run({'command':'while :; do printf abcdefghijklmnopqrstuvwxyz; printf error >&2; done','timeout_ms':150})
    assert result['ok'] and result['payload']['timed_out'] and result['payload']['truncated']
    assert len(result['payload']['stdout']) <= 32768 and len(result['payload']['stderr'])<=32768
    assert result['payload']['duration_ms'] < 3000


def test_real_mqtt_protocol_ack_subscription_and_retained_messages():
    """A loopback MQTT 3.1.1 peer exercises paho's actual network callbacks."""
    import socket
    import struct
    import time
    from concurrent.futures import ThreadPoolExecutor
    listener=socket.socket(); listener.bind(('127.0.0.1',0)); listener.listen(1); listener.settimeout(5)
    received=[]
    def packet(sock):
        head=sock.recv(1)
        if not head: return None,b''
        length=0; mult=1
        while True:
            digit=sock.recv(1)[0]; length+=(digit&127)*mult
            if digit<128: break
            mult*=128
        body=b''
        while len(body)<length: body+=sock.recv(length-len(body))
        return head[0],body
    def broker():
        conn,_=listener.accept()
        with conn:
            conn.settimeout(5)
            head,body=packet(conn)
            assert head==0x10 and b'MQTT' in body
            conn.sendall(b'\x20\x02\x00\x00')
            head,body=packet(conn)
            assert head==0x82 and b'muse/#' in body
            conn.sendall(b'\x90\x03'+body[:2]+b'\x01')
            topic=b'muse/sensor'; payload=b'prior'
            data=struct.pack('!H',len(topic))+topic+payload
            conn.sendall(bytes([0x31,len(data)])+data)
            head,body=packet(conn)
            assert head==0x32
            n=int.from_bytes(body[:2],'big')
            assert body[2:2+n]==b'muse/test' and body[4+n:]==b'new'
            conn.sendall(b'\x40\x02'+body[2+n:4+n])
            packet(conn)  # disconnect
    settings={'mqtt_host':'127.0.0.1','mqtt_port':listener.getsockname()[1], 'mqtt_prefix':'muse','mqtt_publish':True}
    client=Mqtt(lambda:settings, lambda kind,data:received.append((kind,data)))
    with ThreadPoolExecutor(max_workers=1) as pool:
        task=pool.submit(broker)
        try:
            client.start()
            deadline=time.monotonic()+5
            while (not client.connected or not received) and time.monotonic()<deadline: time.sleep(.02)
            assert client.connected and received[0][1]['retained']
            assert client.run('mqtt.publish',{'topic':'muse/test','payload':'new'})['status']=='broker_acknowledged'
            with pytest.raises(ValueError,match='prefix'):
                client.run('mqtt.publish',{'topic':'other/test','payload':'new'})
        finally:
            client.close(); listener.close()
        task.result(timeout=5)


def test_home_assistant_real_websocket_auth_and_entity_filter():
    import asyncio
    from websockets.asyncio.server import serve
    async def scenario():
        messages=[]; done=asyncio.Event(); stop=asyncio.Event()
        async def peer(ws):
            await ws.send(json.dumps({'type':'auth_required'}))
            auth=json.loads(await ws.recv()); assert auth=={'type':'auth','access_token':'fixture-token'}
            await ws.send(json.dumps({'type':'auth_ok'}))
            assert json.loads(await ws.recv())['event_type']=='state_changed'
            await ws.send(json.dumps({'id':1,'success':True}))
            for entity in ('light.other','light.test'):
                await ws.send(json.dumps({'type':'event','event':{'data':{'entity_id':entity,'new_state':{'state':'on'}}}}))
            await done.wait()
        async with serve(peer,'127.0.0.1',0) as server:
            port=server.sockets[0].getsockname()[1]
            cfg={'ha_url':f'http://127.0.0.1:{port}','ha_token':'fixture-token','ha_events':True,'ha_entities':'light.test'}
            home=HomeAssistant(lambda:cfg,lambda kind,data:messages.append((kind,data)))
            task=asyncio.create_task(home.watch(stop))
            try:
                for _ in range(100):
                    if messages: break
                    await asyncio.sleep(.02)
                assert home.connected and messages==[('home.state',{'entity_id':'light.test','state':'on'})]
            finally:
                done.set(); stop.set(); task.cancel()
                await asyncio.gather(task,return_exceptions=True)
    asyncio.run(scenario())


def test_sdk_reporting_persists_across_restarts_without_extra_rotation(tmp_path,monkeypatch):
    import asyncio
    from types import SimpleNamespace
    from musegadget import config
    from musegadget.service import Service
    from androidtv.cloud import AndroidService, REPORT_FILE
    monkeypatch.setenv('MUSEGADGET_STATE_DIR',str(tmp_path))
    calls=[]
    async def refresh(self,pairing,force=False):
        calls.append(self._sdk_token_report_attempted)
        return {**pairing,'refresh_token':'rotated'}
    monkeypatch.setattr(Service,'_maybe_refresh',refresh)
    identity=SimpleNamespace(node_id='test-node')
    service=AndroidService(identity,executor=None,sdk_token='synthetic-test')
    asyncio.run(service._maybe_refresh({'refresh_token':'old'}))
    assert config.load_json(REPORT_FILE) and calls==[False]
    second=AndroidService(identity,executor=None,sdk_token='synthetic-test')
    asyncio.run(second._maybe_refresh({'refresh_token':'rotated'}))
    assert calls==[False,True]
    assert 'synthetic-test' not in (tmp_path/REPORT_FILE).read_text()


def test_pairing_token_private_atomic_and_reusable(tmp_path,monkeypatch):
    import stat
    from androidtv.pairing import save_sdk_token,get_sdk_token
    monkeypatch.delenv('MUSEGADGET_SDK_TOKEN',raising=False)
    monkeypatch.setenv('MUSEGADGET_STATE_DIR',str(tmp_path/'musegadget'))
    token='mgst_'+'A'*43
    save_sdk_token(str(tmp_path),token)
    path=tmp_path/'musegadget'/'sdk_token'
    assert stat.S_IMODE(path.stat().st_mode)==0o600
    assert get_sdk_token(str(tmp_path))==token
    with pytest.raises(ValueError):save_sdk_token(str(tmp_path),'invalid')
    assert get_sdk_token(str(tmp_path))==token
    assert not list(path.parent.glob('.sdk-token-*'))


def test_status_stays_readable_with_large_event_history(runtime):
    for i in range(50):
        runtime.store.emit('large', {'value':'x'*7900, 'index':i})
    result=runtime.run('device.status',{})
    assert result['ok'] and result['payload']['events']
    assert len(json.dumps(result)) < 220*1024


def test_incomplete_download_is_not_committed(runtime, http_server):
    url,_=http_server
    result=runtime.run('media.cache',{'url':url+'/truncated','path':'incomplete.bin'})
    assert not result['ok']
    assert not (runtime.executor.workspace.root/'incomplete.bin').exists()
    assert not list(runtime.executor.workspace.root.glob('.*musegadget-partial'))


@pytest.mark.parametrize('response,error', [
    ({'ok':True,'status':200},None),
    ({'ok':False,'status':400},'HTTP 400'),
    (TimeoutError('private response text'),'TimeoutError'),
])
def test_cloud_event_uuid_routing_and_delivery_diagnostics(tmp_path, monkeypatch, response, error):
    import asyncio
    import uuid
    from types import SimpleNamespace
    from androidtv.service import _observe
    from musegadget import config
    store=EventStore(tmp_path/'events.sqlite')
    event=store.emit('validation.event',{'message':'synthetic'},notify=True)
    sessions=[]
    async def scenario():
        stop=asyncio.Event()
        async def send_chat(message, session_id):
            assert str(uuid.UUID(session_id)) == session_id
            assert json.loads(message)['event_id'] == event['id']
            sessions.append(session_id)
            stop.set()
            if isinstance(response, Exception): raise response
            return response
        service=SimpleNamespace(identity=SimpleNamespace(node_id='synthetic-node'),_stop=stop,
            _current=SimpleNamespace(registered_at=1,send_chat=send_chat))
        runtime=SimpleNamespace(native=None,store=store,settings=lambda:{'notify_muse':True})
        monkeypatch.setattr(config,'load_json',lambda _: {'paired':True})
        await _observe(service,runtime)
        assert runtime.state['state']=='connected'
    try:
        asyncio.run(scenario())
        row=store.recent()[0]
        assert row['delivery'] == ('delivered' if error is None else 'queued')
        assert row['last_error'] == error
        assert 'private response text' not in json.dumps(row)
        # The same event keeps its ID and side-chat across a reconnect/retry.
        with store.db:
            store.db.execute("UPDATE events SET delivery='queued',next_try=0")
        asyncio.run(scenario())
        assert sessions[0] == sessions[1]
    finally: store.close()


def test_event_diagnostics_migrate_existing_database(tmp_path):
    import sqlite3
    path=tmp_path/'old.sqlite'
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE events (id TEXT PRIMARY KEY,type TEXT,data TEXT,created REAL,expires REAL,delivery TEXT,attempts INTEGER DEFAULT 0,next_try REAL DEFAULT 0)')
        db.execute("INSERT INTO events VALUES ('prior','validation.event','{}',1,9999999999,'queued',0,0)")
    store=EventStore(path)
    try:
        store.delivered('prior',False,'HTTP 400')
        assert store.recent()[0]['last_error']=='HTTP 400'
        store.delivered('prior',True)
        assert store.recent()[0]['delivery']=='delivered' and store.recent()[0]['last_error'] is None
    finally:store.close()
