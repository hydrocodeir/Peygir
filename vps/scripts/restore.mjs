import {restoreBackup} from '../src/maintenance.mjs';
process.umask(0o077);
const args=process.argv.slice(2),source=args[args.indexOf('--from')+1];
if(!args.includes('--from') || !source || !args.includes('--confirm'))throw new Error('Usage: node vps/scripts/restore.mjs --from /backups/NAME --confirm (API stopped; fresh data volume only)');
restoreBackup(process.env.DATA_DIR || '/data',source);console.log('Restored into the fresh data volume.');
