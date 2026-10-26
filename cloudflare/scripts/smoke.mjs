import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),dir=resolve(root,'.device-private/cloudflare');
const {endpoint}=JSON.parse(await readFile(resolve(dir,'deployment.json'),'utf8'));
const {users}=JSON.parse(await readFile(resolve(dir,'credentials.json'),'utf8'));
const sessions=[];let cursor=0;const ids=[];
async function call(path,body,token) {
  const response=await fetch(endpoint+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)});
  return {status:response.status,data:await response.json()};
}
const id=()=>{const value=crypto.randomUUID();ids.push(value);return value;};
try {
  assert.equal((await call('/v1/sync',{cursor:0,operations:[]})).status,401);
  for(const username of ['assistant','manager']) {
    const u=users.find(u=>u.username===username);const r=await call('/v1/login',{username,password:u.password});assert.equal(r.status,200,JSON.stringify(r.data));sessions.push(r.data.token);
  }
  const [a,m]=sessions,meetingId=id(),taskId=id();
  const meeting={id:crypto.randomUUID(),recordId:meetingId,kind:'meeting',baseRevision:0,deleted:false,payload:{title:'آزمون اتصال پیگیر',date:Math.floor(Date.now()/86400000),notes:'دادهٔ ساختگی؛ پس از آزمون حذف می‌شود'}};
  const task={id:crypto.randomUUID(),recordId:taskId,kind:'task',baseRevision:0,deleted:false,payload:{title:'مصوبهٔ آزمایشی اتصال',description:'synthetic test',owner:'مسئول آزمایشی',due:meeting.payload.date+1,priority:'NORMAL',status:'WAITING',meeting:meetingId,reminders:[]}};
  const first=await call('/v1/sync',{cursor:0,operations:[meeting,task]},a);assert.equal(first.status,200);assert.ok(first.data.results.every(r=>r.ok));cursor=first.data.cursor;
  const second=await call('/v1/sync',{cursor:0,operations:[]},m);assert.equal(second.status,200);assert.ok(second.data.changes.some(r=>r.id===taskId));
  const done={...task,id:crypto.randomUUID(),baseRevision:1,payload:{...task.payload,status:'DONE'}};
  const note={id:crypto.randomUUID(),recordId:id(),kind:'followup',baseRevision:0,deleted:false,payload:{task:taskId,text:'پیگیری توسط مدیر آزمایشی انجام شد',created:Date.now()}};
  const changed=await call('/v1/sync',{cursor,operations:[done,note]},m);assert.equal(changed.status,200);assert.ok(changed.data.results.every(r=>r.ok));
  const read=await call('/v1/sync',{cursor,operations:[]},a);assert.equal(read.status,200);assert.ok(read.data.changes.some(r=>r.id===taskId && r.payload.status==='DONE'));
  const replay=await call('/v1/sync',{cursor,operations:[done]},m);assert.equal(replay.data.results[0].record.revision,2);
  const stale=await call('/v1/sync',{cursor,operations:[{...task,id:crypto.randomUUID(),baseRevision:1}]},a);assert.equal(stale.data.results[0].ok,false);
  const cleanup=await call('/v1/sync',{cursor,operations:[{...done,id:crypto.randomUUID(),baseRevision:2,deleted:true},{...meeting,id:crypto.randomUUID(),baseRevision:1,deleted:true}]},a);
  assert.equal(cleanup.status,200);assert.ok(cleanup.data.results.every(r=>r.ok));
  const checked=await call('/v1/sync',{cursor,operations:[]},m);assert.ok(checked.data.changes.some(r=>r.id===note.recordId && r.deleted));
  await writeFile(resolve(dir,'smoke-private.json'),JSON.stringify({endpoint,ids,ok:true},null,2));
  await writeFile(resolve(root,'verification/cloudflare-smoke.json'),JSON.stringify({endpoint,authenticatedAccounts:2,unauthenticatedRequestsRejected:true,meetingAndTaskShared:true,managerStatusAndFollowupReturned:true,retryIdempotent:true,staleEditConflicted:true,syntheticRecordsTombstoned:true,actualPhoneTested:false},null,2));
  console.log('PASS: live Worker + remote D1, both accounts, bidirectional changes, retry/conflict, deletion and child cleanup. Synthetic data only.');
} finally {
  for(const token of sessions) {const result=await call('/v1/logout',{},token);assert.equal(result.status,200);}
}
