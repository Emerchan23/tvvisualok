const fs = require('fs');
const path = require('path');
const out = path.join(process.cwd(), 'diagnostics', 'network-panel-' + new Date().toISOString().replace(/[:.]/g,'-') + '.jsonl');
async function targets(port){ return await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); }
async function attach(name, port){
  const list = await targets(port);
  const page = list.find(t => t.type==='page' && String(t.url||'').includes('/unique-panel/panel-screen/')) || list.find(t=>t.type==='page');
  if(!page) throw new Error('no page '+port);
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id=0;
  const pending = new Map();
  function send(method, params={}){ ws.send(JSON.stringify({id:++id, method, params})); }
  ws.onopen = () => { send('Network.enable'); send('Runtime.enable'); };
  ws.onmessage = ev => {
    const msg = JSON.parse(ev.data);
    if(msg.method === 'Network.requestWillBeSent' || msg.method === 'Network.responseReceived' || msg.method === 'Network.loadingFinished' || msg.method === 'Network.loadingFailed'){
      const p = msg.params || {};
      const req = p.request || {};
      const res = p.response || {};
      const url = req.url || res.url || '';
      if(url.includes('/unique-panel/') || url.includes('/api/')){
        fs.appendFileSync(out, JSON.stringify({at:new Date().toISOString(), name, method:msg.method, requestId:p.requestId, url, status:res.status, mime:res.mimeType, errorText:p.errorText})+'\n');
      }
    }
    if(msg.method === 'Runtime.consoleAPICalled'){
      const args=(msg.params.args||[]).map(a=>a.value||a.description||'');
      fs.appendFileSync(out, JSON.stringify({at:new Date().toISOString(), name, method:'console', args})+'\n');
    }
  };
  return ws;
}
(async()=>{
  await attach('chrome',9222);
  await attach('webview',9223);
  console.log(out);
  setInterval(()=>{}, 1000);
})();
