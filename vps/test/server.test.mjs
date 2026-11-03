import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {mkdtempSync,mkdirSync,rmSync,readFileSync,writeFileSync,existsSync} from 'node:fs';
import {resolve,join,sep} from 'node:path';
import {createApp} from '../src/app.mjs';
import {makeBackup,restoreBackup} from '../src/maintenance.mjs';
import {importD1} from '../src/import-d1.mjs';
import {passwordHash} from '../../cloudflare/src/worker.mjs';
import {flushPush,enqueueMidpoints} from '../../cloudflare/src/organization.mjs';
import {midpoint} from '../../cloudflare/src/deadline.mjs';

const testRoot=resolve(import.meta.dirname,'../../.local-build/vps-tests');mkdirSync(testRoot,{recursive:true});
const owner={username:'owner',password:'owner-test-passphrase-12345'};
const origin='https://peygir.test';

test('reserved meetings reject linked resolutions and existing task meetings cannot become reservations',async t=>{
  const {app}=await setup(t);await company(app,'reserve');const chief=await login(app,'reserve');
  const m=operation('meeting',{title:'رزرو',date:21000,notes:'',start:540,end:600,location:'',attendees:[],guests:[],repeat:'NONE',until:null,series:null,reserved:true});
  assert.equal((await sync(app,'reserve',chief.token,[m])).data.results[0].ok,true);
  assert.equal((await sync(app,'reserve',chief.token,[operation('task',{...task('ممنوع'),meeting:m.recordId})])).status,400);
  assert.equal((await sync(app,'reserve',chief.token,[operation('meeting',{...m.payload,reserved:false},m.recordId,randomUUID(),1)])).status,200);
  const r=await sync(app,'reserve',chief.token,[operation('task',{...task('مجاز'),meeting:m.recordId})]);assert.equal(r.data.results[0].ok,true);
  assert.equal((await sync(app,'reserve',chief.token,[operation('meeting',m.payload,m.recordId,randomUUID(),2)])).status,400);
});

test('extension requests cannot change due dates or be approved by deputies or assistants; rejection preserves due',async t=>{
  const {app}=await setup(t);await company(app,'extensions');const chief=await login(app,'extensions');
  const uid=await user(app,'extensions',chief.token),deputy=await login(app,'extensions','user','account-password-123456');
  await user(app,'extensions',chief.token,'assistant','helper');const helper=await login(app,'extensions','helper','account-password-123456');
  const due=Math.floor((Date.now()+210*60000)/86400000)+5;
  const first=operation('task',{...task('گزارش',uid),due});let r=(await sync(app,'extensions',chief.token,[first])).data.results[0].record;
  const extension={id:randomUUID(),status:'PENDING',progress:'۵۰ درصد',reason:'منتظر داده',due:due+3,baseDue:due,requestedBy:uid,requestedAt:Date.now(),decidedBy:null,decidedAt:0};
  assert.equal((await sync(app,'extensions',deputy.token,[operation('task',{...r.payload,extension,due:due+3},r.id,randomUUID(),r.revision)])).status,400);
  r=(await sync(app,'extensions',deputy.token,[operation('task',{...r.payload,extension},r.id,randomUUID(),r.revision)])).data.results[0].record;
  assert.equal(r.payload.due,due);assert.equal(r.payload.extension.status,'PENDING');
  assert.equal((await sync(app,'extensions',helper.token,[operation('task',{...r.payload,due:due+3},r.id,randomUUID(),r.revision)])).status,400);
  const approval={...r.payload.extension,status:'APPROVED',decidedAt:Date.now(),decidedBy:uid};
  for(const s of [deputy,helper]) assert.equal((await sync(app,'extensions',s.token,[operation('task',{...r.payload,extension:approval,due:due+3},r.id,randomUUID(),r.revision)])).status,400);
  const rejection={...r.payload.extension,status:'REJECTED',decidedAt:Date.now(),decidedBy:chief.userId};
  assert.equal((await sync(app,'extensions',chief.token,[operation('task',{...r.payload,extension:rejection,due:due+3},r.id,randomUUID(),r.revision)])).status,400);
  r=(await sync(app,'extensions',chief.token,[operation('task',{...r.payload,extension:rejection},r.id,randomUUID(),r.revision)])).data.results[0].record;
  assert.equal(r.payload.due,due);assert.equal(r.payload.extension.status,'REJECTED');
  const requested={...extension,id:randomUUID(),requestedAt:Date.now()};
  r=(await sync(app,'extensions',deputy.token,[operation('task',{...r.payload,extension:requested},r.id,randomUUID(),r.revision)])).data.results[0].record;
  const accepted={...r.payload.extension,status:'APPROVED',decidedBy:chief.userId,decidedAt:Date.now()};
  r=(await sync(app,'extensions',chief.token,[operation('task',{...r.payload,extension:accepted,due:due+3},r.id,randomUUID(),r.revision)])).data.results[0].record;
  assert.equal(r.payload.due,due+3);assert.equal(r.payload.extension.decidedBy,chief.userId);
  // Older clients preserve extension decisions and the original creation timestamp.
  const legacy={...r.payload,title:'عنوان تازه'};delete legacy.extension;delete legacy.created;
  const updated=(await sync(app,'extensions',chief.token,[operation('task',legacy,r.id,randomUUID(),r.revision)])).data.results[0].record;
  assert.deepEqual(updated.payload.extension,r.payload.extension);assert.equal(updated.payload.created,r.payload.created);
  const lease=app.registry.lease(app.registry.find('extensions'));try {
    assert.ok(lease.db.prepare("SELECT 1 FROM push_outbox WHERE user_id=? AND kind='extensionRequest'").get(chief.userId));
    assert.ok(lease.db.prepare("SELECT 1 FROM push_outbox WHERE user_id=? AND kind='extensionDecision'").get(uid));
  }finally{lease.release();}
});

