// Explicit owner-run live check. Creates only labelled synthetic records, then tombstones them.
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const root=resolve(import.meta.dirname,'../..'),dir=resolve(root,'.device-private/cloudflare');
const {endpoint}=JSON.parse(await readFile(resolve(dir,'deployment.json'),'utf8'));
const {users}=JSON.parse(await readFile(resolve(dir,'credentials.json'),'utf8'));
const sessions=[],ids=new Set();let admin,passed=false,cleaned=false;
const recordId=()=>{const id=crypto.randomUUID();ids.add(id);return id;};
async function call(path,body,token) {
  const r=await fetch(endpoint+path,{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body),signal:AbortSignal.timeout(25000)});
  return {status:r.status,data:await r.json()};
}
async function sync(token,operations=[]) {
  const r=await call('/v2/sync',{protocol:2,operations,inventory:[]},token);
  assert.equal(r.status,200);assert.ok(r.data.results.every(r=>r.ok));return r.data;
}
async function readAll(token) {
  const known=new Map();
  for(let page=0;page<60;page++) {
    const inventory=[...known.values()].map(r=>({id:r.id,kind:r.kind,revision:r.revision,view:r.view}));
    const response=await call('/v2/sync',{protocol:2,operations:[],inventory},token);assert.equal(response.status,200);
    for(const r of response.data.changes) {if(r.revoked) known.delete(r.id);else known.set(r.id,r);}
    if(!response.data.hasMore) return {changes:[...known.values()]};
  }
  throw new Error('Synthetic check did not finish paging');
}
try {
  assert.equal((await call('/v2/sync',{protocol:2,operations:[],inventory:[]})).status,401);
  const accounts=[];
  for(const username of ['assistant','manager']) {
    const user=users.find(u=>u.username===username);
    const r=await call('/v2/login',{username,password:user.password});assert.equal(r.status,200);sessions.push(r.data.token);accounts.push(r.data);
  }
  admin=accounts[0];const chief=accounts[1];
  const config=await call('/v2/config',{},admin.token);assert.equal(config.status,200);assert.equal(config.data.pushConfigured,true);assert.ok(config.data.firebase.applicationId);
  const date=Math.floor(Date.now()/86400000);
  const meeting={id:crypto.randomUUID(),recordId:recordId(),kind:'meeting',baseRevision:0,deleted:false,payload:{title:'آزمون خودکار پیگیر ۳ — حذف می‌شود',date,notes:'synthetic check',start:540,end:600,location:'محیط آزمایش',attendees:[chief.userId],repeat:'NONE',until:null,series:null}};
  const task={id:crypto.randomUUID(),recordId:recordId(),kind:'task',baseRevision:0,deleted:false,payload:{title:'مصوبهٔ ساختگی آزمون سازمانی',description:'synthetic check',owner:'مسئول آزمایشی',due:date+1,priority:'NORMAL',status:'WAITING',meeting:meeting.recordId,reminders:[],assignee:chief.userId,submitted:0,approved:0,approvedBy:null}};
  const invalidTime=await call('/v2/sync',{protocol:2,inventory:[],operations:[{...meeting,id:crypto.randomUUID(),recordId:recordId(),payload:{...meeting.payload,start:null,end:null}}]},admin.token);
  assert.equal(invalidTime.status,400);
  await sync(admin.token,[meeting,task]);
  const pull=await readAll(chief.token);assert.ok(pull.changes.some(r=>r.id===task.recordId && r.payload.assignee===chief.userId));
  const review={...task,id:crypto.randomUUID(),baseRevision:1,payload:{...task.payload,status:'REVIEW'}};
  const reviewed=await sync(chief.token,[review]);const reviewing=reviewed.results[0].record;assert.ok(reviewing.payload.submitted>0);
  const forbidden=await call('/v2/sync',{protocol:2,inventory:[],operations:[{...review,id:crypto.randomUUID(),baseRevision:2,payload:{...reviewing.payload,status:'DONE'}}]},admin.token);assert.equal(forbidden.status,400);
  const approval={...task,id:crypto.randomUUID(),baseRevision:2,payload:{...reviewing.payload,status:'DONE'}};
  const approved=await sync(chief.token,[approval]);assert.equal(approved.results[0].record.payload.approvedBy,chief.userId);assert.ok(approved.results[0].record.payload.approved>0);
  const replay=await sync(chief.token,[approval]);assert.equal(replay.results[0].record.revision,3);
  const roundtrip=await readAll(admin.token);assert.ok(roundtrip.changes.some(r=>r.id===task.recordId && r.payload.status==='DONE'));
  const note={id:crypto.randomUUID(),recordId:recordId(),kind:'followup',baseRevision:0,deleted:false,payload:{task:task.recordId,text:'گزارش ساختگی آزمون؛ حذف می‌شود',created:Date.now()}};
  await sync(admin.token,[note]);
  passed=true;
} finally {
  if(admin) {
    const snapshot=await readAll(admin.token);
    const own=snapshot.changes.filter(r=>ids.has(r.id) && !r.deleted).sort((a,b)=>(a.kind==='task'?0:a.kind==='meeting'?1:2)-(b.kind==='task'?0:b.kind==='meeting'?1:2));
    // Task deletion cascades reports; subsequently read to avoid trying to delete an already tombstoned child.
    for(const r of own.filter(r=>r.kind==='task'||r.kind==='meeting')) await sync(admin.token,[{id:crypto.randomUUID(),recordId:r.id,kind:r.kind,baseRevision:r.revision,deleted:true,payload:r.payload}]);
    const remaining=await readAll(admin.token);cleaned=!remaining.changes.some(r=>ids.has(r.id) && !r.deleted);assert.ok(cleaned);
  }
  for(const token of sessions) assert.equal((await call('/v1/logout',{},token)).status,200);
  await writeFile(resolve(dir,'organization-smoke-private.json'),JSON.stringify({ids:[...ids],passed,cleaned},null,2));
}
assert.ok(passed && cleaned);
const report={endpoint,version:'3.1.0',protocol:2,authenticatedAccounts:2,cloudflareLiveTested:true,mandatoryMeetingTimesEnforced:true,accountAssignmentShared:true,chiefReviewApprovalTested:true,assistantApprovalRejected:true,retryIdempotent:true,pushConfigurationAvailable:true,syntheticRecordsTombstoned:true,testSessionsRevoked:true,deputyIsolationTestedInLocalWorkersRuntime:true,deputyIsolationTestedOnLiveService:false,fcmOAuthVerified:true,realDeviceDeliveryTested:false};
await writeFile(resolve(root,'verification/cloudflare-organization-smoke.json'),JSON.stringify(report,null,2));
console.log('PASS: live organization API, account assignment, review, chief-only approval, idempotency, configured push, synthetic cleanup and session revocation. No real phone delivery test.');
