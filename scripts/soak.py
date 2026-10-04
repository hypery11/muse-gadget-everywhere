#!/usr/bin/env python3
"""Read-only device soak recorder: PID, PSS, and process presence.

Start the gadget service first. This never sends cloud commands or rotates tokens.
Usage: python3 scripts/soak.py SERIAL --minutes 30 --output soak.jsonl
"""
import argparse
import datetime
import json
import re
import subprocess
import time

parser=argparse.ArgumentParser()
parser.add_argument('serial'); parser.add_argument('--minutes',type=float,default=30)
parser.add_argument('--output',default='soak.jsonl')
args=parser.parse_args()
def adb(*parts):
    return subprocess.run(['adb','-s',args.serial,*parts],capture_output=True,text=True,timeout=20).stdout.strip()
end=time.monotonic()+args.minutes*60
with open(args.output,'w') as output:
    while True:
        pid=adb('shell','pidof','ai.muse.gadgeteverywhere')
        memory=adb('shell','dumpsys','meminfo','ai.muse.gadgeteverywhere') if pid else ''
        match=re.search(r'TOTAL PSS:\s+(\d+)',memory) or re.search(r'^\s*TOTAL\s+(\d+)',memory,re.M)
        row={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'pid':pid,'pss_kib':int(match[1]) if match else None}
        output.write(json.dumps(row)+'\n'); output.flush(); print(json.dumps(row),flush=True)
        if not pid:raise SystemExit('App process is absent; inspect device logs before restarting.')
        remaining=end-time.monotonic()
        if remaining<=0:break
        time.sleep(min(30,remaining))