test('midpoint events target only the assignee, fire at exactly half the interval, persist deduplication and skip completed work',async t=>{
  const {app}=await setup(t);await company(app,'midpoint');const chief=await login(app,'midpoint');const uid=await user(app,'midpoint',chief.token);
  const created=Date.now()-2*86400000,due=Math.floor((Date.now()+210*60000)/86400000)+2;
  const first=operation('task',{...task('پیگیری خودکار',uid),created,due});
  const r=(await sync(app,'midpoint',chief.token,[first])).data.results[0].record;
  const at=midpoint(r.payload);assert.equal(at,Math.floor(created+((due+1)*86400000-210*60000-created)/2));
  const lease=app.registry.lease(app.registry.find('midpoint'));try {
    const env={DB:lease.d1};await enqueueMidpoints(env,at-1);
    assert.equal(lease.db.prepare("SELECT count(*) n FROM push_outbox WHERE kind='midpoint'").get().n,0);
    await enqueueMidpoints(env,at);await enqueueMidpoints(env,at+1);
    const events=lease.db.prepare("SELECT * FROM push_outbox WHERE kind='midpoint'").all();assert.equal(events.length,1);assert.equal(events[0].user_id,uid);
    lease.db.prepare("DELETE FROM push_outbox WHERE kind='midpoint'").run();await enqueueMidpoints(env,at+5000);
    assert.equal(lease.db.prepare("SELECT count(*) n FROM push_outbox WHERE kind='midpoint'").get().n,0);
    assert.equal(midpoint({...r.payload,status:'DONE'}),null);assert.equal(midpoint({...r.payload,status:'REVIEW'}),null);
  }finally{lease.release();}
});
async function setup(t){const dir=mkdtempSync(join(testRoot,'case-'));const app=await createApp({dataDir:dir,publicOrigin:origin,bootstrap:owner});let closed=false;t.after(async()=>{if(!closed)await app.close();assert.ok(resolve(dir).startsWith(testRoot+sep));rmSync(dir,{recursive:true,force:true});});return {app,dir,close:async()=>{if(!closed){await app.close();closed=true;}}};}
async function request(app,path,{company,token,cookie,csrf,body,method=body===undefined?'GET':'POST',requestOrigin=origin,headers={}}={}){
  const r=await app.fetch(new Request(origin+path,{method,headers:{'content-type':'application/json',origin:requestOrigin,...(company?{'x-peygir-company':company}:{}),...(token?{authorization:'Bearer '+token}:{}),...(cookie?{cookie}:{}),...(csrf?{'x-csrf-token':csrf}:{}),...headers},...(body===undefined?{}:{body:JSON.stringify(body)})}));const text=await r.text();return {status:r.status,headers:r.headers,text,data: r.headers.get('content-type')?.includes('application/json')?JSON.parse(text):null};
}
async function company(app,code){return app.registry.create({code,name:'شرکت '+code,managerName:'رئیس '+code,managerUsername:'manager',managerPassword:'company-manager-password-123'});}
async function login(app,code,username='manager',password='company-manager-password-123'){const r=await request(app,'/v2/login',{company:code,body:{username,password}});assert.equal(r.status,200,r.text);return r.data;}
async function admin(app){const r=await request(app,'/admin/api/login',{body:owner});assert.equal(r.status,200);return {cookie:r.headers.get('set-cookie').split(';')[0],csrf:r.data.csrf};}
const operation=(kind,payload,recordId=randomUUID(),id=randomUUID(),baseRevision=0)=>({id,recordId,kind,baseRevision,deleted:false,payload});
const task=(title,assignee=null)=>({title,description:'',owner:'احمدی',due:20000,priority:'NORMAL',status:'WAITING',meeting:null,reminders:[],assignee,submitted:0,approved:0,approvedBy:null});
async function sync(app,company,token,operations=[],inventory=[]){return request(app,'/v2/sync',{company,token,body:{protocol:2,cursor:0,operations,inventory}});}
async function user(app,code,token,role='deputy',name='user'){const id=randomUUID();const r=await request(app,'/v2/users',{company:code,token,body:{action:'create',id,username:name,name:'نام '+name,role,password:'account-password-123456'}});assert.equal(r.status,200,r.text);return id;}

