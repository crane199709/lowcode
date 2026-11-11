#!/usr/bin/env python3
"""Lowcode 自有的真实平台验收：隔离依赖、独立凭据、真实 UI/MQ/Nacos/S3。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--platform-root', type=Path, default=Path('/Users/liuhuan/workspace/coding/saas-forge'))
parser.add_argument('--serve', action='store_true', help='保持本轮隔离 fixture，供另一个终端迭代浏览器用例；Ctrl-C 仅清理本轮资源')
parser.add_argument('--skip-build', action='store_true', help='仅复用本轮已从当前源码构建的产物')
args = parser.parse_args()
PLATFORM = args.platform_root.resolve()
os.umask(0o077)
for tool in ['docker','pnpm','python3','/usr/sbin/htpasswd']:
    if not shutil.which(tool): raise SystemExit('缺少依赖工具：'+tool)
java_home = os.environ.get('JAVA_HOME')
if not java_home or not (Path(java_home)/'bin/java').is_file():
    raise SystemExit('请显式设置有效的 JAVA_HOME（Java 17）')
for port in [15432, 16379, 18081, 19000, 18848, 19848, 18101, 18102, 18202, 18000, 15173, 15174, 15176]:
    with socket.socket() as s:
        try:
            s.bind(('127.0.0.1', port))
        except OSError:
            raise SystemExit(f'隔离端口 {port} 已占用；请释放测试端口后重试，本脚本不会停止现有服务')
RUN = Path(tempfile.mkdtemp(prefix='lowcode-platform-', dir='/private/tmp'))
print(f'私有运行目录：{RUN}', flush=True)
PIDS = []
(RUN / 'runner.pid').write_text(str(os.getpid()))
def stop_fixture(signum, frame):
    raise SystemExit(130 if signum == signal.SIGINT else 143)
signal.signal(signal.SIGTERM, stop_fixture)
signal.signal(signal.SIGINT, stop_fixture)
COMPOSE = ['docker', 'compose', '-p', RUN.name, '-f', str(RUN / 'compose.json')]
C = {key: secrets.token_hex(24) for key in ['postgres', 'operatorPassword', 'memberPassword', 'externalPassword', 's3User', 's3Secret', 'nacosAdmin', 'nacosIdentity', 'nacosIdentityValue']}
for svc in ['iam', 'platform', 'lowcode', 'demo']:
    for kind in ['App', 'Migrator', 'Ops', 'Manifest']:
        C[svc + kind] = secrets.token_hex(24)
for key in ['operatorPassword','memberPassword']:
    C[key] = 'Lowcode-' + C[key] + '!9a'
C['aes'] = base64.b64encode(secrets.token_bytes(32)).decode()
C['mfa'] = base64.b64encode(secrets.token_bytes(32)).decode()
C['nacosToken'] = base64.b64encode(secrets.token_bytes(48)).decode()
C.update(operatorAccount='lowcode-e2e-operator', externalUser='external_reader', runDir=str(RUN), platformRoot=str(PLATFORM))
(RUN / 'credentials.json').write_text(json.dumps(C))

def write(name, value):
    (RUN / name).parent.mkdir(parents=True, exist_ok=True)
    (RUN / name).write_text(json.dumps(value, indent=2) if not isinstance(value, str) else value)

def command(argv, log, *, input=None, env=None, cwd=None, capture=False):
    with (RUN / log).open('a') as out:
        result = subprocess.run(argv, input=input, text=True, stdout=subprocess.PIPE if capture else out,
                                stderr=out, env=env, cwd=cwd)
    if result.returncode:
        raise RuntimeError(f'{log} 阶段失败（原始日志私有，可能包含本轮密钥）')
    return result.stdout if capture else None

def request(path, data=None, headers=None):
    req = urllib.request.Request(path, data=urllib.parse.urlencode(data).encode() if data is not None else None, headers=headers or {})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)

def wait(fn, label, seconds=180):
    for _ in range(seconds):
        try:
            value = fn()
            if value:
                return value
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(1)
    raise RuntimeError(f'{label} 超时')

def pg(sql, db='forge', capture=False):
    return command(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-X', '-U', 'postgres', '-d', db, '-v', 'ON_ERROR_STOP=1', '-At'], 'postgres.log', input=sql, capture=capture)

# 只有配置文件和脚本的只读挂载；数据库、对象存储与 Nacos 均使用新建 project 卷。
services = {
    'postgres': {
        'image': 'postgres:17-alpine',
        'environment': {'POSTGRES_DB':'forge','POSTGRES_PASSWORD':C['postgres']},
        'ports': ['127.0.0.1:15432:5432'],
        'volumes': ['postgres-data:/var/lib/postgresql/data'],
        'healthcheck': {'test':['CMD-SHELL','pg_isready -U postgres -d forge'],'interval':'2s','timeout':'3s','retries':40},
    },
    'redis': {
        'image': 'redis:8.8.1',
        'ports': ['127.0.0.1:16379:6379'],
        'healthcheck': {'test':['CMD','redis-cli','ping'],'interval':'2s','timeout':'3s','retries':30},
    },
    'rocketmq-namesrv': {
        'image': 'apache/rocketmq:5.3.2',
        'command': ['sh','mqnamesrv'],
        'environment': {'JAVA_OPT_EXT':'-Xms128m -Xmx128m'},
    },
    'rocketmq-broker': {
        'image': 'apache/rocketmq:5.3.2',
        'command': ['sh','mqbroker','-n','rocketmq-namesrv:9876','-c','/home/rocketmq/conf/forge-broker.conf'],
        'depends_on': ['rocketmq-namesrv'],
        'environment': {'JAVA_OPT_EXT':'-Xms256m -Xmx256m'},
        'volumes': [str(PLATFORM/'deploy/rocketmq/broker.conf')+':/home/rocketmq/conf/forge-broker.conf:ro'],
    },
    'rocketmq-proxy': {
        'image': 'apache/rocketmq:5.3.2',
        'command': ['sh','mqproxy','-n','rocketmq-namesrv:9876','-pc','/home/rocketmq/conf/forge-proxy.json'],
        'depends_on': ['rocketmq-broker'],
        'restart': 'on-failure',
        'environment': {'JAVA_OPT_EXT':'-Xms128m -Xmx256m'},
        'ports': ['127.0.0.1:18081:8081'],
        'volumes': [str(PLATFORM/'deploy/rocketmq/proxy.json')+':/home/rocketmq/conf/forge-proxy.json:ro'],
    },
    'rocketmq-init': {
        'image': 'apache/rocketmq:5.3.2',
        'depends_on': ['rocketmq-broker'],
        'environment': {'CONSUMER_GROUPS':'forge-iam forge-platform lowcode-service'},
        'volumes': [str(PLATFORM/'deploy/rocketmq/create-topics.sh')+':/home/rocketmq/create-topics.sh:ro'],
        'entrypoint': ['sh','/home/rocketmq/create-topics.sh'],
    },
    's3': {
        'image': 'pgsty/silo:RELEASE.2026-10-18T00-00-00Z',
        'command': ['silo','server','/data'],
        'environment': {'MINIO_ROOT_USER':C['s3User'],'MINIO_ROOT_PASSWORD':C['s3Secret']},
        'ports': ['127.0.0.1:19000:9000'],
        'volumes': ['s3-data:/data'],
        'healthcheck': {
            'test': ['CMD','curl','-sf','http://localhost:9000/minio/health/live'],
            'interval': '3s',
            'timeout': '3s',
            'retries': 40,
        },
    },
    's3-init': {
        'image': 'pgsty/silo:RELEASE.2026-10-18T00-00-00Z',
        'environment': {'S3_USER':C['s3User'],'S3_SECRET':C['s3Secret']},
        'volumes': [str(RUN/'cors.xml')+':/cors.xml:ro'],
        'entrypoint': [
            'sh',
            '-c',
            'mc alias set local http://s3:9000 "$$S3_USER" "$$S3_SECRET" >/dev/null && mc mb local/lowcode-e2e && mc cors set local/lowcode-e2e /cors.xml',
        ],
    },
    'nacos': {
        'image': 'nacos/nacos-server:v3.1.1',
        'environment': {
            'MODE': 'standalone',
            'NACOS_AUTH_ENABLE': 'false',
            'NACOS_AUTH_ADMIN_ENABLE': 'true',
            'NACOS_AUTH_CONSOLE_ENABLE': 'true',
            'NACOS_AUTH_TOKEN': C['nacosToken'],
            'NACOS_AUTH_IDENTITY_KEY': C['nacosIdentity'],
            'NACOS_AUTH_IDENTITY_VALUE': C['nacosIdentityValue'],
            'JVM_XMS': '256m',
            'JVM_XMX': '512m',
        },
        'ports': ['127.0.0.1:18848:8848','127.0.0.1:19848:9848'],
        'volumes': ['nacos-data:/home/nacos/data'],
        'healthcheck': {
            'test': ['CMD-SHELL','curl -sf http://localhost:8848/nacos/v3/admin/core/state/liveness || exit 1'],
            'interval': '5s',
            'timeout': '3s',
            'retries': 50,
        },
    },
}
write('compose.json', {'services': services,'volumes':{name:{} for name in ['postgres-data','s3-data','nacos-data']}})
write('cors.xml','<CORSConfiguration><CORSRule><AllowedOrigin>http://127.0.0.1:15174</AllowedOrigin><AllowedMethod>PUT</AllowedMethod><AllowedMethod>GET</AllowedMethod><AllowedMethod>HEAD</AllowedMethod><AllowedHeader>*</AllowedHeader><ExposeHeader>ETag</ExposeHeader><MaxAgeSeconds>60</MaxAgeSeconds></CORSRule></CORSConfiguration>')
common = {
    'spring.cloud.refresh.never-refreshable': 'com.zaxxer.hikari.HikariDataSource,io.saasforge.sdk.tenant.TenantSessionDataSource',
    'server.address': '127.0.0.1',
    'server.forward-headers-strategy': 'framework',
    'forge.business-zone': 'Asia/Shanghai',
    'forge.security.issuer': 'http://127.0.0.1:18000/iam',
    'forge.security.jwk-set-uri': 'http://127.0.0.1:18101/oauth2/jwks',
    'forge.security.platform.issuer': 'http://127.0.0.1:18000/iam/platform',
    'forge.security.platform.jwk-set-uri': 'http://127.0.0.1:18101/platform/oauth2/jwks',
    'forge.mq.enabled': True,
    'forge.mq.endpoints': '127.0.0.1:18081',
    'forge.mq.ssl': False,
    'forge.manifest.iam-uri': 'http://127.0.0.1:18101',
    'forge.reference-data.iam-uri': 'http://127.0.0.1:18101',
    'forge.platform.iam.uri': 'http://127.0.0.1:18101',
    'spring.data.redis.host': '127.0.0.1',
    'spring.data.redis.port': 16379,
}
gateway = {
    'spring': {
        'cloud': {
            'gateway': {
                'server': {
                    'webflux': {
                        'trusted-proxies': '127\\.0\\.0\\.1|0:0:0:0:0:0:0:1',
                        'routes': [{'id':svc,'uri':'lb://'+svc,'predicates':['Path=/'+prefix+'/**'],'filters':['StripPrefix=1']} for svc,prefix in [('forge-iam','iam'),('forge-platform','platform'),('lowcode-service','lowcode')]],
                    },
                },
            },
        },
    },
    'forge': {
        'gateway': {
            'services': [{'prefix':'/'+p,'realm':r} for p,r in [('iam','tenant'),('platform','platform'),('lowcode','tenant')]],
            'rate-limit': {'default-qps':100},
        },
    },
}
write('nacos-config/forge-common.yaml', common)
write('nacos-config/forge-gateway.yaml', gateway)
base = {
    'spring.cloud.nacos.server-addr': '127.0.0.1:18848',
    'spring.cloud.nacos.config.server-addr': '127.0.0.1:18848',
    'spring.cloud.nacos.config.enabled': True,
    'spring.cloud.nacos.config.namespace': '',
    'spring.cloud.nacos.config.group': 'DEFAULT_GROUP',
    'spring.cloud.nacos.discovery.enabled': True,
    'spring.cloud.nacos.discovery.server-addr': '127.0.0.1:18848',
    'spring.cloud.nacos.discovery.namespace': '',
    'spring.cloud.nacos.discovery.group': 'DEFAULT_GROUP',
    'spring.cloud.nacos.discovery.ip': '127.0.0.1',
    'spring.cloud.nacos.discovery.naming-load-cache-at-start': False,
    'forge.iam.redirect-uri': 'http://127.0.0.1:15174/',
    'forge.iam.platform.redirect-uri': 'http://127.0.0.1:15173/',
    'forge.iam.invitation.accept-url': 'http://127.0.0.1:15174/invitations/accept',
    'forge.iam.sub-app.environment': 'e2e',
    'forge.iam.mfa.encryption-key': C['mfa'],
    'forge.iam.signing-key-file': str(RUN/'tenant.jwk'),
    'forge.iam.platform.signing-key-file': str(RUN/'platform.jwk'),
    'forge.platform.provisioning.services': ['forge-iam','lowcode-service'],
    'forge.platform.iam.token': C['platformManifest'],
}
for svc in ['iam','platform','lowcode']:
    base['forge.platform.ops.tokens.'+('lowcode-service' if svc=='lowcode' else 'forge-'+svc)] = C[svc+'Ops']
for svc in ['platform','lowcode','demo']:
    base['forge.iam.manifest.tokens.'+(svc+'-service' if svc!='platform' else 'forge-platform')] = C[svc+'Manifest']
write('application.yml', base)

def start(svc, port, probe):
    key = 'lowcode' if svc=='lowcode-service' else svc.removeprefix('forge-')
    jar = ROOT/'lowcode-service/target/lowcode-service-1.0.0-SNAPSHOT.jar' if key=='lowcode' else PLATFORM/f'backend/{svc}/target/{svc}-0.2.0-SNAPSHOT.jar'
    for folder in ['java-home','nacos-cache','nacos-logs']:
        (RUN/folder/svc).mkdir(parents=True)
    env = {
        'PATH': os.environ['PATH'],
        'HOME': str(RUN/'java-home'/svc),
        'FORGE_DB_HOST': '127.0.0.1',
        'FORGE_DB_PORT': '15432',
        'FORGE_NACOS_ADDR': '127.0.0.1:18848',
        'FORGE_REDIS_HOST': '127.0.0.1',
        'FORGE_REDIS_PORT': '16379',
        'FORGE_ROCKETMQ_ENDPOINTS': '127.0.0.1:18081',
        'FORGE_IAM_OPS_TOKEN': C['iamOps'],
        'FORGE_PLATFORM_OPS_TOKEN': C['platformOps'],
        'FORGE_DEMO_OPS_TOKEN': C['demoOps'],
        'FORGE_LOWCODE_MANIFEST_TOKEN': C['lowcodeManifest'],
        'FORGE_PLATFORM_MANIFEST_TOKEN': C['platformManifest'],
        'FORGE_DEMO_MANIFEST_TOKEN': C['demoManifest'],
        'FORGE_DB_URL': 'jdbc:postgresql://127.0.0.1:15432/forge',
        'FORGE_DB_USER': 'lowcode_app',
        'FORGE_DB_PASSWORD': C['lowcodeApp'],
        'FORGE_DB_MIGRATOR_USER': 'lowcode_migrator',
        'FORGE_DB_MIGRATOR_PASSWORD': C['lowcodeMigrator'],
        'FORGE_DB_SCHEMA': 'lowcode',
        'FORGE_SERVICE_PORT': str(port),
        'FORGE_OPS_TOKEN': C['lowcodeOps'],
        'FORGE_SECURITY_ISSUER': 'http://127.0.0.1:18000/iam',
        'FORGE_SECURITY_JWK_SET_URI': 'http://127.0.0.1:18101/oauth2/jwks',
        'FORGE_IAM_URI': 'http://127.0.0.1:18101',
        'FORGE_MANIFEST_TOKEN': C['lowcodeManifest'],
        'LOWCODE_DATASOURCE_BLOCKED_NETWORKS': '169.254.0.0/16',
        'LOWCODE_DATASOURCE_ENCRYPTION_KEY': C['aes'],
        'FORGE_STORAGE_ENDPOINT': 'http://127.0.0.1:19000',
        'FORGE_STORAGE_BUCKET': 'lowcode-e2e',
        'FORGE_STORAGE_ACCESS_KEY': C['s3User'],
        'FORGE_STORAGE_SECRET_KEY': C['s3Secret'],
        'SPRING_CONFIG_IMPORT': 'nacos:forge-common.yaml?group=DEFAULT_GROUP&preference=remote'+(',nacos:forge-gateway.yaml?group=DEFAULT_GROUP&preference=remote' if key=='gateway' else ''),
    }
    if key != 'lowcode':
        env.pop('FORGE_MANIFEST_TOKEN')
        env.pop('FORGE_OPS_TOKEN')
    for name in ['iam','platform']:
        env[f'FORGE_{name.upper()}_DB_PASSWORD']=C[name+'App']
        env[f'FORGE_{name.upper()}_DB_MIGRATOR_PASSWORD']=C[name+'Migrator']
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    cmd=[java,'-Xms64m','-Xmx256m','-XX:ActiveProcessorCount=2',f'-Duser.home={RUN}/java-home/{svc}',f'-DJM.SNAPSHOT.PATH={RUN}/nacos-cache/{svc}',f'-DJM.LOG.PATH={RUN}/nacos-logs/{svc}','-jar',str(jar),f'--spring.config.additional-location=file:{RUN}/application.yml',f'--server.port={port}','--spring.cloud.nacos.discovery.ip=127.0.0.1']
    out=(RUN/f'{svc}.log').open('w')
    process=subprocess.Popen(cmd,env=env,stdout=out,stderr=subprocess.STDOUT,cwd=RUN)
    PIDS.append(process)
    def ready():
        if process.poll() is not None:
            raise RuntimeError(f'{svc} 已退出，见私有日志')
        return request(f'http://127.0.0.1:{port}{probe}')
    wait(ready,svc,240)
    print(f'{svc} ready ({port})',flush=True)

try:
    if not args.skip_build:
        command(['./mvnw','-o','-pl','forge-iam,forge-platform,forge-gateway,forge-sdk/forge-sdk-storage','-am','install','-DskipTests'],'build-platform.log',cwd=PLATFORM/'backend')
        command(['pnpm','-r','--filter','@forge/tenant-app...','--filter','@forge/platform-console...','build'],'build-platform-frontend.log',cwd=PLATFORM/'frontend')
        command(['./mvnw','-o','-DskipTests','package'],'build-lowcode.log',cwd=ROOT/'lowcode-service')
        command(['pnpm','build'],'build-frontend.log',cwd=ROOT/'lowcode-sub-app')
    command(COMPOSE+['up','-d','--wait','postgres','redis','s3','nacos'],'dependencies.log')
    command(COMPOSE+['up','-d','rocketmq-namesrv','rocketmq-broker','rocketmq-proxy'],'dependencies.log')
    command(COMPOSE+['run','--rm','rocketmq-init'],'rocketmq-init.log')
    command(COMPOSE+['run','--rm','s3-init'],'s3-init.log')
    pg('REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE SCHEMA extensions; CREATE EXTENSION ltree SCHEMA extensions;')
    provision=(PLATFORM/'deploy/postgres/init/lib/provision-service.sql').read_text()
    for svc in ['iam','platform','lowcode']:
        pg(f"\\set service {svc}\n\\set migrator_password {C[svc+'Migrator']}\n\\set app_password {C[svc+'App']}\n"+provision)
    pg(f"CREATE ROLE external_reader LOGIN PASSWORD '{C['externalPassword']}'; CREATE DATABASE external_fixture;")
    pg('CREATE TABLE sales(region VARCHAR(40), amount NUMERIC(12,2)); INSERT INTO sales VALUES (\'华东\',120),(\'华东\',80),(\'华南\',60); GRANT CONNECT ON DATABASE external_fixture TO external_reader; GRANT USAGE ON SCHEMA public TO external_reader; GRANT SELECT ON sales TO external_reader;',db='external_fixture')
    request('http://127.0.0.1:18848/nacos/v3/auth/user/admin',{'password':C['nacosAdmin']})
    login=wait(lambda:request('http://127.0.0.1:18848/nacos/v3/auth/user/login',{'username':'nacos','password':C['nacosAdmin']}),'Nacos admin')
    headers={'accessToken':login.get('accessToken') or login['data']['accessToken']}
    hashes={}
    for name in ['forge-common.yaml','forge-gateway.yaml']:
        content=(RUN/'nacos-config'/name).read_text()
        published=request('http://127.0.0.1:18848/nacos/v3/admin/cs/config',{'dataId':name,'groupName':'DEFAULT_GROUP','type':'yaml','content':content},headers)
        assert published['code']==0, 'Nacos config publish failed'
        read=request('http://127.0.0.1:18848/nacos/v3/admin/cs/config?'+urllib.parse.urlencode({'namespaceId':'public','groupName':'DEFAULT_GROUP','dataId':name}),headers=headers)
        assert read['data']['content']==content,'Nacos config readback mismatch'
        hashes[name]=hashlib.sha256(content.encode()).hexdigest()
    write('nacos-config-digests.json',hashes)
    start('forge-iam',18101,'/oauth2/jwks')
    hashed=command(['/usr/sbin/htpasswd','-niB','lowcode'], 'password-hash.log', input=C['operatorPassword']+'\n',capture=True).strip().split(':',1)[1]
    pg(f"INSERT INTO iam.operator(id,account,display_name,password_hash) VALUES ('019a2525-0000-7000-8000-000000000061','lowcode-e2e-operator','Lowcode 验收运营','{hashed}'); INSERT INTO iam.operator_role(operator_id,role_id) SELECT '019a2525-0000-7000-8000-000000000061',id FROM iam.platform_role WHERE code='super-admin';")
    start('lowcode-service',18202,'/actuator/health')
    start('forge-platform',18102,'/actuator/health')
    start('forge-gateway',18000,'/actuator/health')
    instances={}
    for svc,port in [('forge-iam',18101),('forge-platform',18102),('lowcode-service',18202),('forge-gateway',18000)]:
        url='http://127.0.0.1:18848/nacos/v3/admin/ns/instance/list?'+urllib.parse.urlencode({'namespaceId':'public','groupName':'DEFAULT_GROUP','serviceName':svc,'healthyOnly':'true'})
        data=wait(lambda:request(url,headers=headers).get('data'),'Nacos '+svc)
        assert len(data)==1 and data[0]['ip']=='127.0.0.1' and data[0]['port']==port and data[0]['healthy'] and data[0]['enabled'], 'Nacos instance mismatch'
        instances[svc]={'ip':data[0]['ip'],'port':port,'healthy':True}
    write('nacos-instances.json',instances)
    assert 'keys' in request('http://127.0.0.1:18000/iam/oauth2/jwks')
    document = request('http://127.0.0.1:18000/lowcode/v3/api-docs')
    assert 'openapi' in document and any('/lowcode' in x['url'] for x in document.get('servers', [])), 'OpenAPI external prefix missing'
    write('gateway-probes.json', {'anonymousJwks': True, 'openApiPrefix': [x['url'] for x in document['servers']]})
    manifest_headers = {'Authorization':'Bearer '+C['lowcodeManifest']}
    directory = request('http://127.0.0.1:18101/internal/permissions',headers=manifest_headers)
    assert all(code in json.dumps(directory) for code in ['datasource:view','datasource:manage','dataset:view','dataset:manage','screen:view','screen:edit','screen:publish']), 'Manifest permission catalogue missing'
    bad = urllib.request.Request('http://127.0.0.1:18101/internal/manifests', data=json.dumps({'service':'lowcode-service','version':'auth-probe','permissions':[],'menus':[],'roles':[],'dataResources':[]}).encode(), headers={'Authorization':'Bearer invalid','Content-Type':'application/json'})
    try:
        urllib.request.urlopen(bad)
        raise AssertionError('wrong Manifest token accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 401
    for path,secret,status in [('/internal/ops',C['lowcodeOps'],200),('/internal/ops','invalid',401)]:
        req=urllib.request.Request('http://127.0.0.1:18202'+path,headers={'Authorization':'Bearer '+secret})
        try:
            with urllib.request.urlopen(req) as response: actual=response.status
        except urllib.error.HTTPError as error: actual=error.code
        assert actual==status, f'ops expected {status} got {actual}'
    db_evidence = pg("SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname IN ('lowcode_app','lowcode_migrator') ORDER BY rolname; SELECT nspname,pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='lowcode'; SELECT DISTINCT installed_by FROM lowcode.flyway_schema_history;",capture=True)
    write('database-accounts.txt',db_evidence)
    assert 'lowcode_app|f|f' in db_evidence and 'lowcode_migrator|f|f' in db_evidence and 'lowcode|lowcode_migrator' in db_evidence
    pg(f"\\set service lowcode\n\\set migrator_password {C['lowcodeMigrator']}\n\\set app_password {C['lowcodeApp']}\n"+provision)
    for statement in ['CREATE TABLE lowcode.e2e_forbidden(id INTEGER);','SELECT * FROM iam.operator;']:
        result = subprocess.run(COMPOSE+['exec','-T','-e','PGPASSWORD='+C['lowcodeApp'],'postgres','psql','-X','-h','127.0.0.1','-U','lowcode_app','-d','forge','-v','ON_ERROR_STOP=1'],input=statement,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
        assert result.returncode != 0 and 'permission denied' in result.stderr, 'runtime DDL/schema boundary failed'
    if args.serve:
        print('隔离 fixture ready；Ctrl-C 清理本轮资源',flush=True)
        while True: time.sleep(1)
    env={**os.environ,'LOWCODE_E2E_RUN_DIR':str(RUN),'LOWCODE_PLATFORM_ROOT':str(PLATFORM)}
    command(['pnpm','exec','playwright','test','-c','e2e/platform/playwright.config.ts'],'playwright.log',env=env,cwd=ROOT/'lowcode-sub-app')
    print('真实平台链路通过',flush=True)
except Exception as error:
    print(str(error),flush=True)
    raise SystemExit(1)
finally:
    for process in reversed(PIDS):
        if process.poll() is None: process.terminate()
    for process in PIDS:
        try: process.wait(timeout=20)
        except subprocess.TimeoutExpired: process.kill()
    command(COMPOSE+['down','--volumes','--remove-orphans'],'cleanup.log')
    print(f'仅本轮 PID/Compose project 已清理，私有证据：{RUN}',flush=True)
