"""Exercise the packaged image using disposable Compose volumes; never accesses a VPS."""
import json,subprocess,tempfile,uuid,shutil
from pathlib import Path

repo=Path(__file__).resolve().parents[3]
(repo/'.local-build').mkdir(exist_ok=True)
version=json.loads((repo/'vps/package.json').read_text(encoding='utf-8'))['version']
project='peygir-smoke-'+uuid.uuid4().hex[:10]
data=project+'_data'; backups=project+'_backups'
root=Path(tempfile.mkdtemp(prefix='server-docker-',dir=repo/'.local-build'))
def run(*args,input=None,ok=True):
    result=subprocess.run(['docker',*args],input=input,text=True,capture_output=True)
    if ok and result.returncode: raise RuntimeError(result.stderr+result.stdout)
    return result
private=root/'.device-private/vps';private.mkdir(parents=True)
(root/'vps').mkdir()
for name in ['compose.yaml','compose.nginx.yaml']:
    shutil.copyfile(repo/'vps'/name,root/'vps'/name)
(private/'compose.env').write_text(f'PEYGIR_DOMAIN=peygir.test\nPEYGIR_DATA_VOLUME={data}\nPEYGIR_BACKUP_VOLUME={backups}\n')
for name,value in [('admin-bootstrap.json',{'username':'owner','password':'synthetic-password-12345'}),('fcm-service-account.json',{}),('fcm-public.json',{})]:
    (private/name).write_text(json.dumps(value))
override=private/'deployed-api.yaml'
override.write_text(f'name: {project}\nservices:\n  api:\n    image: peygir-api:automation-check\n    pull_policy: never\n    build: !reset null\n    ports: !reset []\n')
compose=['compose','--project-directory',str(root/'vps'),'--env-file',str(private/'compose.env'),'-p',project,'-f',str(root/'vps/compose.nginx.yaml'),'-f',str(override)]
def cli(*args,**kw):return run(*compose,*args,**kw)
try:
    config=json.loads(cli('config','--format','json').stdout)
    assert not config['services']['api'].get('build')
    assert config['volumes']['data']['name']==data
    cli('up','-d','--no-build','--wait','--wait-timeout','90','api')
    container=cli('ps','-q','api').stdout.strip()
    assert run('exec',container,'node','/app/vps/scripts/verify-upgrade.mjs',ok=False).returncode
    cli('stop','api')
    base=['run','--rm','--network','none','--read-only','--tmpfs','/tmp','--cap-drop','ALL','--security-opt','no-new-privileges:true','--user','node','--volumes-from',container,'--entrypoint','node','peygir-api:automation-check']
    seed="""import {Registry} from './vps/src/registry.mjs';import {writeFileSync} from 'node:fs';const r=new Registry('/data');await r.create({code:'smoke-company',name:'Smoke',managerName:'Manager',managerPassword:'synthetic-manager-12345'});r.close();writeFileSync('/data/automation-sentinel.txt','existing update files preserved');"""
    run(*base,'--input-type=module','-e',seed)
    backup=run(*base,'/app/vps/scripts/backup.mjs').stdout.strip()
    assert backup.startswith('/backups/')
    assert '1 companies' in run(*base,'/app/vps/scripts/verify-upgrade.mjs').stdout
    cli('up','-d','--no-build','--wait','--wait-timeout','90','api')
    container=cli('ps','-q','api').stdout.strip()
    check="""import {readFileSync} from 'node:fs';const h=await (await fetch('http://127.0.0.1:3000/health')).json();if(h.version!==process.argv[1])throw Error('version');if(readFileSync('/data/automation-sentinel.txt','utf8')!=='existing update files preserved')throw Error('data');console.log(JSON.stringify(h));"""
    health=json.loads(run('exec',container,'node','-e',check,version).stdout)
    result={'composeReset':True,'namedVolumesPreserved':True,'readOnlyImage':True,'runningInstanceRejected':True,'offlineBackup':True,'offlineMigrations':True,'restartHealth':health,'sentinelPreserved':True,'productionTouched':False}
    (repo/'verification/server-automation-docker.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))
finally:
    assert project.startswith('peygir-smoke-') and data==project+'_data' and backups==project+'_backups'
    cli('down','--volumes',ok=False)
