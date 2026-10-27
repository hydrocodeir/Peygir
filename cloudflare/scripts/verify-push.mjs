// Owner-only authorization check. Uses an invalid device token; no real phone receives a message.
import {readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
import {sendPush} from '../src/push.mjs';
const root=resolve(import.meta.dirname,'../..');
const account=await readFile(resolve(root,'.device-private/firebase/service-account.json'),'utf8');
const original=globalThis.fetch;let oauth=0,fcm=0,invalidDevice=false;
try {
  globalThis.fetch=async(url,...args)=>{
    const r=await original(url,...args);
    if(new URL(url).hostname==='oauth2.googleapis.com') oauth=r.status;
    else {fcm=r.status;const value=await r.clone().json();invalidDevice=value.error?.status==='INVALID_ARGUMENT' && value.error?.message?.includes('registration token');}
    return r;
  };
  await sendPush({FCM_SERVICE_ACCOUNT:account},'c'.repeat(152),{userId:'synthetic',eventId:'synthetic',recordId:'synthetic',kind:'task'});
} finally {globalThis.fetch=original;}
assert.equal(oauth,200);assert.equal(fcm,400);assert.ok(invalidDevice);
const summary={implementation:'Web Crypto JWT + FCM HTTP v1',oauthAuthorized:true,fcmApiAccessible:true,invalidTestDeviceRejected:true,realDeviceDeliveryTested:false};
await writeFile(resolve(root,'verification/firebase-check.json'),JSON.stringify(summary,null,2));
console.log('PASS: production push JWT implementation authorized by Google; invalid test device rejected as expected. No real notification sent.');
