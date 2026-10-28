import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,mkdirSync,copyFileSync,readFileSync,statSync,chmodSync,linkSync,rmSync} from 'node:fs';
import {join,resolve,sep} from 'node:path';
import {spawnSync} from 'node:child_process';

const testRoot=resolve(import.meta.dirname,'../../.local-build/init-tests');
mkdirSync(testRoot,{recursive:true});
function fixture(t) {
  const root=mkdtempSync(join(testRoot,'case-'));
  const scripts=join(root,'vps','scripts');mkdirSync(scripts,{recursive:true});
  const script=join(scripts,'init.mjs');copyFileSync(new URL('../scripts/init.mjs',import.meta.url),script);
  t.after(()=>{assert.ok(root.startsWith(testRoot+sep));rmSync(root,{recursive:true,force:true});});
  return {root,script,privateDir:join(root,'.device-private','vps')};
}
test('initializer keeps its directory private while granting the non-root API read access to mounted JSON',t=>{
  const {root,script,privateDir}=fixture(t);
  const result=spawnSync(process.execPath,[script,'--domain','peygir.hydrocode.ir'],{encoding:'utf8'});
  assert.equal(result.status,0,result.stderr);
  const account=JSON.parse(readFileSync(join(privateDir,'admin-bootstrap.json'),'utf8'));
  assert.ok(account.password.length>=24);assert.ok(!result.stdout.includes(account.password));
  const mounted=['admin-bootstrap.json','fcm-service-account.json','fcm-public.json'];
  if(process.platform!=='win32') {
    assert.equal(statSync(privateDir).mode&0o777,0o700);
    for(const name of mounted) assert.equal(statSync(join(privateDir,name)).mode&0o777,0o644,name);
    for(const name of ['compose.env','owner-login.txt']) assert.equal(statSync(join(privateDir,name)).mode&0o777,0o600,name);
    if(process.getuid()===0) {
      // Bind-mounted secrets expose file permissions, not the private source directory.
      // Hardlinks expose the same inode under a neutral directory for the uid=1000 check.
      chmodSync(root,0o755);chmodSync(join(root,'vps'),0o755);chmodSync(join(root,'vps','scripts'),0o755);
      const visible=join(root,'container-secrets');mkdirSync(visible,{mode:0o755});
      for(const name of mounted) linkSync(join(privateDir,name),join(visible,name));
      const child=spawnSync(process.execPath,['-e',`const fs=require('node:fs');for(const name of ${JSON.stringify(mounted)}) JSON.parse(fs.readFileSync(require('node:path').join(process.argv[1],name),'utf8'));`,visible],{uid:1000,gid:1000,encoding:'utf8'});
      assert.equal(child.status,0,child.stderr);
    }
  }
});
test('repeating initialization never replaces existing passwords or settings',t=>{
  const {script,privateDir}=fixture(t);
  assert.equal(spawnSync(process.execPath,[script,'--domain','peygir.hydrocode.ir']).status,0);
  const files=['admin-bootstrap.json','fcm-service-account.json','fcm-public.json','compose.env','owner-login.txt'];
  const before=files.map(n=>readFileSync(join(privateDir,n)));
  const result=spawnSync(process.execPath,[script,'--domain','other.example.com'],{encoding:'utf8'});
  assert.notEqual(result.status,0);
  files.forEach((n,i)=>assert.deepEqual(readFileSync(join(privateDir,n)),before[i]));
});
