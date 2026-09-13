// GUI smoke test for one running test server (used by run-matrix.sh when BOT_NODE_PATH is set).
// The harness ops the bot and gives it 32 stone + 5 bedrock after it joins. The shop "CompatShop" must hold
// DIRT (idx 0) and STONE (idx 1) and be enabled.
// usage: NODE_PATH=<dir with mineflayer> node gui-bot.js <host> <port> [mc version]
const mineflayer = require('mineflayer')
const [host, port, version] = process.argv.slice(2)

const bot = mineflayer.createBot({ host, port: Number(port), username: 'DSBot', auth: 'offline', version: version || false })
const results = []
const sleep = ms => new Promise(r => setTimeout(r, ms))
const count = name => bot.inventory.items().filter(i => i.name === name).reduce((a, i) => a + i.count, 0)
const check = (name, ok, detail) => { results.push({ name, ok, detail }); console.log(`${ok ? 'ok  ' : 'FAIL'} ${name} ${detail || ''}`) }

function titleText (w) {
  try { return typeof w.title === 'string' ? w.title : JSON.stringify(w.title) } catch (e) { return String(w.title) }
}

function waitWindow (ms = 8000) {
  return new Promise(resolve => {
    const t = setTimeout(() => { bot.removeListener('windowOpen', on); resolve(null) }, ms)
    function on (w) { clearTimeout(t); resolve(w) }
    bot.once('windowOpen', on)
  })
}

async function waitFor (cond, ms) {
  for (let i = 0; i < ms / 250; i++) { if (cond()) return true; await sleep(250) }
  return cond()
}

async function run () {
  const gotItems = await waitFor(() => count('stone') >= 32 && count('bedrock') >= 5, 60000)
  check('items given', gotItems, `stone=${count('stone')} bedrock=${count('bedrock')}`)
  if (!gotItems) return
  await sleep(1000)

  // 1. shop GUI
  let w = waitWindow()
  bot.chat('/ds shop CompatShop')
  let win = await w
  check('shop GUI opens', !!win, win ? titleText(win).slice(0, 80) : 'no window')
  if (!win) return
  const shopItem = win.slots[1]
  check('stone shown in slot 1', !!shopItem && shopItem.name === 'stone', shopItem ? shopItem.name : 'empty')

  // 2. trade view
  w = waitWindow()
  await bot.clickWindow(1, 0, 0)
  win = await w
  check('trade view opens', !!win, win ? titleText(win).slice(0, 80) : 'no window')
  if (!win) return

  // 3. buy 1 (slot 11), sell 1 (slot 2)
  let before = count('stone')
  await bot.clickWindow(11, 0, 0)
  await waitFor(() => count('stone') === before + 1, 5000)
  check('buy 1 stone', count('stone') === before + 1, `${before} -> ${count('stone')}`)

  before = count('stone')
  await bot.clickWindow(2, 0, 0)
  await waitFor(() => count('stone') === before - 1, 5000)
  check('sell 1 stone', count('stone') === before - 1, `${before} -> ${count('stone')}`)

  bot.closeWindow(win)
  await sleep(1000)

  // 4. /sell all: stone is bought by the shop, bedrock is not in any shop and must stay
  bot.chat('/sell all')
  await waitFor(() => count('stone') === 0, 8000)
  check('/sell all sells shop items', count('stone') === 0, `stone=${count('stone')}`)
  check('/sell all keeps other items', count('bedrock') === 5, `bedrock=${count('bedrock')}`)

  // 5. start page
  w = waitWindow()
  bot.chat('/ds')
  win = await w
  check('start page opens', !!win, win ? titleText(win).slice(0, 80) : 'no window')
  if (win) bot.closeWindow(win)
}

bot.once('spawn', () => {
  run().catch(e => check('bot script', false, e.stack))
    .finally(async () => {
      const ok = results.length >= 9 && results.every(r => r.ok)
      console.log(ok ? 'GUI PASS' : 'GUI FAIL')
      await sleep(500)
      bot.quit()
      setTimeout(() => process.exit(ok ? 0 : 1), 1000)
    })
})
bot.on('kicked', r => { console.log('kicked', JSON.stringify(r)); console.log('GUI FAIL'); process.exit(1) })
bot.on('error', e => { console.log('error', e.message); console.log('GUI FAIL'); process.exit(1) })
setTimeout(() => { console.log('timeout'); console.log('GUI FAIL'); process.exit(1) }, 150000)
