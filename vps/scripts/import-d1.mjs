import {importD1} from '../src/import-d1.mjs';
process.umask(0o077);
const args=process.argv.slice(2),get=name=>args.includes(name)?args[args.indexOf(name)+1]:null;
if(!get('--sql') || !get('--code') || !get('--name'))throw new Error('Usage: node vps/scripts/import-d1.mjs --sql /backups/export.sql --code company-code --name "Company name" (API stopped)');
const result=importD1(process.env.DATA_DIR || '/data',{sqlFile:get('--sql'),code:get('--code'),name:get('--name')});console.log('Imported company '+result.code+'. Existing passwords and record identities were preserved; log in again.');
