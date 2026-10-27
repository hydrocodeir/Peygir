import {mkdirSync,existsSync,writeFileSync,chmodSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {resolve,join} from 'node:path';
import {randomBytes} from 'node:crypto';
process.umask(0o077);
const args=process.argv.slice(2),domain=args[args.indexOf('--domain')+1];
if(!args.includes('--domain') || !/^(?=.{4,253}$)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])$/.test(domain) || !domain.includes('.') || domain.includes('..')) throw new Error('Usage: node vps/scripts/init.mjs --domain sync.example.com');
const root=resolve(fileURLToPath(new URL('../..',import.meta.url))),dir=join(root,'.device-private','vps');
mkdirSync(dir,{recursive:true,mode:0o700});try{chmodSync(dir,0o700);}catch{}
if(existsSync(join(dir,'admin-bootstrap.json')) || existsSync(join(dir,'compose.env'))) throw new Error('Private configuration exists; initialization was not repeated. Keep the existing files.');
const password=randomBytes(24).toString('base64url');
// The directory is 0700. Compose bind-mounts the files as secrets for its non-root API user.
for(const [name,content] of Object.entries({
  'admin-bootstrap.json':JSON.stringify({username:'owner',password})+'\n',
  'fcm-service-account.json':'{}\n','fcm-public.json':'{}\n',
  'compose.env':`PEYGIR_DOMAIN=${domain}\nPEYGIR_DATA_VOLUME=peygir_data\nPEYGIR_BACKUP_VOLUME=peygir_backups\n`,
  'owner-login.txt':`Management: https://${domain}/admin/\nUsername: owner\nPassword: ${password}\n`
})) writeFileSync(join(dir,name),content,{flag:'wx',mode:0o644});
console.log('Private configuration created in .device-private/vps. Read owner-login.txt locally; no credentials were printed.');