test('company provisioning creates separate database files, usernames and verified login scope',async t=>{
  const {app,dir}=await setup(t);const a=await company(app,'company-a'),b=await company(app,'company-b');
  assert.notEqual(a.company.id,b.company.id);assert.ok(existsSync(join(dir,'tenants',a.company.id+'.sqlite')));assert.ok(existsSync(join(dir,'tenants',b.company.id+'.sqlite')));
  const x=await login(app,'company-a'),y=await login(app,'company-b');assert.notEqual(x.userId,y.userId);assert.equal(x.company,'company-a');assert.equal(y.company,'company-b');
  const bad=await request(app,'/v2/login',{body:{username:'manager',password:'company-manager-password-123'}});assert.equal(bad.status,401);
  assert.equal((await request(app,'/v2/login',{company:'../../registry.sqlite',body:{username:'manager',password:'wrong'}})).status,401);
});
test('tokens from another company cannot sync, manage users, configure push, register or logout',async t=>{
  const {app}=await setup(t);await company(app,'company-a');await company(app,'company-b');const a=await login(app,'company-a');
  for(const path of ['/v2/sync','/v2/users','/v2/config','/v2/device','/v1/logout'])assert.equal((await request(app,path,{company:'company-b',token:a.token,body:{}})).status,401,path);
  assert.equal((await sync(app,'company-a',a.token)).status,200);
});
test('overlapping record and operation IDs remain independent between companies',async t=>{
  const {app}=await setup(t);await company(app,'company-a');await company(app,'company-b');const a=await login(app,'company-a'),b=await login(app,'company-b');const id=randomUUID(),op=randomUUID();
  assert.equal((await sync(app,'company-a',a.token,[operation('task',task('PRIVATE A'),id,op)])).data.results[0].ok,true);
  assert.equal((await sync(app,'company-b',b.token,[operation('task',task('PRIVATE B'),id,op)])).data.results[0].ok,true);
  const ar=await sync(app,'company-a',a.token),br=await sync(app,'company-b',b.token);assert.ok(ar.text.includes('PRIVATE A'));assert.ok(!ar.text.includes('PRIVATE B'));assert.ok(br.text.includes('PRIVATE B'));assert.ok(!br.text.includes('PRIVATE A'));
});
test('deputy scope and chief-only final approval work against the VPS SQLite adapter',async t=>{
  const {app}=await setup(t);await company(app,'company-a');const chief=await login(app,'company-a');const d=await user(app,'company-a',chief.token);const deputy=await login(app,'company-a','user','account-password-123456');
  const mine=operation('task',task('MY TASK',d)),privateTask=operation('task',task('CHIEF PRIVATE'));
  await sync(app,'company-a',chief.token,[mine,privateTask]);const read=await sync(app,'company-a',deputy.token);assert.ok(read.text.includes('MY TASK'));assert.ok(!read.text.includes('CHIEF PRIVATE'));
  const submit=operation('task',{...mine.payload,status:'REVIEW'},mine.recordId,randomUUID(),1);const s=await sync(app,'company-a',deputy.token,[submit]);assert.equal(s.data.results[0].ok,true);assert.equal(s.data.results[0].record.payload.status,'REVIEW');
  const finish=operation('task',{...s.data.results[0].record.payload,status:'DONE'},mine.recordId,randomUUID(),2);const denied=await sync(app,'company-a',deputy.token,[finish]);assert.equal(denied.status,400);
  const approved=await sync(app,'company-a',chief.token,[finish]);assert.equal(approved.data.results[0].ok,true);assert.equal(approved.data.results[0].record.payload.approvedBy,chief.userId);
});
test('chief can create assistants while assistants cannot promote or reset the chief',async t=>{
  const {app}=await setup(t);await company(app,'company-a');const chief=await login(app,'company-a');await user(app,'company-a',chief.token,'assistant','helper');const helper=await login(app,'company-a','helper','account-password-123456');
  const p={action:'create',id:randomUUID(),username:'another-helper',name:'Assistant',role:'assistant',password:'account-password-123456'};
  assert.equal((await request(app,'/v2/users',{company:'company-a',token:helper.token,body:p})).status,403);
  assert.equal((await request(app,'/v2/users',{company:'company-a',token:helper.token,body:{action:'reset',id:chief.userId,password:'new-account-password-123'}})).status,403);
  await user(app,'company-a',helper.token,'deputy','second-deputy');assert.equal((await sync(app,'company-a',chief.token)).status,200);
});
test('assignment and meeting attendees cannot refer to another company',async t=>{
  const {app}=await setup(t);await company(app,'company-a');await company(app,'company-b');const a=await login(app,'company-a'),b=await login(app,'company-b');
  const r=await sync(app,'company-a',a.token,[operation('task',task('bad assignment',b.userId))]);assert.equal(r.status,400);
  const meeting={title:'Bad meeting',date:20000,notes:'',start:540,end:600,location:'',attendees:[b.userId],repeat:'NONE',until:null,series:null};assert.equal((await sync(app,'company-a',a.token,[operation('meeting',meeting)])).status,400);
});
test('suspension blocks an entire company and revokes sessions without affecting another',async t=>{
  const {app}=await setup(t);const ca=await company(app,'company-a');await company(app,'company-b');const a=await login(app,'company-a'),b=await login(app,'company-b');const owner=await admin(app);
  assert.equal((await request(app,`/admin/api/companies/${ca.company.id}/update`,{...owner,body:{name:'A',active:false}})).status,200);
  assert.equal((await sync(app,'company-a',a.token)).status,401);assert.equal((await sync(app,'company-b',b.token)).status,200);
  await request(app,`/admin/api/companies/${ca.company.id}/update`,{...owner,body:{name:'A',active:true}});assert.equal((await sync(app,'company-a',a.token)).status,401);await login(app,'company-a');
});
test('owner panel requires a separate secure session, exact Origin and CSRF token',async t=>{
  const {app}=await setup(t);await company(app,'company-a');const a=await login(app,'company-a');
  assert.equal((await request(app,'/admin/api/companies',{token:a.token})).status,401);
  assert.equal((await request(app,'/admin/api/login',{requestOrigin:'https://evil.test',body:owner})).status,403);
  const response=await request(app,'/admin/api/login',{body:owner});assert.match(response.headers.get('set-cookie'),/HttpOnly; SameSite=Strict/);assert.match(response.headers.get('set-cookie'),/Secure/);
  const auth={cookie:response.headers.get('set-cookie').split(';')[0],csrf:response.data.csrf};
  assert.equal((await request(app,'/admin/api/companies',{cookie:auth.cookie,body:{}})).status,403);
  const list=await request(app,'/admin/api/companies',auth);assert.equal(list.status,200);assert.ok(!list.text.includes('password'));assert.ok(!list.text.includes('token'));
  const r=await request(app,'/admin/api/companies',{...auth,body:{code:'created-in-panel',name:'شرکت تازه',managerName:'مدیر'}});assert.equal(r.status,201);assert.ok(r.data.credentials.password.length>=12);assert.equal(r.data.credentials.endpoint,origin);
  assert.equal((await request(app,'/admin/api/companies/'+r.data.company.id+'/records',auth)).status,404);
  await request(app,'/admin/api/logout',{...auth,body:{}});assert.equal((await request(app,'/admin/api/companies',auth)).status,401);
});
test('owner reset generates a new password, revokes old chief sessions and preserves company records',async t=>{
  const {app}=await setup(t);const c=await company(app,'company-a');const a=await login(app,'company-a');await sync(app,'company-a',a.token,[operation('task',task('PRESERVED'))]);const auth=await admin(app);
  const r=await request(app,`/admin/api/companies/${c.company.id}/reset-manager`,{...auth,body:{}});assert.equal(r.status,200);assert.equal((await sync(app,'company-a',a.token)).status,401);
  const fresh=await login(app,'company-a',r.data.credentials.username,r.data.credentials.password);assert.ok((await sync(app,'company-a',fresh.token)).text.includes('PRESERVED'));
});
test('the local D1 adapter rolls back a failed batch and does not create duplicate revisions on retries',async t=>{
  const {app}=await setup(t);const c=await company(app,'company-a'),lease=app.registry.lease(c.company);
  try {await assert.rejects(lease.d1.batch([lease.d1.prepare('INSERT INTO workspaces VALUES(?,?)').bind('rollback','test'),lease.d1.prepare('INSERT INTO workspaces VALUES(?,?)').bind('rollback','duplicate')]));assert.equal(lease.db.prepare("SELECT count(*) n FROM workspaces WHERE id='rollback'").get().n,0);}finally{lease.release();}
  const a=await login(app,'company-a'),op=operation('task',task('ONCE'));const one=await sync(app,'company-a',a.token,[op]),two=await sync(app,'company-a',a.token,[op]);assert.deepEqual(two.data.results,one.data.results);assert.equal(two.data.results[0].record.revision,1);
});
test('login throttling cannot be bypassed by a forged Cloudflare IP header',async t=>{
  const {app}=await setup(t);await company(app,'company-a');
  for(let i=0;i<10;i++)assert.equal((await request(app,'/v2/login',{company:'company-a',headers:{'cf-connecting-ip':'forged-'+i},body:{username:'manager',password:'wrong'}})).status,401);
  assert.equal((await request(app,'/v2/login',{company:'company-a',headers:{'cf-connecting-ip':'new-forged'},body:{username:'manager',password:'wrong'}})).status,429);
});
test('backup excludes partial WAL copies, requires stopped API and restores all companies to a fresh volume',async t=>{
  const {app,dir,close}=await setup(t);await company(app,'company-a');await company(app,'company-b');const a=await login(app,'company-a');await sync(app,'company-a',a.token,[operation('task',task('BACKUP RECORD'))]);
  const backups=mkdtempSync(join(testRoot,'backup-')),target=mkdtempSync(join(testRoot,'restore-'));t.after(()=>{for(const p of [backups,target]){assert.ok(resolve(p).startsWith(testRoot+sep));rmSync(p,{recursive:true,force:true});}});
  await assert.rejects(makeBackup(dir,backups),/API is running/);await close();const path=await makeBackup(dir,backups);const manifest=JSON.parse(readFileSync(join(path,'manifest.json'),'utf8'));assert.equal(manifest.files.length,3);
  restoreBackup(target,path);const restored=await createApp({dataDir:target,publicOrigin:origin});try{assert.equal(restored.registry.list().length,2);assert.equal((await sync(restored,'company-a',a.token)).status,401);const user=await login(restored,'company-a');assert.ok((await sync(restored,'company-a',user.token)).text.includes('BACKUP RECORD'));}finally{await restored.close();}
  assert.throws(()=>restoreBackup(target,path),/fresh empty/);
  const empty=mkdtempSync(join(testRoot,'tamper-'));t.after(()=>{assert.ok(resolve(empty).startsWith(testRoot+sep));rmSync(empty,{recursive:true,force:true});});manifest.files[0].sha256='0'.repeat(64);writeFileSync(join(path,'manifest.json'),JSON.stringify(manifest));assert.throws(()=>restoreBackup(empty,path),/checksum/);
});
test('static panel has a strict CSP, local assets, and no company content in its initial HTML',async t=>{
  const {app}=await setup(t);await company(app,'company-a');const html=await request(app,'/admin/');assert.equal(html.status,200);assert.match(html.headers.get('content-security-policy'),/frame-ancestors 'none'/);assert.ok(!html.text.includes('company-a'));
  assert.equal((await request(app,'/admin/app.js')).status,200);assert.equal((await request(app,'/admin/font.ttf')).status,200);assert.equal((await request(app,'/.device-private/vps/admin-bootstrap.json')).status,404);
});
test('offline D1 import preserves IDs and passwords, revokes sessions and rejects duplicate imports',async t=>{
  const {app,dir,close}=await setup(t);await close();const id=randomUUID(),manager=randomUUID(),sqlFile=join(dir,'export.sql');
  const schema=['0001_shared.sql','0002_organization.sql'].map(n=>readFileSync(new URL('../../cloudflare/migrations/'+n,import.meta.url),'utf8')).join('\n');const hash=await passwordHash('company-manager-password-123','salt');
  writeFileSync(sqlFile,schema+`\nINSERT INTO workspaces VALUES('${id}','Original');INSERT INTO users(id,workspace,username,name,role,access_role,salt,password_hash,iterations) VALUES('${manager}','${id}','manager','Chief','manager','manager','salt','${hash}',100000);INSERT INTO sessions VALUES('old-session','${manager}',9999999999999);`);
  const result=importD1(dir,{sqlFile,code:'imported-company',name:'Imported'});assert.equal(result.id,id);
  const restarted=await createApp({dataDir:dir,publicOrigin:origin});try{const loginResult=await login(restarted,'imported-company');assert.equal(loginResult.userId,manager);const lease=restarted.registry.lease(restarted.registry.find('imported-company'));try{assert.equal(lease.db.prepare("SELECT count(*) n FROM sessions WHERE token_hash='old-session'").get().n,0);}finally{lease.release();}}finally{await restarted.close();}
  assert.throws(()=>importD1(dir,{sqlFile,code:'duplicate-code',name:'Duplicate'}),/already been imported/);assert.ok(existsSync(join(dir,'tenants',id+'.sqlite')));
});
test('D1 SQL import cannot attach the registry',async t=>{
  const {dir,close}=await setup(t);await close();const file=join(dir,'attach.sql');writeFileSync(file,"ATTACH DATABASE 'registry.sqlite' AS stolen;");assert.throws(()=>importD1(dir,{sqlFile:file,code:'bad-import',name:'Bad'}),/authorized/);
});

