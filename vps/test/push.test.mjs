import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync} from 'node:crypto';
import {sendPush} from '../../cloudflare/src/push.mjs';

const {privateKey}=generateKeyPairSync('rsa',{modulusLength:2048,privateKeyEncoding:{type:'pkcs8',format:'pem'},publicKeyEncoding:{type:'spki',format:'pem'}});
const env={COMPANY_CODE:'company-a',FCM_SERVICE_ACCOUNT:JSON.stringify({client_email:'test@synthetic.invalid',project_id:'synthetic-project',private_key:privateKey})};
test('FCM sends only a generic, company-scoped event via the existing HTTP v1 interface',async t=>{
  let message;
  t.mock.method(globalThis,'fetch',async(url,options)=>{
    if(String(url).includes('oauth2.googleapis.com'))return Response.json({access_token:'synthetic-token',expires_in:3600});
    assert.equal(String(url),'https://fcm.googleapis.com/v1/projects/synthetic-project/messages:send');message=JSON.parse(options.body).message;return Response.json({name:'synthetic-message'});
  });
  assert.equal(await sendPush(env,'synthetic-device',{userId:'u',eventId:'e',recordId:'r',kind:'task'}),'ok');
  assert.deepEqual(message.data,{userId:'u',eventId:'e',recordId:'r',kind:'task',company:'company-a'});assert.equal(message.token,'synthetic-device');assert.equal(message.notification,undefined);assert.equal(message.android.priority,'HIGH');assert.equal(message.android.ttl,'3600s');
});
test('unregistered devices are distinguishable from retryable FCM failures',async t=>{
  let invalid=true;
  t.mock.method(globalThis,'fetch',async url=>String(url).includes('oauth2.googleapis.com')?Response.json({access_token:'synthetic-token',expires_in:3600}):Response.json(invalid?{error:{details:[{errorCode:'UNREGISTERED'}]}}:{error:{}},{status:404}));
  assert.equal(await sendPush(env,'synthetic-device',{}),'invalid');invalid=false;assert.equal(await sendPush(env,'synthetic-device',{}),'retry');
});
