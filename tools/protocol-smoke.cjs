#!/usr/bin/env node
// Headless transport/lifecycle test. A simulated pack SUCCESS is not a visual client test.
const mc = require('minecraft-protocol');
const data = require('minecraft-data');
const nbt = require('prismarine-nbt');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawn} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const ownId = '43e702b4-74f4-465a-8f40-d48b901d5638';
const initialBackendPackId = '13bb55d6-d714-4fbc-a313-01a42de68c55';
const sleep = ms => new Promise(resolve=>setTimeout(resolve,ms));
const servers=[]; const clients=[];
let proxy; let failed; let stopping=false;
const joins = {lobby:0,old:0,launch:0};
const movements = {lobby:0,old:0,launch:0};
const backendPlayers={};
const bridgeRequests=[];

async function until(check, label, timeout=20000) {
  const end=Date.now()+timeout;
  while(Date.now()<end) {
    if(failed) throw failed;
    if(check()) return;
    await sleep(25);
  }
  throw Error('Timeout: '+label);
}

function backend(name,version,port) {
  const d=data(version);
  const server=mc.createServer({host:'127.0.0.1',port,version,'online-mode':false,
    enforceSecureProfile:false,registryCodec:d.loginPacket.dimensionCodec});
  servers.push(server);
  server.on('error',e=>{failed=e;});
  server.on('playerJoin',client=>{
    joins[name]++;
    backendPlayers[name]=client;
    client.packResponses=[];
    client.on('resource_pack_receive',p=>client.packResponses.push(p));
    client.on('custom_payload',p=>{
      if (p.channel!=='crowncinema:bridge') return;
      assert.equal(p.data.length,19);
      bridgeRequests.push({server:name,action:p.data[1],token:p.data.subarray(2,18).toString('hex')});
      if (p.data[1]===0) {
        // Simulate only the trusted companion handshake. Real Bukkit player
        // protection/restoration is tested separately in backend-bridge.
        const response=Buffer.from(p.data); response[1]=1;response[18]=0;
        client.write('custom_payload',{channel:p.channel,data:response});
      }
    });
    client.on('position',()=>{movements[name]++;});
    client.on('error',e=>{if(!stopping) failed=e;});
    client.write('login',{...d.loginPacket,entityId:client.id});
    client.write('position',{x:0.5,y:64,z:0.5,yaw:0,pitch:0,flags:0,teleportId:1});
    if(name==='lobby') client.write('add_resource_pack',{uuid:initialBackendPackId,url:'https://example.invalid/nexo-style-join.zip',hash:'b'.repeat(40),forced:false,promptMessage:undefined});
  });
  return new Promise(resolve=>server.once('listening',resolve));
}

function connect(username,version,fakeHost) {
  const client=mc.createClient({host:'127.0.0.1',port:25565,username,version,auth:'offline',fakeHost});
  const record={client,packOffers:[],removed:[],titles:[],chat:[],positions:0,gameModes:[],abilities:[],sounds:[],stoppedSounds:[],entityId:null,appliedPacks:new Set(),initialHeld:false};
  clients.push(record);
  client.on('error',e=>{if(!stopping) failed=e;});
  client.on('end',reason=>{if(!stopping) failed=Error(username+' disconnected: '+reason);});
  client.on('position',p=>{record.positions++; client.write('teleport_confirm',{teleportId:p.teleportId});});
  client.on('login',p=>{record.gameModes.push(p.gameMode);record.entityId=p.entityId;});
  client.on('entity_sound_effect',p=>record.sounds.push({...p,localEntityId:record.entityId}));
  client.on('stop_sound',p=>record.stoppedSounds.push(p));
  client.on('abilities',p=>record.abilities.push(p));
  client.on('set_title_text',p=>record.titles.push(JSON.stringify(nbt.simplify(p.text))));
  client.on('system_chat',p=>record.chat.push(JSON.stringify(typeof p.content==='string'?p.content:nbt.simplify(p.content))));
  client.on('remove_resource_pack',p=>{record.removed.push(p.uuid);if(p.uuid)record.appliedPacks.delete(p.uuid);else record.appliedPacks.clear()});
  client.on('add_resource_pack',p=>{
    record.packOffers.push(p);
    if(p.uuid===initialBackendPackId && !record.initialHeld) {
      record.initialHeld=true;client.write('resource_pack_receive',{uuid:p.uuid,result:3});return;
    }
    // Exercise Velocity's real status state machine with a protocol client.
    // Texture decoding and GUI rendering still require a real Minecraft client.
    for(const result of [3,4,0]) client.write('resource_pack_receive',{uuid:p.uuid,result});
    record.appliedPacks.add(p.uuid);
  });
  return record;
}