test('named people can be managers or assistants and edits preserve account identity and assignments',async t=>{
  const {app}=await setup(t);await company(app,'company-a');const chief=await login(app,'company-a');
  const id=await user(app,'company-a',chief.token,'manager','second-manager');const second=await login(app,'company-a','second-manager','account-password-123456');assert.equal(second.role,'manager');
  await sync(app,'company-a',chief.token,[operation('task',task('ASSIGNED BEFORE RENAME',id))]);
  const r=await request(app,'/v2/users',{company:'company-a',token:chief.token,body:{action:'update',id,name:'Named Person',username:'renamed-person',role:'assistant',active:true}});assert.equal(r.status,200,r.text);
  assert.equal((await sync(app,'company-a',second.token)).status,401);
  assert.equal((await request(app,'/v2/login',{company:'company-a',body:{username:'second-manager',password:'account-password-123456'}})).status,401);
  const fresh=await login(app,'company-a','renamed-person','account-password-123456');assert.equal(fresh.userId,id);assert.equal(fresh.role,'assistant');assert.equal(fresh.name,'Named Person');assert.ok((await sync(app,'company-a',fresh.token)).text.includes('ASSIGNED BEFORE RENAME'));
});
test('hard deletion removes credentials, sessions and push access but preserves business records and prevents UUID reuse',async t=>{
  const {app}=await setup(t);const c=await company(app,'company-a'),chief=await login(app,'company-a');const id=await user(app,'company-a',chief.token);const old=await login(app,'company-a','user','account-password-123456');
  await request(app,'/v2/device',{company:'company-a',token:old.token,body:{token:'synthetic-device',enabled:true}});
  await sync(app,'company-a',chief.token,[operation('task',task('KEEP HISTORY',id))]);
  const r=await request(app,'/v2/users',{company:'company-a',token:chief.token,body:{action:'delete',id}});assert.equal(r.status,200,r.text);assert.ok(!r.data.users.some(u=>u.id===id));
  for(const path of ['/v2/sync','/v2/users','/v2/config','/v2/device'])assert.equal((await request(app,path,{company:'company-a',token:old.token,body:{}})).status,401);
  assert.equal((await request(app,'/v2/login',{company:'company-a',body:{username:'user',password:'account-password-123456'}})).status,401);
  const lease=app.registry.lease(c.company);try{assert.equal(lease.db.prepare('SELECT count(*) n FROM users WHERE id=?').get(id).n,0);assert.equal(lease.db.prepare('SELECT count(*) n FROM sessions WHERE user_id=?').get(id).n,0);assert.equal(lease.db.prepare('SELECT count(*) n FROM push_devices WHERE user_id=?').get(id).n,0);assert.equal(lease.db.prepare('SELECT count(*) n FROM push_outbox WHERE user_id=?').get(id).n,0);}finally{lease.release();}
  assert.ok((await sync(app,'company-a',chief.token)).text.includes('KEEP HISTORY'));
  const reused=await request(app,'/v2/users',{company:'company-a',token:chief.token,body:{action:'create',id,name:'Impersonation',username:'user',role:'deputy',password:'account-password-123456'}});assert.equal(reused.status,409);
  const newId=await user(app,'company-a',chief.token,'deputy','user');assert.notEqual(newId,id);const fresh=await login(app,'company-a','user','account-password-123456');assert.ok(!(await sync(app,'company-a',fresh.token)).text.includes('KEEP HISTORY'));
});
test('self deletion and removing or demoting the last manager are rejected, including concurrent changes',async t=>{
  const {app}=await setup(t);await company(app,'company-a');const a=await login(app,'company-a');
  assert.equal((await request(app,'/v2/users',{company:'company-a',token:a.token,body:{action:'delete',id:a.userId}})).status,400);
  const change={action:'update',id:a.userId,name:'Manager',username:'manager',role:'deputy',active:true};assert.equal((await request(app,'/v2/users',{company:'company-a',token:a.token,body:change})).status,409);
  const id=await user(app,'company-a',a.token,'manager','other-manager'),b=await login(app,'company-a','other-manager','account-password-123456');
  const results=await Promise.all([request(app,'/v2/users',{company:'company-a',token:a.token,body:{action:'delete',id}}),request(app,'/v2/users',{company:'company-a',token:b.token,body:{action:'delete',id:a.userId}})]);assert.equal(results.filter(r=>r.status===200).length,1);
  const active=app.registry.lease(app.registry.find('company-a'));try{assert.equal(active.db.prepare("SELECT count(*) n FROM users WHERE active=1 AND COALESCE(access_role,role)='manager'").get().n,1);}finally{active.release();}
});
test('duplicate usernames, stale edits and cross-company deletes do not alter any account',async t=>{
  const {app}=await setup(t);await company(app,'company-a');await company(app,'company-b');const a=await login(app,'company-a'),b=await login(app,'company-b'),id=await user(app,'company-a',a.token);
  const update={action:'update',id,name:'Edited',username:'manager',role:'deputy',active:true};assert.equal((await request(app,'/v2/users',{company:'company-a',token:a.token,body:update})).status,409);
  assert.equal((await request(app,'/v2/users',{company:'company-a',token:a.token,body:{...update,username:'user',expected:{name:'stale',username:'user',role:'deputy',active:true}}})).status,409);
  assert.equal((await request(app,'/v2/users',{company:'company-a',token:a.token,body:{action:'delete',id:b.userId}})).status,400);await login(app,'company-b');
});
test('platform recovery selects another active manager after the original manager is deleted',async t=>{
  const {app}=await setup(t);const c=await company(app,'company-a'),a=await login(app,'company-a');await user(app,'company-a',a.token,'manager','replacement-manager');const b=await login(app,'company-a','replacement-manager','account-password-123456');
  assert.equal((await request(app,'/v2/users',{company:'company-a',token:b.token,body:{action:'delete',id:a.userId}})).status,200);
  const owner=await admin(app),reset=await request(app,`/admin/api/companies/${c.company.id}/reset-manager`,{...owner,body:{}});assert.equal(reset.status,200);assert.equal(reset.data.credentials.username,'replacement-manager');await login(app,'company-a',reset.data.credentials.username,reset.data.credentials.password);
});


