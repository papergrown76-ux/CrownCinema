#!/usr/bin/env node
// Real Paper 26.2 regression test. Resource-pack application remains simulated.
const mc=require('minecraft-protocol');
const fs=require('node:fs');const path=require('node:path');const assert=require('node:assert/strict');
const crypto=require('node:crypto');const {spawn}=require('node:child_process');
const root=path.resolve(__dirname,'..');const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const actors=[];let stopping=false,failed;const logs={paper:'',proxy:''};
const downloads=[];
const runId=Date.now().toString(36).slice(-6);
function launch(name,args){
 const process=spawn('python3',args,{cwd:root,stdio:['pipe','pipe','pipe']});
 const file=fs.createWriteStream(path.join(root,'build/real-bridge-'+name+'.log'));
 for(const stream of [process.stdout,process.stderr])stream.on('data',b=>{logs[name]+=b;file.write(b)});
 process.on('error',e=>{failed=e});process.on('exit',code=>{if(!stopping)failed=Error(name+' exited: '+code)});
 return process;
}
fs.mkdirSync(path.join(root,'build'),{recursive:true});
fs.rmSync(path.join(root,'build/real-bridge-result.json'),{force:true});
const paper=launch('paper',['tools/run-paper-bridge-test.py']);
const proxy=launch('proxy',['tools/run-test-proxy.py','--real-bridge']);
async function until(check,label,timeout=15000){
 const end=Date.now()+timeout;
 while(Date.now()<end){if(failed)throw failed;if(check())return;await sleep(25)}
 throw Error('Timeout: '+label+'; inspect build/real-bridge-paper.log and real-bridge-proxy.log');
}
function connect(name,version){
 const client=mc.createClient({host:'127.0.0.1',port:25565,username:name,version,auth:'offline'});
 const r={client,name,version,positions:[],titles:[],sounds:[],abilities:[],logins:0,correct:false,corrected:0,done:false};actors.push(r);
 client.on('error',e=>{if(!stopping&&!r.done)failed=e});
 client.on('end',reason=>{if(!stopping&&!r.done)failed=Error(name+' disconnected: '+JSON.stringify(reason))});
 client.on('login',()=>r.logins++);
 client.on('position',p=>{
  const previous=r.positions.at(-1)||{x:0,y:0,z:0};const flags=typeof p.flags==='object'?p.flags._value:p.flags;
  const pos={x:p.x+((flags&1)?previous.x:0),y:p.y+((flags&2)?previous.y:0),z:p.z+((flags&4)?previous.z:0)};
  r.positions.push(pos);client.write('teleport_confirm',{teleportId:p.teleportId});
  if(r.correct&&pos.y===72){r.correct=false;r.corrected++;paper.stdin.write('execute as '+name+' at @s run tp @s ~0.1 ~ ~\n')}
 });
 client.on('set_title_text',p=>r.titles.push(p));client.on('entity_sound_effect',p=>r.sounds.push(p));
 client.on('abilities',p=>r.abilities.push(p));
 client.on('add_resource_pack',p=>{
  downloads.push(fetch(p.url).then(async response=>{assert.equal(response.status,200);const data=Buffer.from(await response.arrayBuffer());assert.equal(crypto.createHash('sha1').update(data).digest('hex'),p.hash)}));
  for(const result of [3,4,0])client.write('resource_pack_receive',{uuid:p.uuid,result});
 });
 return r;
}
function near(a,b){return a&&b&&Math.abs(a.x-b.x)+Math.abs(a.y-b.y)+Math.abs(a.z-b.z)<.05}
async function start(r,correction=false){
 const mark={positions:r.positions.length,titles:r.titles.length,sounds:r.sounds.length};r.correct=correction;
 proxy.stdin.write('crowncinema announce lobby Real Paper Test\n');
 await until(()=>r.positions.slice(mark.positions).some(p=>p.y===72)&&r.titles.length>mark.titles+2,'start '+r.name);
 return mark;
}
async function returned(r,mark,timeout=15000){
 await until(()=>r.positions.length>mark.positions&&near(r.positions.at(-1),r.origin)&&r.abilities.at(-1)?.flags===0,'return and restore '+r.name,timeout);
 assert.equal(r.logins,1,'bridge must not reconnect the backend');
 const recovery=fs.readFileSync(path.join(root,'build/real-paper-bridge/plugins/CrownCinemaBackend/recovery.yml'),'utf8');
 assert(!recovery.includes(r.client.uuid),'successful return must clear durable recovery data');
}
async function run(){
 await until(()=>logs.paper.includes('Done (')&&logs.proxy.includes('Done ('),'Paper and Velocity startup',90000);
 console.log('PASS: real Paper 26.2 + ViaVersion/ViaBackwards 5.12.1 and Velocity started');
 const legacy=connect('BridgeOld'+runId,'1.20.4');
 await until(()=>legacy.positions.length>0,'legacy join');legacy.origin=legacy.positions.at(-1);await sleep(1700);
 assert.notEqual(legacy.origin.y,72,'test must begin outside the cinema world');
 let mark=await start(legacy);await returned(legacy,mark);
 assert(legacy.titles.length-mark.titles>80);assert.equal(legacy.sounds.length-mark.sounds,1);
 console.log('PASS: full movie/sound and actual return to original position/movement without reconnect');
 mark=await start(legacy,true);await returned(legacy,mark);
 assert.equal(legacy.corrected,1);assert(legacy.titles.length-mark.titles>80);assert.equal(legacy.sounds.length-mark.sounds,1);
 console.log('PASS: extra server teleport inside cinema before READY no longer cancels playback or traps player');
 mark=await start(legacy);legacy.client.chat('/crownbackend leave');await returned(legacy,mark,6000);
 console.log('PASS: ordinary player emergency leave returns directly from the backend');
 mark=await start(legacy);proxy.stdin.write('crowncinema stop all\n');await returned(legacy,mark,6000);
 console.log('PASS: administrative stop restores actual backend position and movement');
 mark=await start(legacy);const logStart=logs.paper.length;paper.stdin.write('cinemafault\n');
 await until(()=>logs.paper.slice(logStart).includes('BRIDGE_TEST_RETURN_BLOCKED'),'arm return veto');
 proxy.stdin.write('crowncinema stop all\n');
 await until(()=>logs.paper.slice(logStart).includes('Return blocked for'),'blocked return detected');
 assert.equal(legacy.positions.at(-1).y,72,'vetoed return must keep the player in protected cinema');
 assert.equal(legacy.abilities.at(-1).flags&1,1,'damage protection must remain enabled');
 assert(fs.readFileSync(path.join(root,'build/real-paper-bridge/plugins/CrownCinemaBackend/recovery.yml'),'utf8').includes(legacy.client.uuid));
 await returned(legacy,mark,8000);
 console.log('PASS: vetoed return keeps protection/recovery and retries successfully after the veto clears');
 legacy.done=true;legacy.client.end('Legacy test complete');await sleep(350);
 const modern=connect('BridgeNew'+runId,'26.1');
 await until(()=>modern.positions.length>0,'modern join');modern.origin=modern.positions.at(-1);await sleep(1700);
 assert.notEqual(modern.origin.y,72,'test must begin outside the cinema world');
 mark=await start(modern,true);await returned(modern,mark);
 assert(modern.titles.length-mark.titles>80);assert.equal(modern.sounds.length-mark.sounds,1);
 console.log('PASS: modern 26.1 protocol client also survives the same-room teleport on Paper 26.2');
 await Promise.all(downloads);
 assert(logs.proxy.includes('Video: test-pattern.mp4'),'real proxy must load the imported synthetic MP4');
 console.log('PASS: real proxy imports a custom MP4 and serves the exact generated pack hash over HTTP');
 const sha256=file=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex');
 fs.writeFileSync(path.join(root,'build/real-bridge-result.json'),JSON.stringify({passed:true,paper:'26.2 build 134',clientVersions:['1.20.4','26.1'],
  proxySha256:sha256('target/crown-cinema.jar'),backendSha256:sha256('backend-bridge/target/crown-cinema-backend.jar'),
  customVideoImportVerified:true,visualClientTest:false,packApplicationSimulated:true,finishedAt:new Date().toISOString()},null,2)+'\n');
}
run().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(async()=>{
 stopping=true;for(const r of actors){r.done=true;r.client.end('Test complete')}
 for(const [child,command]of[[proxy,'shutdown\n'],[paper,'stop\n']]){
  if(child.exitCode===null){child.stdin.write(command);await Promise.race([new Promise(r=>child.once('exit',r)),sleep(10000)]);if(child.exitCode===null)child.kill('SIGTERM')}
 }
});
