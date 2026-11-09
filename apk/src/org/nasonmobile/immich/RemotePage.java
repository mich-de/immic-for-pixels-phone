package org.nasonmobile.immich;

/**
 * La pagina servita da Remote: un solo file, niente librerie esterne (il telefono può non avere Internet e la
 * Content-Security-Policy di Remote non le permetterebbe). Parla con /api/* (vedi Remote.route) con form urlencoded
 * e l'intestazione X-Immich-Panel. Ogni testo che arriva dal server passa da textContent, mai da innerHTML.
 * Niente barre rovesciate qui dentro: in un text block Java sarebbero sequenze di escape.
 */
final class RemotePage {
    private RemotePage() {
    }

    static final String HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>Immich Server</title>
<style>
:root{--bg:#f3f4f8;--card:#fff;--text:#14161c;--muted:#5d6475;--line:#e3e6ee;--accent:#4250af;--accent-soft:#4250af1f;
--ok:#1e8e4f;--warn:#b26a00;--bad:#c62828;--surface:#f0f2f7}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#1a1d24;--text:#e8eaf0;--muted:#9aa1b2;--line:#2a2f3a;
--accent:#8f9cf5;--accent-soft:#8f9cf526;--ok:#4cc38a;--warn:#f0b04a;--bad:#ff7a7a;--surface:#232731}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:760px;margin:0 auto;padding:20px 16px 48px}
header{display:flex;align-items:center;gap:12px;margin-bottom:18px}
header h1{font-size:22px;margin:0;flex:1}
.pill{display:inline-flex;align-items:center;gap:6px;padding:4px 12px;border-radius:999px;font-weight:600;font-size:13px;background:var(--surface)}
.pill i{width:8px;height:8px;border-radius:50%;background:var(--muted)}
.pill.RUNNING i{background:var(--ok)}.pill.STARTING i,.pill.INSTALLING i,.pill.STOPPING i{background:var(--warn)}.pill.ERROR i{background:var(--bad)}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:18px;margin-bottom:14px}
.card h2{font-size:16px;margin:0 0 4px}
.muted{color:var(--muted);font-size:13px}
.row{display:flex;flex-wrap:wrap;gap:10px;margin-top:12px}
button{font:inherit;font-weight:600;border:0;border-radius:999px;padding:9px 16px;cursor:pointer;background:var(--accent-soft);color:var(--accent)}
button.primary{background:var(--accent);color:#fff}
button.danger{color:var(--bad);background:transparent;border:1px solid var(--line)}
button:disabled{opacity:.45;cursor:default}
.banner{display:none;border-radius:14px;padding:12px 14px;margin-bottom:14px;background:var(--accent-soft);color:var(--text)}
.banner.show{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.banner b{flex:1;min-width:200px}
label.sw{display:flex;align-items:flex-start;gap:12px;padding:10px 0;border-top:1px solid var(--line);cursor:pointer}
label.sw:first-of-type{border-top:0}
label.sw span{flex:1}
input[type=checkbox]{width:20px;height:20px;accent-color:var(--accent);margin-top:2px;flex:none}
.field{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;border-top:1px solid var(--line)}
select,input[type=password],input[type=text]{font:inherit;color:var(--text);background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:8px 10px;max-width:100%}
.status{margin-top:8px;font-size:13px;color:var(--muted);white-space:pre-wrap;overflow-wrap:anywhere}
a{color:var(--accent)}
pre{background:var(--surface);border-radius:12px;padding:12px;font-size:12px;max-height:420px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;margin:12px 0 0}
#toast{position:fixed;left:50%;bottom:20px;transform:translateX(-50%);max-width:calc(100% - 32px);background:var(--text);color:var(--bg);
padding:10px 16px;border-radius:12px;font-size:14px;opacity:0;transition:opacity .2s;pointer-events:none}
#toast.show{opacity:1}
#login{max-width:380px;margin:12vh auto 0}
#login input{width:100%;margin:12px 0}
.hidden{display:none}
.warn{color:var(--warn)}.bad{color:var(--bad)}
</style>
</head>
<body>
<main>
<section id="login" class="card hidden">
<h2>Immich Server</h2>
<p class="muted">Enter the password shown in the app on the phone (card "Remote configuration").</p>
<form id="loginForm"><input id="pw" type="password" autocomplete="current-password" placeholder="xxxx-xxxx-xxxx" autofocus>
<button class="primary" type="submit">Sign in</button></form>
<p id="loginMsg" class="status"></p>
</section>

<div id="app" class="hidden">
<header><h1>Immich Server</h1><span id="pill" class="pill"><i></i><span id="pillText">…</span></span></header>

<div id="updBanner" class="banner"><b id="updBannerText"></b><button class="primary" data-act="update_install">Download and install</button></div>

<section class="card">
<h2>Server</h2>
<div id="detail" class="muted"></div>
<div id="urls" class="status"></div>
<div id="health" class="status"></div>
<div class="row">
<button id="bStart" class="primary" data-act="start">Start</button>
<button id="bRestart" data-act="restart">Restart</button>
<button id="bStop" class="danger" data-act="stop">Stop</button>
</div>
</section>

<section class="card">
<h2>Updates</h2>
<div class="muted">Installed: <b id="version">–</b></div>
<div id="updStatus" class="status"></div>
<label class="sw"><input type="checkbox" data-key="update_check"><span>Check for updates every day (with a notification on the phone)</span></label>
<div class="row"><button data-act="update_check">Check now</button><button id="bInstall" class="primary" data-act="update_install">Download and install</button></div>
<p class="muted">Android always asks for a tap on the phone before installing: after the download, tap the notification there.</p>
</section>

<section class="card">
<h2>Start</h2>
<label class="sw"><input type="checkbox" data-key="autostart"><span>Start the server when the phone turns on</span></label>
</section>

<section class="card">
<h2>Gallery and Google Photos</h2>
<label class="sw"><input type="checkbox" data-key="export_enabled"><span>Copy new photos to the gallery automatically</span></label>
<label class="sw"><input type="checkbox" data-key="export_all_users"><span>Include the photos of the other Immich users too</span></label>
<div class="field"><span>Delete the gallery copy after</span><select data-key="export_keep_days"></select></div>
<div class="field"><span>Gallery copies waiting for backup: at most</span><select data-key="export_cap_gb"></select></div>
<div id="stExport" class="status"></div>
<label class="sw"><input type="checkbox" data-key="dcim_originals"><span>Originals directly in DCIM/Immich (restarts the server)</span></label>
<div id="stDcim" class="status"></div>
</section>

<section class="card">
<h2>Delete originals from Immich</h2>
<div class="field"><span>Delete the original from Immich after</span><select data-key="prune_days"></select></div>
<label class="sw"><input type="checkbox" data-key="prune_emergency"><span>Emergency: also when space is critical, even if set to "Never"</span></label>
<div class="field"><span>Immich API key <span id="keySet" class="muted"></span></span></div>
<div class="row"><input id="apiKey" type="password" autocomplete="off" placeholder="paste a new API key" style="flex:1;min-width:200px">
<button id="bKey">Save and test</button></div>
<div id="stPrune" class="status"></div>
</section>

<section class="card">
<h2>Photos whose file is gone</h2>
<label class="sw"><input type="checkbox" data-key="missing_cleanup"><span>Remove them from Immich every night</span></label>
<div class="row"><button data-act="missing_cleanup_now">Clean up now</button></div>
<div id="stMissing" class="status"></div>
</section>

<section class="card">
<h2>Battery</h2>
<label class="sw"><input type="checkbox" data-key="battery_guard_enabled"><span>Remind me to unplug and plug back in</span></label>
<div class="field"><span>Unplug above</span><select data-key="battery_guard_high"></select></div>
<div class="field"><span>Plug back in below</span><select data-key="battery_guard_low"></select></div>
<div id="stBattery" class="status"></div>
</section>

<section class="card">
<h2>Advanced</h2>
<label class="sw"><input type="checkbox" data-key="proot_no_seccomp"><span>proot without seccomp (only if the server hangs on start; applies at the next start)</span></label>
</section>

<section class="card">
<h2>Logs</h2>
<div class="row"><select id="logName"><option value="immich">Immich</option><option value="setup">Setup</option>
<option value="postgres">PostgreSQL</option><option value="valkey">Valkey</option></select><button id="bLog">Show</button></div>
<pre id="log" class="hidden"></pre>
</section>

<div class="row"><button id="bLogout" class="danger">Sign out</button></div>
</div>
</main>
<div id="toast"></div>
<script>
const CHOICES={
 export_keep_days:[[0,"Never"],[3,"3 days"],[5,"5 days"],[7,"7 days"],[30,"30 days"]],
 export_cap_gb:[[0,"No cap"],[5,"5 GB"],[10,"10 GB"],[20,"20 GB"],[50,"50 GB"]],
 prune_days:[[0,"Never (default)"],[2,"2 days"],[3,"3 days"],[5,"5 days"],[7,"7 days"]],
 battery_guard_high:[[60,"60%"],[70,"70%"],[80,"80%"],[90,"90%"]],
 battery_guard_low:[[20,"20%"],[30,"30%"],[40,"40%"],[50,"50%"]]};
const $=id=>document.getElementById(id);
let timer=null,toastTimer=null,last=null;

function toast(t){const e=$("toast");e.textContent=t;e.classList.add("show");clearTimeout(toastTimer);toastTimer=setTimeout(()=>e.classList.remove("show"),5000);}

async function api(path,body){
 const opt=body===undefined?{}:{method:"POST",headers:{"X-Immich-Panel":"1","Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams(body)};
 const r=await fetch(path,Object.assign({credentials:"same-origin",cache:"no-store"},opt));
 if(r.status===401&&path!=="/api/login"){showLogin();throw new Error("login");}
 const type=r.headers.get("content-type")||"";
 const data=type.indexOf("json")>=0?await r.json():await r.text();
 if(!r.ok)throw new Error((data&&data.error)||("HTTP "+r.status));
 return data;
}

function showLogin(){clearInterval(timer);timer=null;$("app").classList.add("hidden");$("login").classList.remove("hidden");$("pw").focus();}
function showApp(){$("login").classList.add("hidden");$("app").classList.remove("hidden");}

$("loginForm").addEventListener("submit",async e=>{
 e.preventDefault();$("loginMsg").textContent="";
 try{await api("/api/login",{password:$("pw").value});$("pw").value="";start();}
 catch(err){$("loginMsg").textContent=err.message;}
});

for(const sel of document.querySelectorAll("select[data-key]")){
 for(const [v,l] of CHOICES[sel.dataset.key]){const o=document.createElement("option");o.value=v;o.textContent=l;sel.appendChild(o);}
}

async function save(key,value,el){
 try{const r=await api("/api/settings",{[key]:value});toast((r.notes&&r.notes.length)?r.notes.join(" "):"Saved");}
 catch(err){toast(err.message);}
 refresh();
}
for(const el of document.querySelectorAll("[data-key]")){
 el.addEventListener("change",()=>{el.dataset.dirty="1";save(el.dataset.key,el.type==="checkbox"?String(el.checked):el.value,el);});
}

const CONFIRM={stop:"Stop the server? Photos won't upload until it starts again. While the server is stopped this page may stop answering: then start it from the phone.",
 restart:"Restart the server? It's unavailable for a minute or two."};
for(const b of document.querySelectorAll("[data-act]")){
 b.addEventListener("click",async()=>{
  const a=b.dataset.act;
  if(CONFIRM[a]&&!confirm(CONFIRM[a]))return;
  b.disabled=true;
  try{const r=await api("/api/action",{action:a});toast(r.message||"Done");}
  catch(err){toast(err.message);}
  b.disabled=false;refresh();
 });
}

$("bKey").addEventListener("click",async()=>{
 const k=$("apiKey").value.trim();
 if(!k&&!confirm("Remove the saved API key?"))return;
 $("bKey").disabled=true;
 try{const r=await api("/api/settings",{prune_api_key:k});$("apiKey").value="";toast(r.notes.join(" "));}
 catch(err){toast(err.message);}
 $("bKey").disabled=false;refresh();
});

$("bLog").addEventListener("click",async()=>{
 try{const t=await api("/api/log?name="+encodeURIComponent($("logName").value));const p=$("log");p.textContent=t||"(empty)";p.classList.remove("hidden");p.scrollTop=p.scrollHeight;}
 catch(err){toast(err.message);}
});

$("bLogout").addEventListener("click",async()=>{try{await api("/api/logout",{});}catch(e){}showLogin();});

function render(s){
 last=s;
 const labels={IDLE:"Stopped",INSTALLING:"Installing",STARTING:"Starting",RUNNING:"Running",STOPPING:"Stopping",ERROR:"Error"};
 $("pill").className="pill "+s.state;
 $("pillText").textContent=labels[s.state]||s.state;
 $("detail").textContent=s.detail+(s.progress>=0?" ("+s.progress+"%)":"");
 $("urls").textContent=s.urls.length?"Immich: "+s.urls.join("  ·  "):"No network address.";
 $("health").textContent=s.storage+(s.battery?"  ·  "+s.battery:"");
 $("health").className="status"+(s.storage_level===1?" bad":s.storage_level===0?" warn":"");
 const stopped=s.state==="IDLE"||s.state==="ERROR";
 $("bStart").disabled=!stopped;$("bStop").disabled=s.state==="IDLE";
 $("version").textContent=s.version||"not installed yet";
 const u=s.update;
 $("updStatus").textContent=u.status||"";
 $("bInstall").disabled=!u.available||u.busy;
 const ban=$("updBanner");
 if(u.available&&!u.busy){$("updBannerText").textContent=u.title+(u.size_mb?" ("+u.size_mb+" MB)":"");ban.classList.add("show");}
 else ban.classList.remove("show");
 $("stExport").textContent=s.status.export;
 $("stDcim").textContent=s.status.dcim;
 $("stPrune").textContent=[s.status.prune_days,s.status.prune].filter(Boolean).join("  ·  ");
 $("stMissing").textContent=s.status.missing;
 $("stBattery").textContent=s.battery;
 $("keySet").textContent=s.settings.prune_api_key_set?"(saved)":"(none)";
 for(const el of document.querySelectorAll("[data-key]")){
  if(document.activeElement===el)continue;
  const v=s.settings[el.dataset.key];
  if(el.type==="checkbox")el.checked=!!v;else el.value=String(v);
 }
 document.title=(labels[s.state]||s.state)+" · Immich Server";
}

async function refresh(){
 try{const s=await api("/api/state");showApp();render(s);}
 catch(err){if(err.message!=="login")toast(err.message);}
}
function start(){refresh();clearInterval(timer);timer=setInterval(()=>{if(!document.hidden)refresh();},4000);}
document.addEventListener("visibilitychange",()=>{if(!document.hidden&&timer)refresh();});
start();
</script>
</body>
</html>
""";
}