test('company contacts persist after record deletion without login, invitations or cross-company disclosure',async t=>{
  const {app}=await setup(t);const aCompany=await company(app,'company-a');await company(app,'company-b');
  const a=await login(app,'company-a'),b=await login(app,'company-b');
  const deputyId=await user(app,'company-a',a.token,'deputy','ahmadi');
  const deputy=await login(app,'company-a','ahmadi','account-password-123456');
  const meeting={title:'جلسه مهمان',date:20000,notes:'',start:540,end:600,location:'سالن',attendees:[deputyId],guests:['سارا کاظمی'],repeat:'NONE',until:null,series:null};
  const op=operation('meeting',meeting);const saved=await sync(app,'company-a',a.token,[op]);assert.equal(saved.status,200,saved.text);assert.equal(saved.data.results[0].ok,true);
  assert.deepEqual(saved.data.contacts,['سارا کاظمی']);assert.ok(!saved.data.users.some(u=>u.name==='سارا کاظمی'));
  assert.ok(!(await sync(app,'company-b',b.token)).text.includes('کاظمی'));
  const visible=await sync(app,'company-a',deputy.token);assert.equal(visible.data.changes.length,1);assert.deepEqual(visible.data.contacts,[]);
  const lease=app.registry.lease(aCompany.company);try {
    assert.equal(lease.db.prepare('SELECT count(*) n FROM users').get().n,2);
    assert.deepEqual(lease.db.prepare('SELECT user_id FROM push_outbox').all().map(r=>r.user_id),[deputyId]);
  }finally{lease.release();}
  const olderClient={...meeting};delete olderClient.guests;
  const updated=await sync(app,'company-a',a.token,[operation('meeting',olderClient,op.recordId,randomUUID(),1)]);assert.deepEqual(updated.data.results[0].record.payload.guests,['سارا کاظمی']);
  const remove={...operation('meeting',meeting,op.recordId,randomUUID(),2),deleted:true};
  assert.equal((await sync(app,'company-a',a.token,[remove])).data.results[0].ok,true);
  assert.deepEqual((await sync(app,'company-a',a.token)).data.contacts,['سارا کاظمی']);
});

