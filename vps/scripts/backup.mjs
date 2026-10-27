import {makeBackup} from '../src/maintenance.mjs';
process.umask(0o077);
console.log(await makeBackup(process.env.DATA_DIR || '/data',process.env.BACKUP_DIR || '/backups'));
