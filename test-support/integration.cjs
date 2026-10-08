'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');
const { spawn } = require('node:child_process');
const [rootArg, java, seedArg, platform, modules, authJar, failureMode = 'provider'] = process.argv.slice(2);
const root = path.resolve(rootArg), seed = path.resolve(seedArg), project = path.resolve(__dirname, '..');
assert(path.basename(root).startsWith('nordperms-test-') && !fs.existsSync(root), 'Fresh isolated test directory required');
assert(['Paper', 'Folia'].includes(platform));
const mineflayer = require(path.join(path.resolve(modules), 'mineflayer'));
const bots = [], passed = [], benchmarks = [];
let server, output = '', exited = false;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn, label, timeout = 20000) {
  const start = Date.now();
  while (!fn()) { if (Date.now() - start > timeout) throw Error('Timeout: ' + label); await sleep(50); }
}
function pass(label) { passed.push(label); console.log('PASS: ' + label); }
async function marker(command, pattern) {
  const offset = output.length;
  server.stdin.write(command + '\n');
  await until(() => pattern.test(output.slice(offset)) || exited, command);
  assert(!exited, output.slice(-5000));
  return output.slice(offset).match(pattern);
}
async function state(name) {
  const match = await marker('nptest state ' + name, new RegExp('NP_STATE ' + name + ' ((?:true|false)(?: (?:true|false)){7})'));
  return match[1].split(' ').map(s => s === 'true');
}
function offlineUuid(name) {
  const bytes = crypto.createHash('md5').update('OfflinePlayer:' + name).digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30; bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
}
const configPath = path.join(root, 'plugins/NordPerms/config.yml');
const template = fs.readFileSync(path.join(project, 'src/main/resources/config.yml'), 'utf8');
function config(staff) {
  return template.replace('      nordregen.use: true', '      probe.parent: true\n      nordregen.use: true')
    .replace('members: {}', staff ? 'members: {"' + offlineUuid('NPStaff') + '": moderators}' : 'members: {}');
}
async function join(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25649, username: name, version: '26.2', auth: 'offline', hideErrors: true });
  bot.messages = [];
  bot.on('messagestr', text => bot.messages.push(text));
  bot.on('error', error => console.error(error));
  bot.on('kicked', reason => console.error('KICKED', reason));
  bot.once('spawn', () => { bot.physicsEnabled = false; });
  bots.push(bot);
  await until(() => bot.entity && output.includes(name + ' joined the game'), 'join ' + name, 60000);
  await until(() => bot.messages.some(m => m.includes('Please register')), 'NordAuth ready ' + name);
  return bot;
}
async function register(bot) {
  bot.chat('/register SyntheticTest-2026! SyntheticTest-2026!');
  await until(() => bot.messages.some(m => m.includes('Account registered successfully')), 'register');
}
async function expectRefused(name) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25649, username: name, version: '26.2', auth: 'offline', hideErrors: true });
  let kicked = false;
  bot.on('kicked', () => { kicked = true; }); bot.on('error', () => {});
  bots.push(bot);
  await until(() => kicked, 'reject ' + name, 30000);
  assert(!output.includes(name + ' joined the game'));
}
async function main() {
  const occupied = await new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port: 25649 });
    socket.on('connect', () => { socket.destroy(); resolve(true); }); socket.on('error', () => resolve(false));
  });
  assert(!occupied, 'Test port occupied');
  fs.mkdirSync(path.dirname(configPath), { recursive: true });
  // Copy runtime only. Never copy worlds, plugins, auth data or server configuration.
  for (const name of ['server.jar', 'cache', 'libraries', 'versions', 'eula.txt']) {
    if (fs.existsSync(path.join(seed, name))) fs.cpSync(path.join(seed, name), path.join(root, name), { recursive: true });
  }
  assert(fs.readFileSync(path.join(root, 'eula.txt'), 'utf8').includes('eula=true'));
  fs.writeFileSync(path.join(root, 'server.properties'), 'server-ip=127.0.0.1\nserver-port=25649\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nallow-flight=true\nmax-players=4\nview-distance=2\nsimulation-distance=2\nlevel-name=NordPermsSynthetic\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n');
  fs.writeFileSync(configPath, config(true) + '\ngroups: broken\n');
  fs.writeFileSync(path.join(root, 'ops.json'), JSON.stringify([{ uuid: offlineUuid('NPForbidden'), name: 'NPForbidden', level: 4, bypassesPlayerLimit: false }]));
  fs.copyFileSync(path.join(project, 'target/NordPerms-1.0.0.jar'), path.join(root, 'plugins/NordPerms-1.0.0.jar'));
  fs.copyFileSync(path.resolve(authJar), path.join(root, 'plugins/NordAuth.jar'));
  fs.copyFileSync(path.join(__dirname, 'build/PermsTestProbe.jar'), path.join(root, 'plugins/PermsTestProbe.jar'));
  server = spawn(java, ['-Dterminal.jline=false', '-Dterminal.ansi=false', '-Xms256M', '-Xmx1400M', '-jar', 'server.jar', 'nogui'], { cwd: root, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  for (const stream of [server.stdout, server.stderr]) stream.on('data', bytes => { output += bytes.toString().replace(/\x1b\[[0-9;]*m/g, ''); });
  server.on('exit', () => { exited = true; });
  server.on('error', error => { output += String(error); exited = true; });
  await until(() => /Done \(/.test(output) || exited, 'startup', 120000);
  assert(!exited, output.slice(-5000));
  assert.match(output, /NordPerms startup refused/); assert.match(output, new RegExp(platform + ' version'));
  await expectRefused('NPRefused');
  fs.writeFileSync(configPath, config(true));
  await marker('nordperms reload', /NordPerms reloaded; moderator UUIDs=1/);
  pass(platform + ' rejects logins on invalid startup config and recovers through console reload');
  await expectRefused('NPForbidden');
  pass('An OP account cannot join; rejecting it does not stop the server');
  const staff = await join('NPStaff'), basic = await join('NPBasic');
  assert.deepEqual((await state('NPStaff')).slice(0, 3), [false, false, false]);
  pass('Moderator UUID alone grants nothing before NordAuth authentication');
  staff.chat('/register SyntheticTest-2026! SyntheticTest-2026!');
  await until(() => staff.messages.some(m => m.includes('must already be registered')), 'block first-registration takeover');
  pass('A configured staff identity cannot be captured by first registration');
  fs.writeFileSync(configPath, config(false));
  await marker('nordperms reload', /NordPerms reloaded; moderator UUIDs=0/);
  await register(staff); await register(basic);
  fs.writeFileSync(configPath, config(true));
  await marker('nordperms reload', /NordPerms reloaded; moderator UUIDs=1/);
  assert.deepEqual(await state('NPStaff'), [true, true, true, false, false, false, true, false]);
  assert.deepEqual(await state('NPBasic'), [false, false, false, false, false, false, false, false]);
  pass('Authenticated moderator gets exact permissions and Bukkit child; ordinary player has no administrative rights');
  await marker('nptest attach NPBasic', /NP_ATTACHED/);
  assert.deepEqual(await state('NPBasic'), [false, false, false, true, false, false, false, false]);
  pass('Standard third-party attachment works but cannot override protected staff nodes');
  await marker('nptest timed NPBasic', /NP_TIMED/);
  assert.equal((await state('NPBasic'))[4], true);
  await sleep(750);
  assert.equal((await state('NPBasic'))[4], false);
  pass('Temporary attachment expires using entity scheduler on both platforms');
  fs.writeFileSync(configPath, config(true) + '\ngroups: broken\n');
  await marker('nordperms reload', /reload rejected; previous policy retained/);
  assert.equal((await state('NPStaff'))[0], true);
  pass('Malformed/duplicate-key reload leaves previous policy intact');
  fs.writeFileSync(configPath, config(false));
  await marker('nordperms reload', /NordPerms reloaded; moderator UUIDs=0/);
  assert.equal((await state('NPStaff'))[0], false);
  pass('Removing membership revokes privileges of a connected player without reconnecting');
  fs.writeFileSync(configPath, config(true));
  await marker('nordperms reload', /NordPerms reloaded; moderator UUIDs=1/);
  staff.chat('/nordperms reload');
  await until(() => staff.messages.some(m => m.includes('only from the server console')), 'deny in-game management');
  pass('Even authenticated moderators cannot manage their own permissions');
  staff.chat('/nordauth:resetpassword NPStaff ChangedSynthetic!');
  await until(() => staff.messages.some(m => m.includes('Staff/self/offline password recovery')), 'protect namespaced self-reset');
  const count = staff.messages.length;
  staff.chat('/resetpassword OfflineName ChangedSynthetic!');
  await until(() => staff.messages.slice(count).some(m => m.includes('Staff/self/offline password recovery')), 'protect offline account');
  pass('Namespaced self/staff and unknown offline account resets are console-only');
  staff.chat('/resetpassword NPBasic ChangedSynthetic!');
  await until(() => staff.messages.some(m => m.includes('Password for NPBasic has been reset')), 'ordinary password recovery');
  pass('Moderator can reset an online ordinary player using the real NordAuth command');
  for (const name of ['NPBasic', 'NPStaff']) {
    const match = await marker('nptest bench ' + name, new RegExp('NP_BENCH ' + name + ' (\\d+) (\\d+) (\\d+)'));
    const nsPerCheck = +match[2] / +match[1]; benchmarks.push({ name, iterations: +match[1], nsPerCheck, granted: +match[3] });
    console.log('BENCH ' + name + ': ' + nsPerCheck.toFixed(1) + ' ns/check (synthetic hot-path measurement, not 600-player TPS)');
  }
  assert(!/NP_PROBE_FAILED|Could not pass event|Thread failed main thread check|Cannot read world asynchronously|Cannot install permissible|Cannot verify permissible/i.test(output));
  pass('No injection, event or region ownership errors');
  server.stdin.write('nptest disable' + failureMode + ' NPStaff\n');
  await until(() => exited, 'fail-closed disable ' + failureMode, 45000);
  if (failureMode === 'auth') assert.match(output, /NordAuth was disabled while the server was running; stopping server for safety/);
  else assert.match(output, /Disabling NordPerms/);
  pass('Hot disabling ' + failureMode + ' stops the test server instead of restoring unsafe rights');
}
(async () => {
  try { await main(); }
  catch (error) { console.error(error); console.error(output.slice(-6000)); process.exitCode = 1; }
  finally {
    bots.forEach(bot => bot.quit());
    if (server && !exited) {
      server.stdin.write('stop\n');
      try { await until(() => exited, 'shutdown', 45000); } catch { server.kill(); process.exitCode = 1; }
    }
    if (fs.existsSync(root)) {
      fs.writeFileSync(path.join(root, 'integration-output.log'), output);
      fs.writeFileSync(path.join(root, 'results.json'), JSON.stringify({ platform, passed, benchmarks, success: !process.exitCode, notes: 'Synthetic loopback test; no production data; not a 600-player TPS benchmark.' }, null, 2));
    }
  }
})();