test('contacts cannot grant task access, cannot be forged by regular users and reject invalid guest lists',async t=>{
  const {app}=await setup(t);await company(app,'company-a');const a=await login(app,'company-a');
  const person=await user(app,'company-a',a.token,'deputy','ordinary'),d=await login(app,'company-a','ordinary','account-password-123456');
  const work=task('CONTACT TASK');work.owner='نام ordinary';
  const saved=await sync(app,'company-a',a.token,[operation('task',work)]);assert.deepEqual(saved.data.contacts,['نام ordinary']);
  assert.equal((await sync(app,'company-a',d.token)).data.changes.length,0);
  const bad=await sync(app,'company-a',d.token,[operation('task',{...work,assignee:person})]);assert.equal(bad.data.results[0].denied,true);
  const meeting={title:'bad',date:20000,notes:'',start:540,end:600,location:'',attendees:[],guests:['رضا','رضا'],repeat:'NONE',until:null,series:null};
  assert.equal((await sync(app,'company-a',a.token,[operation('meeting',meeting)])).status,400);
  const assigned=await sync(app,'company-a',a.token,[operation('task',task('ASSIGNED',person))]);assert.equal(assigned.data.results[0].ok,true);
  assert.equal((await sync(app,'company-a',d.token)).data.changes.length,1);
});

