const enc=new TextEncoder();
const base64url=bytes=>btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
const jwtPart=value=>base64url(enc.encode(JSON.stringify(value)));
let cached;
async function accessToken(env) {
  const account=JSON.parse(env.FCM_SERVICE_ACCOUNT);
  if(cached?.email===account.client_email && cached.expires>Date.now()+120000) return {token:cached.token,project:account.project_id};
  const now=Math.floor(Date.now()/1000);
  const unsigned=jwtPart({alg:'RS256',typ:'JWT'})+'.'+jwtPart({iss:account.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
  const pem=account.private_key.replace(/-----[^-]+-----|\s/g,'');
  const key=await crypto.subtle.importKey('pkcs8',Uint8Array.from(atob(pem),c=>c.charCodeAt(0)),{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['sign']);
  const signature=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key,enc.encode(unsigned));
  const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:unsigned+'.'+base64url(signature)}),signal:AbortSignal.timeout(15000)});
  if(!response.ok) throw new Error('Push authorization failed');
  const data=await response.json();cached={email:account.client_email,token:data.access_token,expires:Date.now()+data.expires_in*1000};return {token:cached.token,project:account.project_id};
}
export async function sendPush(env,device,data) {
  const auth=await accessToken(env);
  const response=await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(auth.project)}/messages:send`,{method:'POST',headers:{Authorization:'Bearer '+auth.token,'Content-Type':'application/json'},body:JSON.stringify({message:{token:device,data,android:{priority:'HIGH',ttl:'3600s'}}}),signal:AbortSignal.timeout(15000)});
  if(response.ok) return 'ok';
  const result=await response.json().catch(()=>({}));
  return result.error?.details?.some(d=>d.errorCode==='UNREGISTERED')?'invalid':'retry';
}
