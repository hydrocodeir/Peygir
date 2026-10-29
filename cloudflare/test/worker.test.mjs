import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
import {build} from 'esbuild';
import {passwordHash, validateOperation} from '../src/worker.mjs';

const uuid=()=>crypto.randomUUID();
async function fixture(fn) {
  const bundle=await build({entryPoints:['src/worker.mjs'],bundle:true,write:false,format:'esm'});
  const mf=new Miniflare(convertV4MiniflareOptions({modules:true,script:bundle.outputFiles[0].text,compatibilityDate:'2026-10-25',d1Databases:['DB']}));
  try {
    const db=await mf.getD1Database('DB');
    const schema=(await readFile('migrations/0001_shared.sql','utf8')).replace(/CREATE TRIGGER[\s\S]*?END;/g,s=>s.replace(/\r?\n/g,' '));
    await db.exec(schema);
    await db.exec(await readFile('migrations/0002_organization.sql','utf8'));
    await db.exec(await readFile('migrations/0004_push_accounts.sql','utf8'));
    const workspace=uuid(),other=uuid();
    for(const id of [workspace,other]) await db.prepare('INSERT INTO workspaces VALUES(?,?)').bind(id,'Test workspace').run();
    for(const [username,role,ws] of [['assistant','assistant',workspace],['manager','manager',workspace],['other','assistant',other]]) {
      const salt=uuid();await db.prepare('INSERT INTO users(id,workspace,username,name,role,salt,password_hash,iterations) VALUES(?,?,?,?,?,?,?,?)').bind(uuid(),ws,username,username,role,salt,await passwordHash('correct-strong-test-password',salt),100000).run();
    }
    const request=async (path,data,token)=> {
      const r=await mf.dispatchFetch('https://test.invalid'+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(data)});
      return {status:r.status,data:await r.json()};
    };
    const login=async username=>{const r=await request('/v1/login',{username,password:'correct-strong-test-password'});assert.equal(r.status,200);return r.data.token;};
    await fn({db,request,login,workspace});
  } finally {await mf.dispose();}
}
const meeting=()=>({id:uuid(),recordId:uuid(),kind:'meeting',baseRevision:0,deleted:false,payload:{title:'جلسه آزمون',date:20751,notes:''}});
const task=meetingId=>({id:uuid(),recordId:uuid(),kind:'task',baseRevision:0,deleted:false,payload:{title:'گزارش فروش',description:'',owner:'آقای احمدی',due:20752,priority:'NORMAL',status:'WAITING',meeting:meetingId,reminders:[1793000000000]}});
test('two accounts share meetings and tasks while another workspace is isolated',()=>fixture(async({request,login})=>{
  const a=await login('assistant'),m=await login('manager'),o=await login('other');const one=meeting(),two=task(one.recordId);
  const push=await request('/v1/sync',{cursor:0,operations:[one,two]},a);assert.equal(push.status,200);assert.ok(push.data.results.every(r=>r.ok));
  const pull=await request('/v1/sync',{cursor:0,operations:[]},m);assert.equal(pull.data.changes.length,2);
  assert.equal((await request('/v1/sync',{cursor:0,operations:[]},o)).data.changes.length,0);
  const update={...two,id:uuid(),baseRevision:1,payload:{...two.payload,status:'DONE'}};
  const done=await request('/v1/sync',{cursor:pull.data.cursor,operations:[update]},m);assert.equal(done.data.results[0].record.payload.status,'DONE');
  assert.equal((await request('/v1/sync',{cursor:push.data.cursor,operations:[]},a)).data.changes[0].payload.status,'DONE');
}));
test('idempotent retries return the original receipt; stale and simultaneous edits conflict',()=>fixture(async({request,login,db})=>{
  const token=await login('assistant'),op=meeting();const first=await request('/v1/sync',{cursor:0,operations:[op]},token);
  const replay=await request('/v1/sync',{cursor:0,operations:[op]},token);assert.deepEqual(replay.data.results,first.data.results);
  assert.equal((await db.prepare('SELECT count(*) AS n FROM changes').first()).n,1);
  const edits=[1,2].map(n=>({...op,id:uuid(),baseRevision:1,payload:{...op.payload,title:'edit '+n}}));
  const both=await Promise.all(edits.map(e=>request('/v1/sync',{cursor:0,operations:[e]},token)));
  assert.equal(both.filter(r=>r.data.results[0].ok).length,1);assert.equal(both.filter(r=>!r.data.results[0].ok).length,1);
  const mismatch=await request('/v1/sync',{cursor:0,operations:[{...op,payload:{...op.payload,title:'changed replay'}}]},token);assert.equal(mismatch.status,400);
}));
test('meeting deletion detaches tasks; task deletion tombstones followups',()=>fixture(async({request,login})=>{
  const token=await login('assistant'),one=meeting(),two=task(one.recordId);
  const note={id:uuid(),recordId:uuid(),kind:'followup',baseRevision:0,deleted:false,payload:{task:two.recordId,text:'منتظر تأیید مالی است',created:1793000000000}};
  assert.equal((await request('/v1/sync',{cursor:0,operations:[one,two,note]},token)).status,200);
  const deleted=await request('/v1/sync',{cursor:0,operations:[{...one,id:uuid(),baseRevision:1,deleted:true}]},token);
  assert.equal(deleted.data.changes.at(-1).kind,'task');assert.equal(deleted.data.changes.at(-1).payload.meeting,null);
  const taskDelete={...two,id:uuid(),baseRevision:2,deleted:true,payload:{...two.payload,meeting:null}};
  const end=await request('/v1/sync',{cursor:0,operations:[taskDelete]},token);assert.equal(end.data.changes.at(-1).kind,'followup');assert.equal(end.data.changes.at(-1).deleted,true);
}));
test('credentials, logout, invalid payloads and cross-workspace references are enforced',()=>fixture(async({request,login})=>{
  assert.equal((await request('/v1/sync',{cursor:0,operations:[]})).status,401);
  assert.equal((await request('/v1/login',{username:'assistant',password:'wrong'})).status,401);
  const a=await login('assistant'),o=await login('other'),op=meeting();await request('/v1/sync',{cursor:0,operations:[op]},a);
  const blocked=await request('/v1/sync',{cursor:0,operations:[task(op.recordId)]},o);assert.equal(blocked.status,200);assert.equal(blocked.data.results[0].ok,false);assert.equal(blocked.data.results[0].record.reason,'parent_deleted');
  assert.equal((await request('/v1/sync',{cursor:-1,operations:[]},a)).status,400);
  assert.throws(()=>validateOperation({...task(null),payload:{...task(null).payload,status:'INVALID'}}));
  assert.equal((await request('/v1/logout',{},a)).status,200);
  assert.equal((await request('/v1/sync',{cursor:0,operations:[]},a)).status,401);
}));
test('login is rate limited',()=>fixture(async({request})=>{
  for(let i=0;i<10;i++) assert.equal((await request('/v1/login',{username:'bad',password:'bad'})).status,401);
  assert.equal((await request('/v1/login',{username:'bad',password:'bad'})).status,429);
}));
test('a deleted parent blocks its pending child without blocking unrelated queued work',()=>fixture(async({request,login})=>{
  const token=await login('assistant'),one=meeting(),two=task(one.recordId);
  await request('/v1/sync',{cursor:0,operations:[one,two]},token);
  await request('/v1/sync',{cursor:0,operations:[{...two,id:uuid(),baseRevision:1,deleted:true}]},token);
  const note={id:uuid(),recordId:uuid(),kind:'followup',baseRevision:0,deleted:false,payload:{task:two.recordId,text:'offline note',created:1793000000000}};
  const result=await request('/v1/sync',{cursor:0,operations:[note,meeting()]},token);
  assert.equal(result.status,200);assert.equal(result.data.results[0].ok,false);assert.equal(result.data.results[0].record.reason,'parent_deleted');assert.equal(result.data.results[1].ok,true);
}));