test('existing tenant databases acquire contacts schema on reopen without losing data',async t=>{
  const {app,dir,close}=await setup(t);const c=await company(app,'company-a'),a=await login(app,'company-a');
  await sync(app,'company-a',a.token,[operation('task',task('MIGRATED'))]);
  const lease=app.registry.lease(c.company);lease.db.exec('DROP TABLE contacts');lease.release();await close();
  const fresh=await createApp({dataDir:dir,publicOrigin:origin});try {
    const r=await sync(fresh,'company-a',a.token);assert.equal(r.status,200,r.text);assert.ok(r.text.includes('MIGRATED'));assert.deepEqual(r.data.contacts,[]);
  }finally{await fresh.close();}
});


test('new invitations and assignments target their recipient while ordinary edits stay update events',async t=>{
  const {app}=await setup(t);const c=await company(app,'company-a'),chief=await login(app,'company-a');
  const first=await user(app,'company-a',chief.token,'deputy','first'),second=await user(app,'company-a',chief.token,'deputy','second');
  const assistantId=await user(app,'company-a',chief.token,'assistant','assistant');
  const assistant=await login(app,'company-a','assistant','account-password-123456');
  const meeting={title:'PRIVATE MEETING TITLE',date:20000,notes:'',start:540,end:600,location:'',attendees:[first],guests:['PRIVATE GUEST'],repeat:'NONE',until:null,series:null};
  const invitation=operation('meeting',meeting);assert.equal((await sync(app,'company-a',assistant.token,[invitation])).data.results[0].ok,true);
  const assigned=operation('task',task('PRIVATE TASK TITLE',first));assert.equal((await sync(app,'company-a',assistant.token,[assigned])).data.results[0].ok,true);
  const edit=operation('task',{...assigned.payload,title:'CHANGED TASK TITLE'},assigned.recordId,randomUUID(),1);assert.equal((await sync(app,'company-a',assistant.token,[edit])).data.results[0].ok,true);
  const addGuest=operation('meeting',{...meeting,attendees:[first,second]},invitation.recordId,randomUUID(),1);assert.equal((await sync(app,'company-a',assistant.token,[addGuest])).data.results[0].ok,true);
  await sync(app,'company-a',assistant.token,[invitation]);
  const lease=app.registry.lease(c.company);try {
    const rows=lease.db.prepare('SELECT id,user_id,kind FROM push_outbox').all();
    const event=(op,id)=>rows.find(r=>r.id===op.id+'|'+id)?.kind;
    assert.equal(event(invitation,first),'invitation');assert.equal(event(invitation,chief.userId),'meeting');
    assert.equal(event(assigned,first),'assignment');assert.equal(event(assigned,chief.userId),'task');assert.equal(event(edit,first),'task');
    assert.equal(event(addGuest,first),'meeting');assert.equal(event(addGuest,second),'invitation');assert.equal(event(addGuest,assistantId),undefined);
    assert.equal(rows.filter(r=>r.id===invitation.id+'|'+first).length,1);
    assert.ok(!JSON.stringify(rows).includes('PRIVATE'));
  }finally{lease.release();}
});


