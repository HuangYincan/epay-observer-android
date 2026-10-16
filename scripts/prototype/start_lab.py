#!/usr/bin/env python3
"""Start a new, private synthetic lab with prebuilt, explicitly supplied checkouts.

No host installations, public listeners, real accounts, or production resources.
Existing named containers and lab directories are never replaced.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request

GO = 'golang@sha256:37cfcca63222ec499bec5e8ad7aad52dbf7529db36d5812b8eae2f0621aa92d6'
PG = 'postgres@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d'
REDIS = 'redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499'
p = argparse.ArgumentParser()
p.add_argument('directory', type=Path)
p.add_argument('--gateway', type=Path, required=True)
p.add_argument('--new-api', type=Path, required=True)
p.add_argument('--prefix', default='epay-observer-demo')
a = p.parse_args()
if not re.fullmatch(r'epay-observer-[a-z0-9-]{1,30}', a.prefix): p.error('dedicated epay-observer-* prefix required')
root = Path(__file__).resolve().parents[2]
lab = a.directory.resolve()
if not str(lab).startswith(('/tmp/epay-observer-', '/private/tmp/epay-observer-')): p.error('private /tmp/epay-observer-* directory required')
if lab.exists(): p.error('lab directory already exists; choose a new directory')
for directory, binary in [(a.gateway, 'epay-observer-synthetic'), (a.new_api, 'new-api-observer')]:
    if not (directory / binary).is_file(): p.error('build required binary first: '+binary)
for suffix in ['runtime', 'postgres', 'redis', 'gateway', 'newapi']:
    if subprocess.run(['docker', 'inspect', a.prefix+'-'+suffix], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        p.error('named task container already exists: '+a.prefix+'-'+suffix)
os.umask(0o077)
subprocess.run(['python3', str(root/'scripts/prepare_pairing_tls.py'), str(lab)], check=True)
pg = secrets.token_hex(24); redis = secrets.token_hex(24)
accounts = {name: {'username': user, 'password': secrets.token_urlsafe(24)} for name, user in [('gateway_admin','observer_lab'), ('gateway_merchant','synthetic_lab'), ('newapi_root','observerlab')]}
(lab/'private-accounts.json').write_text(json.dumps(accounts))
(lab/'postgres.env').write_text(f'POSTGRES_USER=observer_lab\nPOSTGRES_PASSWORD={pg}\nPOSTGRES_DB=epay\n')
(lab/'redis.conf').write_text(f'bind 127.0.0.1\nport 6379\nrequirepass {redis}\nappendonly yes\ndir /data\n')
(lab/'observer-kek').write_text(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('='))
(lab/'gateway.env').write_text(f'DB_HOST=127.0.0.1\nDB_PORT=5432\nDB_USER=observer_lab\nDB_PASSWORD={pg}\nDB_NAME=epay\nDB_SSLMODE=disable\nREDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nREDIS_PASSWORD={redis}\nJWT_SECRET={secrets.token_hex(32)}\nGIN_MODE=release\nDEFAULT_ADMIN_USERNAME=observer_lab\nDEFAULT_ADMIN_PASSWORD={accounts["gateway_admin"]["password"]}\nOBSERVER_ENVIRONMENT={a.prefix}\nOBSERVER_SOURCE_CLASS=synthetic\nOBSERVER_ORIGIN=https://localhost:18443\nOBSERVER_KEK_ID=lab-kek-v1\nOBSERVER_KEK_FILE=/lab/observer-kek\nOBSERVER_TLS_CERT_FILE=/lab/server.pem\nOBSERVER_TLS_KEY_FILE=/lab/server.key\nOBSERVER_TLS_ADDRESS=:8443\nOBSERVER_SYNTHETIC_ENVIRONMENT={a.prefix}\nOBSERVER_SYNTHETIC_CALLBACK_URL=https://127.0.0.1:8444/api/user/epay/notify\nOBSERVER_SYNTHETIC_CALLBACK_CA_FILE=/lab/ca.pem\n')
(lab/'newapi.env').write_text(f'SQL_DSN=postgres://observer_lab:{pg}@127.0.0.1:5432/newapi?sslmode=disable\nSESSION_SECRET={secrets.token_hex(32)}\nCRYPTO_SECRET={secrets.token_hex(32)}\nPORT=3000\nTZ=UTC\n')
(lab/'config.yaml').write_text('server:\n  port: 8080\n  mode: release\ndatabase:\n  host: 127.0.0.1\n  port: 5432\n  user: observer_lab\n  password: from-env\n  dbname: epay\n  sslmode: disable\nredis:\n  host: 127.0.0.1\n  port: 6379\n  password: from-env\n  db: 0\njwt:\n  secret: from-env\n  expire_hour: 12\n')
meta = {'prefix':a.prefix, 'environment':a.prefix, 'gateway_database':'epay', 'newapi_database':'newapi', 'gateway':str(a.gateway.resolve()), 'new_api':str(a.new_api.resolve()), 'images':{'go':GO,'postgres':PG,'redis':REDIS}}
for name, directory in [('android',root),('gateway',a.gateway),('new_api',a.new_api)]:
    meta[name+'_commit'] = subprocess.check_output(['git','-C',str(directory),'rev-parse','HEAD'],text=True).strip()
(lab/'runtime.json').write_text(json.dumps(meta,indent=2))
def docker(*args, input=None):
    return subprocess.run(['docker', *map(str,args)],input=input,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
def run(suffix, args):
    docker('run','-d','--name',a.prefix+'-'+suffix,'--label','epay-observer.lab='+a.prefix,*args)
def wait_for(label, predicate):
    end=time.monotonic()+60
    while time.monotonic()<end:
        try:
            if predicate(): return
        except (OSError,subprocess.CalledProcessError): pass
        time.sleep(.5)
    raise RuntimeError(label+' did not become ready; inspect private task container logs')
docker('network','create','--label','epay-observer.lab='+a.prefix,a.prefix)
run('runtime',['--network',a.prefix,'-p','127.0.0.1:18443:8443','-p','127.0.0.1:18080:8080','-p','127.0.0.1:13000:3000','--mount',f'type=bind,source={lab},target=/lab',GO,'sleep','infinity'])
network=['--network','container:'+a.prefix+'-runtime']
run('postgres',network+['--env-file',str(lab/'postgres.env'),PG,'-c','listen_addresses=127.0.0.1'])
run('redis',network+['--mount',f'type=bind,source={lab}/redis.conf,target=/usr/local/etc/redis/redis.conf,readonly',REDIS,'redis-server','/usr/local/etc/redis/redis.conf'])
wait_for('PostgreSQL',lambda:docker('exec',a.prefix+'-postgres','pg_isready','-U','observer_lab').returncode==0)
docker('exec',a.prefix+'-postgres','createdb','-U','observer_lab','newapi')
gateway_args=network+['--env-file',str(lab/'gateway.env'),'--mount',f'type=bind,source={a.gateway.resolve()},target=/gateway,readonly','--mount',f'type=bind,source={lab},target=/lab','--workdir','/lab',GO,'/gateway/epay-observer-synthetic']
docker('run','--rm',*gateway_args,'migrate')
sql=f"INSERT INTO observer_environments(environment,source_class,enabled) VALUES ('{a.prefix}','synthetic',true); INSERT INTO observer_receivers(environment,receiver_id,provider,account_ref,enabled) VALUES ('{a.prefix}','lab-receiver-wx','wxpay','synthetic-wx',true),('{a.prefix}','lab-receiver-ali','alipay','synthetic-ali',true);"
docker('exec','-i',a.prefix+'-postgres','psql','-v','ON_ERROR_STOP=1','-U','observer_lab','-d','epay',input=sql.encode())
run('gateway',gateway_args)
run('newapi',network+['--env-file',str(lab/'newapi.env'),'--mount',f'type=bind,source={a.new_api.resolve()},target=/source,readonly','--mount',f'type=bind,source={lab},target=/lab','--workdir','/lab',GO,'/source/new-api-observer'])
docker('cp',root/'scripts/prototype/callback_proxy.go',a.prefix+'-runtime:/tmp/callback_proxy.go')
docker('exec',a.prefix+'-runtime','go','build','-o','/lab/callback-proxy','/tmp/callback_proxy.go')
docker('exec','-d',a.prefix+'-runtime','/lab/callback-proxy','--lab-dir','/lab')
wait_for('gateway',lambda:urllib.request.urlopen('http://127.0.0.1:18080/health',timeout=3).status==200)
wait_for('New API',lambda:urllib.request.urlopen('http://127.0.0.1:13000/api/setup',timeout=3).status==200)
print('Private synthetic lab running; configure with configure_lab.py. No credentials printed.')
