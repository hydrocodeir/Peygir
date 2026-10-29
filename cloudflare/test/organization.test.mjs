import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {build} from 'esbuild';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {passwordHash} from '../src/worker.mjs';
const uuid=()=>crypto.randomUUID();
const password='correct-strong-test-password';
async function fixture(fn) {
  const bundle=await build({entryPoints:['src/worker.mjs'],bundle:true,write:false,format:'esm'});
  const mf=new Miniflare(convertV4MiniflareOptions({modules:true,script:bundle.outputFiles[0].text,compatibilityDate:'2026-10-25',d1Databases:['DB']}));
  try {
    const db=await mf.getD1Database('DB');
    await db.exec((await readFile('migrations/0001_shared.sql','utf8')).replace(/CREATE TRIGGER[\s\S]*?END;/g,s=>s.replace(/\r?\n/g,' ')));
    await db.exec(await readFile('migrations/0002_organization.sql','utf8'));
    await db.exec(await readFile('migrations/0004_push_accounts.sql','utf8'));
    const ws=uuid(),other=uuid(),users={};
    for(const w of [ws,other]) await db.prepare('INSERT INTO workspaces VALUES(?,?)').bind(w,'Company').run();
    for(const [name,role,workspace] of [['assistant','assistant',ws],['manager','manager',ws],['a','deputy',ws],['b','deputy',ws],['foreign','deputy',other]]) {
      const id=uuid(),salt=uuid();users[name]=id;
      await db.prepare('INSERT INTO users(id,workspace,username,name,role,access_role,salt,password_hash,iterations) VALUES(?,?,?,?,?,?,?,?,100000)').bind(id,workspace,name,name,role==='deputy'?'assistant':role,role,salt,await passwordHash(password,salt)).run();
    }
    const request=async(path,p,token)=>{const r=await mf.dispatchFetch('https://test.invalid'+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(p)});return {status:r.status,data:await r.json()};};
    const tokens={};for(const name of Object.keys(users)) {const r=await request('/v2/login',{username:name,password});assert.equal(r.status,200);tokens[name]=r.data.token;}
    const sync=(who,operations=[],inventory=[])=>request('/v2/sync',{protocol:2,operations,inventory},tokens[who]);
    await fn({db,ws,users,tokens,request,sync});
  } finally {await mf.dispose();}
}
const meeting=attendees=>({id:uuid(),recordId:uuid(),kind:'meeting',baseRevision:0,deleted:false,payload:{title:'جلسه محرمانه',date:20751,notes:'دستور جلسه',start:540,end:600,location:'شرکت',attendees,repeat:'NONE',until:null,series:null}});
const task=(assignee,meeting=null)=>({id:uuid(),recordId:uuid(),kind:'task',baseRevision:0,deleted:false,payload:{title:'مصوبه',description:'شرح',owner:'معاون',due:20752,priority:'NORMAL',status:'WAITING',meeting,reminders:[],assignee,submitted:0,approved:0,approvedBy:null}});
const edit=(op,fields,revision=1)=>({...op,id:uuid(),baseRevision:revision,payload:{...op.payload,...fields}});
const inv=changes=>changes.filter(r=>!r.revoked).map(r=>({id:r.id,kind:r.kind,revision:r.revision,view:r.view}));
test('organization meetings require both start and end times with a strictly increasing range',()=>fixture(async({sync})=>{
  const m=meeting([]);
  for(const times of [{start:null,end:null},{start:600,end:null},{start:600,end:600},{start:600,end:540}]) assert.equal((await sync('assistant',[{...m,id:uuid(),recordId:uuid(),payload:{...m.payload,...times}}])).status,400);
  assert.equal((await sync('assistant',[m])).data.results[0].ok,true);
}));
test('deputies receive only their tasks, their followups and invited meetings, including on a fresh full sync',()=>fixture(async({sync,users})=>{
  const m=meeting([users.a,users.b]),a=task(users.a,m.recordId),b=task(users.b,m.recordId),unassigned=task(null);
  const note={id:uuid(),recordId:uuid(),kind:'followup',baseRevision:0,deleted:false,payload:{task:b.recordId,text:'private B note',created:Date.now()}};
  assert.equal((await sync('assistant',[m,a,b,unassigned,note])).status,200);
  const ra=await sync('a'),rb=await sync('b');
  assert.deepEqual(new Set(ra.data.changes.map(r=>r.id)),new Set([m.recordId,a.recordId]));
  assert.deepEqual(new Set(rb.data.changes.map(r=>r.id)),new Set([m.recordId,b.recordId,note.recordId]));
  assert.equal((await sync('foreign')).data.changes.length,0);
  assert.equal((await sync('manager')).data.changes.length,5);
}));
test('guessed IDs, stale operations and another user receipt never expose a different deputy task',()=>fixture(async({sync,users})=>{
  const a=task(users.a),b=task(users.b);await sync('assistant',[a,b]);
  for(const op of [edit(b,{status:'ACTIVE'}),{...b,baseRevision:0}]) {
    const r=await sync('a',[op]);assert.equal(r.status,200);assert.equal(r.data.results[0].record.revoked,true);assert.deepEqual(r.data.results[0].record.payload,{});
  }
  const otherReceipt=await sync('b',[b]);assert.equal(otherReceipt.status,400);
}));
test('deputy cannot change assignment, title, date, priority, delete or approve; chief alone approves REVIEW',()=>fixture(async({sync,users})=>{
  const t=task(users.a);await sync('assistant',[t]);
  for(const fields of [{title:'hacked'},{due:20799},{assignee:users.b},{priority:'URGENT'},{status:'DONE'}]) assert.equal((await sync('a',[edit(t,fields)])).status,400);
  assert.equal((await sync('a',[{...edit(t,{}),deleted:true}])).status,400);
  const review=await sync('a',[edit(t,{status:'REVIEW'})]);assert.equal(review.status,200);assert.equal(review.data.results[0].record.payload.status,'REVIEW');assert.ok(review.data.results[0].record.payload.submitted>0);
  const current=review.data.results[0].record.payload;
  assert.equal((await sync('assistant',[{...edit(t,{},2),payload:{...current,status:'DONE'}}])).status,400);
  const approved=await sync('manager',[{...edit(t,{},2),payload:{...current,status:'DONE'}}]);assert.equal(approved.status,200);assert.equal(approved.data.results[0].record.payload.approvedBy,users.manager);
}));
test('reassignment revokes cached parent and notes, and exposes all existing child history to the new assignee',()=>fixture(async({sync,users})=>{
  const t=task(users.a),note={id:uuid(),recordId:uuid(),kind:'followup',baseRevision:0,deleted:false,payload:{task:t.recordId,text:'report',created:Date.now()}};
  await sync('assistant',[t,note]);const first=await sync('a');
  await sync('manager',[edit(t,{assignee:users.b})]);
  const old=await sync('a',[],inv(first.data.changes));assert.ok(old.data.changes.every(r=>r.revoked && Object.keys(r.payload).length===0));assert.equal(old.data.changes.length,2);
  const next=await sync('b');assert.deepEqual(new Set(next.data.changes.map(r=>r.id)),new Set([t.recordId,note.recordId]));
  const replay=await sync('a',[note]);assert.equal(replay.data.results[0].record.revoked,true);
}));
test('removing a meeting invite revokes meeting data and projects the retained own task without the hidden meeting',()=>fixture(async({sync,users})=>{
  const m=meeting([users.a]),t=task(users.a,m.recordId);await sync('assistant',[m,t]);const old=await sync('a');
  await sync('manager',[edit(m,{attendees:[]})]);
  const after=await sync('a',[],inv(old.data.changes));
  assert.equal(after.data.changes.find(r=>r.kind==='meeting').revoked,true);
  assert.equal(after.data.changes.find(r=>r.kind==='task').payload.meeting,null);
  const projected=after.data.changes.find(r=>r.kind==='task');
  const status=await sync('a',[{id:uuid(),recordId:t.recordId,kind:'task',baseRevision:projected.revision,deleted:false,payload:{...projected.payload,status:'ACTIVE'}}]);
  assert.equal(status.data.results[0].ok,true);assert.equal(status.data.results[0].record.payload.meeting,null);
  const all=await sync('manager');assert.equal(all.data.changes.find(r=>r.id===t.recordId).payload.meeting,m.recordId);
}));
test('inviting someone to an old meeting works even with no new task revision, and cross-company IDs are rejected',()=>fixture(async({sync,users})=>{
  const m=meeting([]),t=task(users.a,m.recordId);await sync('assistant',[m,t]);const old=await sync('a');assert.equal(old.data.changes[0].payload.meeting,null);
  assert.equal((await sync('assistant',[edit(m,{attendees:[users.foreign]})])).status,400);
  await sync('manager',[edit(m,{attendees:[users.a]})]);const fresh=await sync('a',[],inv(old.data.changes));assert.equal(fresh.data.changes.find(r=>r.kind==='task').payload.meeting,m.recordId);
  assert.equal((await sync('assistant',[task(users.foreign)])).status,400);
}));
test('account creation, reset and deactivation are admin-only, revoke sessions and protect the last chief',()=>fixture(async({request,sync,tokens,users})=>{
  const user={action:'create',id:uuid(),name:'New deputy',username:'new-deputy',role:'deputy',password};
  assert.equal((await request('/v2/users',user,tokens.a)).status,403);
  assert.equal((await request('/v2/users',user,tokens.assistant)).status,200);
  assert.equal((await request('/v1/sync',{cursor:0,operations:[]},tokens.manager)).status,426);
  const login=await request('/v2/login',{username:user.username,password});assert.equal(login.data.role,'deputy');
  assert.equal((await request('/v2/users',{action:'reset',id:user.id,password:'new-strong-password'},tokens.manager)).status,200);
  assert.equal((await request('/v2/sync',{protocol:2,operations:[],inventory:[]},login.data.token)).status,401);
  assert.equal((await request('/v2/users',{action:'update',id:users.manager,name:'Chief',active:false},tokens.assistant)).status,400);
  assert.equal((await request('/v2/users',{action:'update',id:users.a,name:'A',active:false},tokens.manager)).status,200);
  assert.equal((await sync('a')).status,401);
}));
test('deputy can append a report to own task; unrelated or editable reports and meeting writes are blocked',()=>fixture(async({sync,users})=>{
  const a=task(users.a),b=task(users.b);await sync('assistant',[a,b]);
  const note={id:uuid(),recordId:uuid(),kind:'followup',baseRevision:0,deleted:false,payload:{task:a.recordId,text:'progress',created:Date.now()}};
  assert.equal((await sync('a',[note])).data.results[0].ok,true);
  assert.equal((await sync('a',[edit(note,{text:'overwrite'})])).status,400);
  const denied=await sync('a',[{...note,id:uuid(),recordId:uuid(),payload:{...note.payload,task:b.recordId}}]);assert.equal(denied.data.results[0].record.revoked,true);
  const m=meeting([users.a]);await sync('assistant',[m]);assert.equal((await sync('a',[edit(m,{title:'changed'})])).status,403);
}));
test('stale authorized edits receive the current version and canonical review/report receipts survive lost responses',()=>fixture(async({sync,users})=>{
  const t=task(users.a);await sync('assistant',[t]);
  await sync('manager',[edit(t,{title:'عنوان تازه'})]);
  const stale=await sync('a',[edit(t,{status:'ACTIVE'})]);assert.equal(stale.status,200);assert.equal(stale.data.results[0].ok,false);assert.equal(stale.data.results[0].record.payload.title,'عنوان تازه');
  const current=stale.data.results[0].record;
  const review={...edit(t,{},2),payload:{...current.payload,status:'REVIEW'}};
  const first=await sync('a',[review]);const again=await sync('a',[review]);assert.deepEqual(first.data.results,again.data.results);
  const note={id:uuid(),recordId:uuid(),kind:'followup',baseRevision:0,deleted:false,payload:{task:t.recordId,text:'گزارش ثبت‌شده',created:Date.now()}};
  const one=await sync('a',[note]);const two=await sync('a',[note]);assert.deepEqual(one.data.results,two.data.results);
}));
test('push outbox contains only the chief and allowed assignee/invitees, never other deputies',()=>fixture(async({sync,users,db})=>{
  const t=task(users.a);await sync('assistant',[t]);
  let rows=(await db.prepare('SELECT user_id FROM push_outbox').all()).results;
  assert.deepEqual(new Set(rows.map(r=>r.user_id)),new Set([users.a,users.manager]));
  await db.prepare('DELETE FROM push_outbox').run();await sync('assistant',[meeting([users.b])]);
  rows=(await db.prepare('SELECT user_id FROM push_outbox').all()).results;
  assert.deepEqual(new Set(rows.map(r=>r.user_id)),new Set([users.b,users.manager]));
}));
test('inventory pagination catches up without omissions and does not repeatedly return unknown tombstones',()=>fixture(async({sync,users,db,ws})=>{
  for(let i=0;i<205;i++) {const m=meeting([users.a]);await db.prepare('INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?)').bind(ws,m.recordId,'meeting',1,JSON.stringify(m.payload),0,'manager',Date.now(),uuid()).run();}
  const first=await sync('a');assert.equal(first.data.changes.length,200);assert.equal(first.data.hasMore,true);
  const second=await sync('a',[],inv(first.data.changes));assert.equal(second.data.changes.length,5);assert.equal(second.data.hasMore,false);
  assert.equal((await sync('a',[],inv([...first.data.changes,...second.data.changes]))).data.changes.length,0);
}));