test('one phone registers independent account routes and disabling or logging out one does not remove the other',async t=>{
  const {app}=await setup(t);const a=await company(app,'multi-a'),b=await company(app,'multi-b');
  const x=await login(app,'multi-a'),y=await login(app,'multi-b');
  const routeX=randomUUID(),routeY=randomUUID(),phone='same-phone-fcm-token';
  for(const [code,session,route] of [['multi-a',x,routeX],['multi-b',y,routeY]]) {
    const response=await request(app,'/v2/device',{company:code,token:session.token,body:{token:phone,enabled:true,accountRoute:route}});
    assert.equal(response.status,200);assert.equal(response.data.accountRoute,route);
    const config=await request(app,'/v2/config',{company:code,token:session.token,body:{}});assert.equal(config.data.accountRouting,1);
  }
  assert.equal((await request(app,'/v2/device',{company:'multi-a',token:x.token,body:{token:phone,enabled:false,accountRoute:routeX}})).status,200);
  let lease=app.registry.lease(a.company);try{assert.equal(lease.db.prepare('SELECT enabled FROM push_accounts WHERE route=?').get(routeX).enabled,0);}finally{lease.release();}
  lease=app.registry.lease(b.company);try{assert.equal(lease.db.prepare('SELECT enabled FROM push_accounts WHERE route=?').get(routeY).enabled,1);}finally{lease.release();}
  assert.equal((await request(app,'/v1/logout',{company:'multi-a',token:x.token,body:{}})).status,200);
  lease=app.registry.lease(a.company);try{assert.equal(lease.db.prepare('SELECT count(*) n FROM push_accounts WHERE route=?').get(routeX).n,0);}finally{lease.release();}
  assert.equal((await sync(app,'multi-b',y.token)).status,200);
});

test('two users in the same company keep one shared phone token under different routes and cannot claim each others route',async t=>{
  const {app}=await setup(t);const c=await company(app,'multi-a'),chief=await login(app,'multi-a');
  await user(app,'multi-a',chief.token,'deputy','person');const person=await login(app,'multi-a','person','account-password-123456');
  const one=randomUUID(),two=randomUUID();
  for(const [session,route] of [[chief,one],[person,two]]) assert.equal((await request(app,'/v2/device',{company:'multi-a',token:session.token,body:{token:'phone-token',enabled:true,accountRoute:route}})).status,200);
  assert.equal((await request(app,'/v2/device',{company:'multi-a',token:person.token,body:{token:'replacement',enabled:true,accountRoute:one}})).status,400);
  const lease=app.registry.lease(c.company);try{const rows=lease.db.prepare('SELECT route,user_id,token FROM push_accounts').all();assert.equal(rows.length,2);assert.equal(rows.find(r=>r.route===one).user_id,chief.userId);assert.ok(rows.every(r=>r.token==='phone-token'));}finally{lease.release();}
});

test('push account migration upgrades an existing tenant without modifying records or legacy devices',async t=>{
  const {app}=await setup(t);const c=await company(app,'multi-a'),session=await login(app,'multi-a');
  await sync(app,'multi-a',session.token,[operation('task',task('retained'))]);
  await request(app,'/v2/device',{company:'multi-a',token:session.token,body:{token:'legacy-phone',enabled:true}});
  const entry=app.registry.pool.get(c.company.id);entry.db.exec('DROP TABLE push_accounts');entry.db.close();app.registry.pool.delete(c.company.id);
  const lease=app.registry.lease(c.company);try{assert.equal(lease.db.prepare('SELECT count(*) n FROM records').get().n,1);assert.equal(lease.db.prepare('SELECT count(*) n FROM push_devices').get().n,1);assert.equal(lease.db.prepare('SELECT count(*) n FROM push_accounts').get().n,0);}finally{lease.release();}
});


test('push dispatch includes each account route and never sends private titles in the delivery payload',async t=>{
  const {app}=await setup(t);const c=await company(app,'multi-a'),chief=await login(app,'multi-a');
  const first=await user(app,'multi-a',chief.token,'deputy','first');const second=await user(app,'multi-a',chief.token,'deputy','second');
  const a=await login(app,'multi-a','first','account-password-123456'),b=await login(app,'multi-a','second','account-password-123456');
  const routes=[randomUUID(),randomUUID()];
  for(const [i,session] of [a,b].entries()) assert.equal((await request(app,'/v2/device',{company:'multi-a',token:session.token,body:{token:'shared-phone',enabled:true,accountRoute:routes[i]}})).status,200);
  await sync(app,'multi-a',chief.token,[operation('task',task('PRIVATE A',first)),operation('task',task('PRIVATE B',second))]);
  const lease=app.registry.lease(c.company);const sent=[];
  try{await flushPush({DB:lease.d1,FCM_SERVICE_ACCOUNT:'test-only',COMPANY_CODE:'multi-a'},async(env,token,data)=>{sent.push({token,data});return 'ok';});}finally{lease.release();}
  assert.equal(sent.length,2);assert.deepEqual(new Set(sent.map(x=>x.data.accountRoute)),new Set(routes));
  assert.equal(sent.find(x=>x.data.userId===first).data.accountRoute,routes[0]);assert.equal(sent.find(x=>x.data.userId===second).data.accountRoute,routes[1]);assert.ok(!JSON.stringify(sent).includes('PRIVATE'));
});