async function main() {
  fs.rmSync(path.join(root,'build/protocol-smoke-result.json'),{force:true});
  await Promise.all([backend('lobby','1.20.4',25566),backend('old','1.20.2',25567),backend('launch','1.20.4',25569)]);
  fs.mkdirSync(path.join(root,'build'),{recursive:true});
  const log=fs.createWriteStream(path.join(root,'build/protocol-smoke-proxy.log'));
  let output='';
  proxy=spawn('python3',['tools/run-test-proxy.py','--smoke'],{cwd:root,stdio:['pipe','pipe','pipe']});
  for(const stream of [proxy.stdout,proxy.stderr]) stream.on('data',b=>{log.write(b);output+=b.toString();});
  proxy.on('error',e=>{failed=e;});
  proxy.on('exit',code=>{if(!stopping) failed=Error('Proxy exited with '+code+'; inspect build/protocol-smoke-proxy.log');});
  await until(()=>/Done \(/.test(output),'proxy startup',45000);
  assert.match(output,/Crown Cinema initialized; protocol range 765–777/);
  console.log('PASS: real Velocity + LimboAPI startup, protocol range 765–777');
  proxy.stdin.write('crowncinema debug on\n');
  await until(()=>output.includes('Debug enabled'),'enable debug mode');
  proxy.stdin.write('crowncinema video list\n');
  await until(()=>output.includes('Available in plugins/crowncinema/videos/'),'video library command');
  const modern=connect('CinemaModern','1.20.4','127.0.0.1');
  const old=connect('CinemaOld','1.20.2','old.crown.test');
  await until(()=>modern.initialHeld && old.chat.some(x=>x.includes('1.20.3')) && modern.positions>0 && old.positions>0,'backend joins and backend pack offer');
  await sleep(1500);
  assert(!modern.packOffers.some(p=>p.uuid===ownId),'cinema must wait for the backend download/reload');
  for(const result of [4,0]) modern.client.write('resource_pack_receive',{uuid:initialBackendPackId,result});
  modern.appliedPacks.add(initialBackendPackId);
  await until(()=>modern.packOffers.some(p=>p.uuid===ownId),'cinema pack after backend completion');
  const cinemaOffer=modern.packOffers.find(p=>p.uuid===ownId);
  console.log('PASS: backend pack finishes before cinema download, with SUCCESSFUL delivered to the backend');
  assert.equal(old.packOffers.length,0);
  assert.equal(old.chat.filter(x=>x.includes('1.20.3')).length,1);
  console.log('PASS: 1.20.2 gets one upgrade message and no pack; 1.20.4 gets the pack');
  assert.equal(cinemaOffer.url, 'http://127.0.0.1:28124/crown-cinema.zip?v='+cinemaOffer.hash);
  const download = await fetch(cinemaOffer.url);
  assert.equal(download.status, 200);
  const pack=Buffer.from(await download.arrayBuffer());
  assert.equal(crypto.createHash('sha1').update(pack).digest('hex'),cinemaOffer.hash);
  console.log('PASS: automatic self-hosted URL delivers the pack with the actual offered SHA-1');
  const backendPackId='1e2a4ba1-40ec-4240-b49a-790f89ec2608';
  backendPlayers.lobby.write('add_resource_pack',{uuid:backendPackId,url:'https://example.invalid/backend.zip',hash:'a'.repeat(40),forced:false,promptMessage:undefined});
  await until(()=>modern.packOffers.some(p=>p.uuid===backendPackId),'additional backend pack offer');
  await until(()=>backendPlayers.lobby.packResponses.some(p=>p.uuid===backendPackId && p.result===0),'backend receives pack success');
  await sleep(100);
  backendPlayers.lobby.write('remove_resource_pack',{uuid:undefined});
  await until(()=>modern.removed.includes(undefined),'backend clear-all');
  assert(modern.removed.includes(undefined),'backend clear-all must reach the client unchanged');
  await until(()=>modern.appliedPacks.has(ownId),'cinema pack automatically restored after backend clear-all');
  console.log('PASS: backend clear-all passes through and cinema restores only its own pack');
  for(let i=0;i<30;i++) {
    const url='https://example.invalid/backend-'+i+'.zip';
    const count=backendPlayers.lobby.packResponses.length;
    backendPlayers.lobby.write('remove_resource_pack',{uuid:undefined});
    backendPlayers.lobby.write('add_resource_pack',{uuid:backendPackId,url,hash:crypto.createHash('sha1').update(url).digest('hex'),forced:false,promptMessage:undefined});
    await until(()=>modern.packOffers.some(p=>p.url===url),'immediate replacement offer '+i);
    await until(()=>backendPlayers.lobby.packResponses.slice(count).some(p=>p.uuid===backendPackId && p.result===0),'immediate replacement success '+i);
    await sleep(20);
  }
  console.log('PASS: rapid backend clear-and-replace keeps downstream success responses working');
  await until(()=>modern.appliedPacks.has(ownId),'cinema pack restored after final backend replacement');
  assert(modern.appliedPacks.has(backendPackId),'backend replacement pack must stay applied');
  proxy.stdin.write('crowncinema status CinemaModern\n');
  await until(()=>output.includes('Backend packs:')&&output.includes('SUCCESSFUL'),'backend pack diagnostics');
  // No player has admin permission by default.
  modern.client.chat('/crowncinema announce launch Unauthorized');
  await until(()=>modern.chat.some(x=>x.includes('permission')),'permission denial');
  assert.equal(modern.titles.length,0);
  console.log('PASS: ordinary players cannot announce');
  proxy.stdin.write('crowncinema announce launch Test Survival\n');
  await until(()=>modern.titles.length>=3,'cinema starts');
  assert.equal(old.titles.length,0);
  assert(modern.gameModes.includes(3),'cinema did not use spectator mode');
  assert.equal(old.sounds.length,0,'unsupported client received cinema sound');
  assert.equal(modern.sounds.length,1,'cinema sound must be sent exactly once');
  const videoSound=modern.sounds[0];
  assert.equal(videoSound.soundId,0,'sound must use the inline custom resource-pack event');
  assert.equal(videoSound.soundEvent.resource,'crowncinema:video');
  assert.equal(videoSound.soundCategory,'master');
  assert.equal(videoSound.entityId,videoSound.localEntityId,'sound emitter must match the client\'s own Limbo player');
  assert.equal(videoSound.localEntityId,1,'pinned LimboAPI changed its local player entity ID');
  assert(Math.abs(videoSound.volume-0.8)<0.000001 && videoSound.pitch===1,'sound volume or pitch was changed');
  console.log('PASS: named video sound arrives once on MASTER at the actual Limbo player entity');
  assert(modern.abilities.some(a=>(a.flags&7)===7 && a.flyingSpeed===0 && a.walkingSpeed===0),'movement abilities were not frozen');
  const positionsBefore=modern.positions;
  const backendMovesBefore=movements.lobby;
  modern.client.write('position',{x:100,y:70,z:100,onGround:false});
  await until(()=>modern.positions>positionsBefore,'camera/position correction in limbo');
  assert.equal(movements.lobby,backendMovesBefore,'cinema movement reached the former backend');
  console.log('PASS: spectator camera is corrected and cinema movement never reaches the former backend');
  await until(()=>modern.chat.some(x=>x.includes('JOIN NOW')),'cinema completion and return',20000);
  assert(joins.lobby>=2,'viewer did not return to their original backend');
  assert(modern.titles.some(x=>x.includes('crowncinema:movie')),'no film frames');
  assert(modern.titles.some(x=>x.includes('Test Survival')),'no server name endcard');
  assert(modern.stoppedSounds.some(p=>p.flags===3 && p.source===0 && p.sound==='crowncinema:video'),
    'video sound did not stop at the end of the movie');
  console.log('PASS: movie frames + dynamic endcard, old client skipped, original backend restored');
  modern.client.chat('/crowncinema join');
  await until(()=>joins.launch===1,'Join Now target connection');
  console.log('PASS: Join Now uses the advertised registered Velocity server');
  // launch uses the optional backend bridge: no Limbo/reconnect is permitted.
  proxy.stdin.write('crowncinema announce lobby Second Run\n');
  const before=modern.titles.length;
  await until(()=>modern.titles.length>before+1,'second cinema starts');
  assert.equal(joins.launch,1,'backend bridge unexpectedly reconnected the viewer');
  assert(bridgeRequests.some(p=>p.server==='launch' && p.action===0),'no backend START handshake');
  assert.equal(modern.sounds.length,2,'second playback must restart the sound exactly once');
  const stopsBefore=modern.stoppedSounds.length;
  proxy.stdin.write('crowncinema stop all\n');
  await until(()=>bridgeRequests.some(p=>p.server==='launch' && p.action===3),'backend STOP handshake');
  assert.equal(joins.launch,1,'stopping backend cinema must keep the current server connection');
  assert(modern.stoppedSounds.length>stopsBefore,'administrative stop did not stop the sound');
  console.log('PASS: backend mode starts and stops without disconnecting from its server');
  const count=modern.titles.length;
  await sleep(350);
  assert.equal(modern.titles.length,count,'playback continued after stop');
  const disabled=connect('CinemaDisabled','1.20.4','off.crown.test');
  await until(()=>disabled.positions>0,'join unlisted/disabled server');
  await sleep(1300);
  assert.equal(disabled.packOffers.length,0,'disabled server received an automatic cinema pack offer');
  proxy.stdin.write('crowncinema announce lobby Exclusion Test\n');
  await until(()=>modern.titles.length>count,'enabled viewer receives announcement');
  assert.equal(disabled.titles.length,0,'disabled server received cinema frames');
  proxy.stdin.write('crowncinema stop all\n');
  await sleep(150);
  console.log('PASS: unlisted/OFF server receives neither automatic pack offers nor animation');
  proxy.stdin.write('crowncinema servers\n');
  await until(()=>output.includes('Crown Cinema - Servers'),'admin server overview');
  proxy.stdin.write('crowncinema server disabled proxy\n');
  await until(()=>disabled.packOffers.length===1,'live mode change offers pack to players already online');
  assert.match(fs.readFileSync(path.join(root,'build/smoke-runtime/plugins/crowncinema/config.properties'),'utf8'),/^cinema\.server\.disabled=proxy$/m);
  proxy.stdin.write('crowncinema announce lobby Live Settings\n');
  await until(()=>disabled.titles.length>=3,'newly enabled viewer receives animation without restarting');
  proxy.stdin.write('crowncinema server disabled off\n');
  await until(()=>disabled.gameModes.at(-1)!==3,'disabling a server stops its running cinema and returns its viewer');
  const disabledFrames=disabled.titles.length;
  await sleep(250);
  assert.equal(disabled.titles.length,disabledFrames,'frames continued after server was disabled');
  assert.match(fs.readFileSync(path.join(root,'build/smoke-runtime/plugins/crowncinema/config.properties'),'utf8'),/^cinema\.server\.disabled=off$/m);
  proxy.stdin.write('crowncinema stop all\n');
  console.log('PASS: admin menu discovers servers; mode commands persist, apply immediately and stop excluded viewers');
  assert(fs.readFileSync(path.join(root,'build/smoke-runtime/plugins/crowncinema/debug.log'),'utf8').includes('pack.status'),'debug log must contain real pack statuses');
  proxy.stdin.write('crowncinema debug off\n');
  await until(()=>output.includes('Debug disabled'),'disable debug mode');
  if(/ERROR|Exception/.test(output)) throw Error('Proxy logged an error; inspect build/protocol-smoke-proxy.log');
  const sha256=file=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex');
  fs.writeFileSync(path.join(root,'build/protocol-smoke-result.json'),JSON.stringify({passed:true,soundPacketsVerified:true,backendBridgeVerified:true,backendPackLifecycleVerified:true,clientVersions:['1.20.2','1.20.4'],visualClientTest:false,
    pluginSha256:sha256('target/crown-cinema.jar'),packSha256:sha256('src/main/resources/cinema/crown-cinema.zip'),finishedAt:new Date().toISOString()},null,2)+'\n');
  console.log('All protocol smoke checks passed. Actual client rendering and 26.3 login remain untested.');
}

main().catch(e=>{console.error(e.stack);process.exitCode=1;}).finally(async()=>{
  stopping=true;
  for(const {client} of clients) client.end('Test complete');
  for(const server of servers) server.close();
  if(proxy && proxy.exitCode===null) {
    proxy.stdin.write('shutdown\n');
    await Promise.race([new Promise(r=>proxy.once('exit',r)),sleep(5000)]);
    if(proxy.exitCode===null) proxy.kill('SIGTERM');
  }
});
